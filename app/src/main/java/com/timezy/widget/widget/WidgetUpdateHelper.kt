package com.timezy.widget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.timezy.widget.MainActivity
import com.timezy.widget.R
import com.timezy.widget.data.database.AppDatabase
import com.timezy.widget.data.model.CountdownEvent
import com.timezy.widget.data.model.RecurrenceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object WidgetUpdateHelper {

    const val ACTION_SCHEDULED_WIDGET_UPDATE = "com.timezy.widget.ACTION_SCHEDULED_WIDGET_UPDATE"
    private const val REQUEST_CODE_SCHEDULED_UPDATE = 7701
    private var lastUpdateTime = 0L

    const val EVENT_ID_AUTO = -1L
    const val EVENT_ID_UNASSIGNED = -2L

    private const val PREFS_NAME = "countdown_widget_prefs"
    private const val PREF_PREFIX_KEY = "appwidget_event_"
    private const val PREF_STYLE_KEY = "appwidget_style_"
    private const val PREF_COLOR_KEY = "appwidget_color_"
    private const val PREF_SHOW_SECONDS_KEY = "appwidget_show_seconds_"
    private const val PREF_LIQUID_GLASS_KEY = "appwidget_liquid_glass_"
    private const val PREF_AUTO_MODE_KEY = "appwidget_auto_mode_"

    @Volatile var prefsOverrideForTesting: SharedPreferences? = null
    @Volatile var isWidgetRemovalSupportedForTesting: Boolean? = null
    @Volatile var installedWidgetIdsOverrideForTesting: List<Int>? = null
    @Volatile var eventDaoOverrideForTesting: com.timezy.widget.data.database.EventDao? = null

    private val mainHandler by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Exception) {
            null
        }
    }
    @Volatile private var isTickerRunning = false
    private var applicationContext: Context? = null
    internal val eventCache = ConcurrentHashMap<Int, CountdownEvent>()

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun invalidateEventCache() {
        eventCache.clear()
    }

    fun getEventDao(context: Context): com.timezy.widget.data.database.EventDao? {
        eventDaoOverrideForTesting?.let { return it }
        return try {
            AppDatabase.getInstance(context).eventDao()
        } catch (_: Exception) {
            null
        }
    }

    private fun getPrefs(context: Context?): SharedPreferences {
        return prefsOverrideForTesting ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("Context must not be null when prefsOverrideForTesting is not set")
    }

    @JvmOverloads
    fun setWidgetEventId(context: Context? = null, appWidgetId: Int, eventId: Long) {
        val editor = getPrefs(context).edit()
        if (eventId > 0L) {
            editor.putLong(PREF_PREFIX_KEY + appWidgetId, eventId)
            editor.putBoolean(PREF_AUTO_MODE_KEY + appWidgetId, false)
        } else if (eventId == EVENT_ID_AUTO) {
            editor.putLong(PREF_PREFIX_KEY + appWidgetId, EVENT_ID_AUTO)
            editor.putBoolean(PREF_AUTO_MODE_KEY + appWidgetId, true)
        } else {
            editor.putLong(PREF_PREFIX_KEY + appWidgetId, EVENT_ID_UNASSIGNED)
            editor.putBoolean(PREF_AUTO_MODE_KEY + appWidgetId, false)
        }
        editor.apply()
        invalidateEventCache()
    }

    @JvmOverloads
    fun getWidgetEventId(context: Context? = null, appWidgetId: Int): Long {
        val raw = getPrefs(context).getLong(PREF_PREFIX_KEY + appWidgetId, EVENT_ID_AUTO)
        return if (raw <= 0L) EVENT_ID_AUTO else raw
    }

    @JvmOverloads
    fun getRawWidgetEventId(context: Context? = null, appWidgetId: Int): Long {
        return getPrefs(context).getLong(PREF_PREFIX_KEY + appWidgetId, EVENT_ID_AUTO)
    }

    @JvmOverloads
    fun isWidgetAutoMode(context: Context? = null, appWidgetId: Int): Boolean {
        return getPrefs(context).getBoolean(PREF_AUTO_MODE_KEY + appWidgetId, false)
    }

    @JvmOverloads
    fun clearWidgetEventId(context: Context? = null, appWidgetId: Int) {
        getPrefs(context).edit()
            .putLong(PREF_PREFIX_KEY + appWidgetId, EVENT_ID_UNASSIGNED)
            .putBoolean(PREF_AUTO_MODE_KEY + appWidgetId, false)
            .remove(PREF_COLOR_KEY + appWidgetId)
            .apply()
        eventCache.remove(appWidgetId)
    }

    @JvmOverloads
    fun setWidgetColorTheme(context: Context? = null, appWidgetId: Int, theme: com.timezy.widget.data.model.EventTheme) {
        getPrefs(context).edit().putString(PREF_COLOR_KEY + appWidgetId, theme.id).commit()
    }

    @JvmOverloads
    fun getWidgetColorTheme(context: Context? = null, appWidgetId: Int): com.timezy.widget.data.model.EventTheme {
        // 1. Single source of truth: The assigned event's theme is authoritative for its widgets
        val event = eventCache[appWidgetId] ?: try {
            if (context != null) resolveEventForWidgetSync(context, appWidgetId) else null
        } catch (_: Exception) {
            null
        }
        if (event != null) {
            return event.theme
        }

        // 2. Fallback for unassigned widgets, empty states, or test environments without an event
        val prefs = getPrefs(context)
        val colorId = prefs.getString(PREF_COLOR_KEY + appWidgetId, null)
        if (colorId != null) {
            return com.timezy.widget.data.model.EventTheme.fromId(colorId)
        }
        val defaultGlobal = try {
            if (context != null) com.timezy.widget.data.preferences.AppPreferences(context).getWidgetAccentTheme() else null
        } catch (_: Exception) {
            null
        }
        return defaultGlobal ?: com.timezy.widget.data.model.EventTheme.NEON_CYAN
    }

    @JvmOverloads
    fun hasWidgetColorTheme(context: Context? = null, appWidgetId: Int): Boolean {
        return getPrefs(context).contains(PREF_COLOR_KEY + appWidgetId)
    }

    @JvmOverloads
    fun setWidgetStyle(context: Context? = null, appWidgetId: Int, style: com.timezy.widget.data.model.CircleStyle) {
        getPrefs(context).edit().putString(PREF_STYLE_KEY + appWidgetId, style.name).commit()
    }

    @JvmOverloads
    fun setWidgetStyle(context: Context? = null, appWidgetId: Int, style: String) {
        getPrefs(context).edit().putString(PREF_STYLE_KEY + appWidgetId, style).commit()
    }

    @JvmOverloads
    fun getWidgetCircleStyle(context: Context? = null, appWidgetId: Int): com.timezy.widget.data.model.CircleStyle {
        val prefs = getPrefs(context)
        val styleName = prefs.getString(PREF_STYLE_KEY + appWidgetId, null)
        if (styleName != null) {
            return com.timezy.widget.data.model.CircleStyle.fromName(styleName)
        }
        val defaultStyle = try {
            if (context != null) com.timezy.widget.data.preferences.AppPreferences(context).getWidgetStyle() else com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS
        } catch (_: Exception) {
            com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS
        }
        prefs.edit().putString(PREF_STYLE_KEY + appWidgetId, defaultStyle.name).commit()
        return defaultStyle
    }

    @JvmOverloads
    fun getWidgetStyle(context: Context? = null, appWidgetId: Int): String {
        return getPrefs(context).getString(PREF_STYLE_KEY + appWidgetId, "LIQUID_GLASS") ?: "LIQUID_GLASS"
    }

    @JvmOverloads
    fun setWidgetShowSeconds(context: Context? = null, appWidgetId: Int, show: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_SHOW_SECONDS_KEY + appWidgetId, show).commit()
    }

    @JvmOverloads
    fun isWidgetShowSecondsEnabled(context: Context? = null, appWidgetId: Int): Boolean {
        val prefs = getPrefs(context)
        val key = PREF_SHOW_SECONDS_KEY + appWidgetId
        if (prefs.contains(key)) {
            return prefs.getBoolean(key, true)
        }
        val defaultVal = try {
            if (context != null) com.timezy.widget.data.preferences.AppPreferences(context).isWidgetShowSecondsEnabled() else true
        } catch (_: Exception) {
            true
        }
        prefs.edit().putBoolean(key, defaultVal).commit()
        return defaultVal
    }

    @JvmOverloads
    fun setWidgetLiquidGlass(context: Context? = null, appWidgetId: Int, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_LIQUID_GLASS_KEY + appWidgetId, enabled).commit()
    }

    @JvmOverloads
    fun isWidgetLiquidGlassEnabled(context: Context? = null, appWidgetId: Int): Boolean {
        val prefs = getPrefs(context)
        val key = PREF_LIQUID_GLASS_KEY + appWidgetId
        if (prefs.contains(key)) {
            return prefs.getBoolean(key, true)
        }
        val defaultVal = try {
            if (context != null) com.timezy.widget.data.preferences.AppPreferences(context).isWidgetLiquidGlassEnabled() else true
        } catch (_: Exception) {
            true
        }
        prefs.edit().putBoolean(key, defaultVal).commit()
        return defaultVal
    }

    @JvmOverloads
    fun deleteWidgetPref(context: Context? = null, appWidgetId: Int) {
        getPrefs(context).edit()
            .remove(PREF_PREFIX_KEY + appWidgetId)
            .remove(PREF_AUTO_MODE_KEY + appWidgetId)
            .remove(PREF_COLOR_KEY + appWidgetId)
            .remove(PREF_STYLE_KEY + appWidgetId)
            .remove(PREF_SHOW_SECONDS_KEY + appWidgetId)
            .remove(PREF_LIQUID_GLASS_KEY + appWidgetId)
            .apply()
        eventCache.remove(appWidgetId)
    }

    fun getAllInstalledWidgetIds(context: Context?): List<Int> {
        installedWidgetIdsOverrideForTesting?.let { return it }
        if (context == null) return emptyList()
        return try {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val circleIds = appWidgetManager.getAppWidgetIds(ComponentName(context, CircleCountdownWidgetProvider::class.java))
            val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MediumCountdownWidgetProvider::class.java))
            (circleIds + mediumIds).distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Checks whether the current launcher supports programmatic removal of pinned home-screen widgets.
     * In the standard Android AppWidget framework, provider apps cannot forcibly delete pinned widgets
     * from third-party or system launchers (only the host or the user manually can do so).
     */
    fun isWidgetRemovalSupportedByLauncher(context: Context? = null): Boolean {
        isWidgetRemovalSupportedForTesting?.let { return it }
        return false
    }

    data class WidgetRemovalResult(
        val appWidgetId: Int,
        val isRemoved: Boolean,
        val launcherLimitation: String? = null
    )

    fun removeWidgetInstanceIfSupported(context: Context? = null, appWidgetId: Int): WidgetRemovalResult {
        val supported = isWidgetRemovalSupportedByLauncher(context)
        return if (supported) {
            deleteWidgetPref(context, appWidgetId)
            WidgetRemovalResult(
                appWidgetId = appWidgetId,
                isRemoved = true,
                launcherLimitation = null
            )
        } else {
            WidgetRemovalResult(
                appWidgetId = appWidgetId,
                isRemoved = false,
                launcherLimitation = "Android launcher limitation: Third-party apps cannot forcibly remove pinned widgets from the launcher. Obsolete widgets must be manually removed by the user from their home screen."
            )
        }
    }

    fun hasActiveWidgets(context: Context): Boolean {
        return getAllInstalledWidgetIds(context).isNotEmpty()
    }

    fun anyWidgetHasSecondsEnabled(context: Context): Boolean {
        return try {
            val allIds = getAllInstalledWidgetIds(context)
            if (allIds.isEmpty()) return false
            for (id in allIds) {
                if (isWidgetShowSecondsEnabled(context, id)) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun anyWidgetHasActiveCountdownWithSeconds(context: Context): Boolean {
        return try {
            val allIds = getAllInstalledWidgetIds(context)
            if (allIds.isEmpty()) return false
            for (id in allIds) {
                if (isWidgetShowSecondsEnabled(context, id)) {
                    val event = eventCache[id] ?: try { resolveEventForWidgetSync(context, id) } catch (_: Exception) { null }
                    if (event != null) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun startLiveTicker(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext

        if (!hasActiveWidgets(appContext) || !anyWidgetHasActiveCountdownWithSeconds(appContext)) {
            stopLiveTicker()
            return
        }

        val powerManager = try { appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager } catch (_: Exception) { null }
        if (powerManager != null && !powerManager.isInteractive) {
            stopLiveTicker()
            return
        }

        if (isTickerRunning) return
        isTickerRunning = true

        mainHandler?.removeCallbacks(tickerRunnable)
        mainHandler?.post(tickerRunnable)
    }

    fun stopLiveTicker() {
        isTickerRunning = false
        mainHandler?.removeCallbacks(tickerRunnable)
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val context = applicationContext ?: return
            if (!isTickerRunning) return

            val powerManager = try { context.getSystemService(Context.POWER_SERVICE) as? PowerManager } catch (_: Exception) { null }
            if (powerManager != null && !powerManager.isInteractive) {
                stopLiveTicker()
                scheduleNextWidgetUpdate(context)
                return
            }

            if (!hasActiveWidgets(context) || !anyWidgetHasActiveCountdownWithSeconds(context)) {
                stopLiveTicker()
                return
            }

            // Fast tick: updates all widgets with seconds enabled with current millisecond
            updateAllWidgetsFastTick(context)

            // Re-align next tick to wall-clock second boundary
            val now = System.currentTimeMillis()
            val delayToNextSecond = 1000L - (now % 1000L)
            val targetDelay = if (delayToNextSecond < 50L) delayToNextSecond + 1000L else delayToNextSecond
            mainHandler?.postDelayed(this, targetDelay)
        }
    }

    private fun updateAllWidgetsFastTick(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val circleComponent = ComponentName(context, CircleCountdownWidgetProvider::class.java)
        val circleIds = appWidgetManager.getAppWidgetIds(circleComponent)
        for (id in circleIds) {
            if (isWidgetShowSecondsEnabled(context, id)) {
                val event = getOrResolveEvent(context, id)
                updateCircleWidgetSync(context, appWidgetManager, id, event)
            }
        }

        val mediumComponent = ComponentName(context, MediumCountdownWidgetProvider::class.java)
        val mediumIds = appWidgetManager.getAppWidgetIds(mediumComponent)
        for (id in mediumIds) {
            if (isWidgetShowSecondsEnabled(context, id)) {
                val event = getOrResolveEvent(context, id)
                updateMediumWidgetSync(context, appWidgetManager, id, event)
            }
        }
    }

    /**
     * Calculates the countdown progress and remaining days:
     * progress = remainingDays / totalCountdownDays
     * Clamped between 0.0 and 1.0.
     * - 1.0 = complete ring (start of countdown / event far away)
     * - 0.5 = half ring (midway)
     * - 0.0 = empty ring (event date reached or past)
     */
    fun calculateProgressAndDays(event: CountdownEvent, nowMillis: Long = System.currentTimeMillis()): Pair<Float, Int> {
        val zone = ZoneId.systemDefault()
        val effectiveTargetMillis = event.getEffectiveTargetEpochMillis(nowMillis)
        val targetDate = Instant.ofEpochMilli(effectiveTargetMillis).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val createdDate = Instant.ofEpochMilli(event.createdAtEpochMillis).atZone(zone).toLocalDate()

        val calendarRemainingDays = ChronoUnit.DAYS.between(nowDate, targetDate).toInt()

        if (calendarRemainingDays <= 0) {
            // Event date or past: ring is empty (0.0), count is 0 if today, or negative if past
            return Pair(0.0f, calendarRemainingDays)
        }

        // Determine reference total duration from creation date to target date
        val initialTotalDays = if (event.recurrence != RecurrenceType.NONE) {
            when (event.recurrence) {
                RecurrenceType.DAILY -> 1
                RecurrenceType.WEEKLY -> 7
                RecurrenceType.MONTHLY -> 30
                RecurrenceType.YEARLY -> 365
                RecurrenceType.NONE -> ChronoUnit.DAYS.between(createdDate, targetDate).toInt()
            }
        } else {
            ChronoUnit.DAYS.between(createdDate, targetDate).toInt()
        }

        val totalCountdownDays = if (initialTotalDays < calendarRemainingDays || initialTotalDays <= 0) {
            calendarRemainingDays
        } else {
            initialTotalDays
        }

        val progress = (calendarRemainingDays.toFloat() / totalCountdownDays.toFloat()).coerceIn(0.0f, 1.0f)
        return Pair(progress, calendarRemainingDays)
    }

    fun calculateProgress(event: CountdownEvent, nowMillis: Long = System.currentTimeMillis()): Pair<Float, Int> {
        return calculateProgressAndDays(event, nowMillis)
    }

    /**
     * Renders a circular countdown progress ring bitmap according to the selected CircleStyle:
     * - Inactive subtle track ring around perimeter
     * - Colored circular progress ring around center using the selected accent theme
     * - Center disc styled according to CircleStyle:
     *     * LIQUID_GLASS: translucent frosted glass with specular highlight or clean flat matte
     *     * PURE_WHITE: solid 100% white with black text and high contrast
     *     * SMOKED_GLASS: dark obsidian translucent surface with metallic border and light text
     * - Large bold number showing remaining days
     * - Small "Days" label below the number
     * - Optional seconds counter if enabled
     * - Fully transparent bitmap background so the user's wallpaper remains completely visible
     */
    fun renderCircleCountdownBitmap(
        numberText: String,
        labelText: String,
        progress: Float,
        ringColorHex: String = "#20B9E8",
        circleStyle: com.timezy.widget.data.model.CircleStyle = com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS,
        isLiquidGlassEnabled: Boolean = true,
        showSeconds: Boolean = false,
        secondsText: String? = null,
        sizePx: Int = 320,
        isMedium: Boolean = false
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val strokeWidth = sizePx * 0.084f
        val arcRadius = (sizePx - strokeWidth) / 2f - 4f

        // 1. Draw Inactive subtle track around circle perimeter
        val trackColor = when (circleStyle) {
            com.timezy.widget.data.model.CircleStyle.SMOKED_GLASS -> Color.argb(45, 255, 255, 255)
            com.timezy.widget.data.model.CircleStyle.PURE_WHITE -> Color.parseColor("#E5E7EB")
            com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS -> if (isLiquidGlassEnabled) Color.argb(120, 226, 232, 240) else Color.parseColor("#E2E8F0")
        }
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = trackColor
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
        }
        val oval = RectF(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        // 2. Draw Active Colored Progress Arc (shrinks as event approaches)
        if (progress > 0.0f) {
            val sweep = (progress.coerceIn(0.0f, 1.0f) * 360f)
            val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = try {
                    Color.parseColor(ringColorHex)
                } catch (e: Exception) {
                    Color.parseColor("#20B9E8")
                }
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                strokeCap = Paint.Cap.ROUND
            }
            if (sweep >= 359.5f) {
                canvas.drawArc(oval, -90f, 360f, false, progressPaint)
            } else {
                canvas.drawArc(oval, -90f, sweep, false, progressPaint)
            }
        }

        // 3. Draw Center Disc according to CircleStyle
        val innerDiscRadius = arcRadius - (strokeWidth / 2f) + 1.5f

        when (circleStyle) {
            com.timezy.widget.data.model.CircleStyle.PURE_WHITE -> {
                // Solid pure white center disc, crisp high contrast
                val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy, innerDiscRadius, discPaint)

                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E2E8F0")
                    style = Paint.Style.STROKE
                    this.strokeWidth = sizePx * 0.008f
                }
                canvas.drawCircle(cx, cy, innerDiscRadius - 1f, borderPaint)
            }
            com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS -> {
                if (isLiquidGlassEnabled) {
                    // Refined translucent glass surface with subtle radial gradient highlight
                    val glassGradient = android.graphics.RadialGradient(
                        cx,
                        cy - (innerDiscRadius * 0.25f),
                        innerDiscRadius * 1.25f,
                        intArrayOf(
                            Color.argb(235, 255, 255, 255), // 92% white highlight at top
                            Color.argb(195, 241, 245, 249), // 76% frosted glass center
                            Color.argb(145, 226, 232, 240)  // 57% soft glass edge
                        ),
                        floatArrayOf(0.0f, 0.55f, 1.0f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = glassGradient
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(cx, cy, innerDiscRadius, discPaint)

                    // Subtle specular glass border
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(220, 255, 255, 255)
                        style = Paint.Style.STROKE
                        this.strokeWidth = sizePx * 0.012f
                    }
                    canvas.drawCircle(cx, cy, innerDiscRadius - 1f, borderPaint)
                } else {
                    // Clean non-glass translucent surface (wallpaper visible, but no glass gradient or specular border)
                    val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(85, 226, 232, 240)
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(cx, cy, innerDiscRadius, discPaint)

                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(130, 203, 213, 225)
                        style = Paint.Style.STROKE
                        this.strokeWidth = sizePx * 0.008f
                    }
                    canvas.drawCircle(cx, cy, innerDiscRadius - 1f, borderPaint)
                }
            }
            com.timezy.widget.data.model.CircleStyle.SMOKED_GLASS -> {
                // Dark translucent circular surface (premium obsidian / smoked glass)
                val darkGradient = android.graphics.RadialGradient(
                    cx,
                    cy,
                    innerDiscRadius * 1.1f,
                    intArrayOf(
                        Color.argb(215, 30, 35, 45),  // 84% dark smoked glass
                        Color.argb(230, 16, 20, 27)   // 90% obsidian slate
                    ),
                    floatArrayOf(0.0f, 1.0f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = darkGradient
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy, innerDiscRadius, discPaint)

                // Subtle gray / metallic border
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(110, 75, 85, 99)
                    style = Paint.Style.STROKE
                    this.strokeWidth = sizePx * 0.01f
                }
                canvas.drawCircle(cx, cy, innerDiscRadius - 1f, borderPaint)
            }
        }

        // 4. Determine Text Colors
        val numColor = when (circleStyle) {
            com.timezy.widget.data.model.CircleStyle.PURE_WHITE -> Color.parseColor("#000000")
            com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS -> Color.parseColor("#0F172A")
            com.timezy.widget.data.model.CircleStyle.SMOKED_GLASS -> Color.parseColor("#F8FAFC")
        }

        val lblColor = when (circleStyle) {
            com.timezy.widget.data.model.CircleStyle.PURE_WHITE -> Color.parseColor("#111827")
            com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS -> Color.parseColor("#334155")
            com.timezy.widget.data.model.CircleStyle.SMOKED_GLASS -> Color.parseColor("#94A3B8")
        }

        val secColor = when (circleStyle) {
            com.timezy.widget.data.model.CircleStyle.PURE_WHITE -> Color.parseColor("#4B5563")
            com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS -> Color.parseColor("#475569")
            com.timezy.widget.data.model.CircleStyle.SMOKED_GLASS -> try { Color.parseColor(ringColorHex) } catch (e: Exception) { Color.parseColor("#94A3B8") }
        }

        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = numColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val baseSize = sizePx * 0.35f
            textSize = when {
                numberText.length <= 2 -> baseSize
                numberText.length == 3 -> baseSize * 0.76f
                numberText.length == 4 -> baseSize * 0.60f
                else -> baseSize * 0.48f
            }
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lblColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.088f
        }

        val numBounds = Rect()
        numberPaint.getTextBounds(numberText, 0, numberText.length, numBounds)
        val numHeight = numBounds.height().toFloat()

        val lblBounds = Rect()
        labelPaint.getTextBounds(labelText, 0, labelText.length, lblBounds)
        val lblHeight = lblBounds.height().toFloat()

        val gap = sizePx * 0.030f
        val totalTextHeight = numHeight + gap + lblHeight
        val blockTop = cy - (totalTextHeight / 2f)

        val numberBaseline = blockTop + numHeight
        val labelBaseline = numberBaseline + gap + lblHeight
        canvas.drawText(numberText, cx, numberBaseline, numberPaint)
        canvas.drawText(labelText, cx, labelBaseline, labelPaint)

        return bitmap
    }

    fun renderEmptyCircleCountdownBitmap(
        sizePx: Int = 320,
        circleStyle: com.timezy.widget.data.model.CircleStyle = com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS,
        isLiquidGlassEnabled: Boolean = true
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val strokeWidth = sizePx * 0.084f
        val arcRadius = (sizePx - strokeWidth) / 2f - 4f

        // 1. Inactive subtle track
        val trackColor = when (circleStyle) {
            com.timezy.widget.data.model.CircleStyle.SMOKED_GLASS -> Color.argb(45, 255, 255, 255)
            com.timezy.widget.data.model.CircleStyle.PURE_WHITE -> Color.parseColor("#E5E7EB")
            com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS -> if (isLiquidGlassEnabled) Color.argb(120, 226, 232, 240) else Color.parseColor("#E2E8F0")
        }
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = trackColor
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
        }
        val arcRect = RectF(
            cx - arcRadius,
            cy - arcRadius,
            cx + arcRadius,
            cy + arcRadius
        )
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        // 2. Center Disc
        val innerDiscRadius = arcRadius - (strokeWidth / 2f)
        when (circleStyle) {
            com.timezy.widget.data.model.CircleStyle.PURE_WHITE -> {
                val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy, innerDiscRadius, discPaint)
            }
            com.timezy.widget.data.model.CircleStyle.LIQUID_GLASS -> {
                if (isLiquidGlassEnabled) {
                    val glassGradient = android.graphics.RadialGradient(
                        cx,
                        cy - (innerDiscRadius * 0.25f),
                        innerDiscRadius * 1.25f,
                        intArrayOf(
                            Color.argb(235, 255, 255, 255),
                            Color.argb(195, 241, 245, 249),
                            Color.argb(145, 226, 232, 240)
                        ),
                        floatArrayOf(0.0f, 0.55f, 1.0f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = glassGradient
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(cx, cy, innerDiscRadius, discPaint)
                } else {
                    val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(85, 226, 232, 240)
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(cx, cy, innerDiscRadius, discPaint)
                }
            }
            com.timezy.widget.data.model.CircleStyle.SMOKED_GLASS -> {
                val darkGradient = android.graphics.RadialGradient(
                    cx,
                    cy,
                    innerDiscRadius * 1.1f,
                    intArrayOf(
                        Color.argb(215, 30, 35, 45),
                        Color.argb(230, 16, 20, 27)
                    ),
                    floatArrayOf(0.0f, 1.0f),
                    android.graphics.Shader.TileMode.CLAMP
                )
                val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = darkGradient
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy, innerDiscRadius, discPaint)
            }
        }

        // 3. Clean "+" Symbol and "Create" label
        val symColor = when (circleStyle) {
            com.timezy.widget.data.model.CircleStyle.SMOKED_GLASS -> Color.parseColor("#CBD5E1")
            else -> Color.parseColor("#9CA3AF")
        }
        val lblColor = when (circleStyle) {
            com.timezy.widget.data.model.CircleStyle.SMOKED_GLASS -> Color.parseColor("#94A3B8")
            else -> Color.parseColor("#6B7280")
        }

        val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = symColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.36f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lblColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.088f
        }

        val symBounds = Rect()
        val symbolText = "+"
        symbolPaint.getTextBounds(symbolText, 0, symbolText.length, symBounds)
        val symHeight = symBounds.height().toFloat()

        val lblBounds = Rect()
        val labelText = "Create"
        labelPaint.getTextBounds(labelText, 0, labelText.length, lblBounds)
        val lblHeight = lblBounds.height().toFloat()

        val gap = sizePx * 0.038f
        val totalTextHeight = symHeight + gap + lblHeight
        val blockTop = cy - (totalTextHeight / 2f)
        val symBaseline = blockTop + symHeight
        val lblBaseline = symBaseline + gap + lblHeight

        canvas.drawText(symbolText, cx, symBaseline, symbolPaint)
        canvas.drawText(labelText, cx, lblBaseline, labelPaint)

        return bitmap
    }

    /**
     * Formats the secondary countdown timer displayed beneath the widget title.
     * Uses the event's target date/time and the supplied nowMillis as the single source of truth.
     * Correctly handles:
     * - Sub-day remainder for distant events (e.g. "2h 44m 18s left" for an event 1466 days away)
     * - Imminent events (e.g. "44m 18s left", "18s left")
     * - Zero countdown boundary (e.g. "0s left")
     * - Expired / past events (e.g. "2h 44m 18s ago")
     * - Date-only events without specific time (e.g. "Today!", "Tomorrow", "3 days ago")
     */
    fun formatSecondaryTimer(
        event: CountdownEvent,
        remainingTime: com.timezy.widget.data.model.TimeRemaining,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val effectiveTarget = event.getEffectiveTargetEpochMillis(nowMillis)
        val diffMillis = effectiveTarget - nowMillis

        if (!event.hasSpecificTime) {
            val zone = ZoneId.systemDefault()
            val targetDate = Instant.ofEpochMilli(effectiveTarget).atZone(zone).toLocalDate()
            val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

            if (targetDate.isEqual(nowDate)) {
                return "Today!"
            }
            if (targetDate.isEqual(nowDate.plusDays(1))) {
                return "Tomorrow"
            }
            if (targetDate.isBefore(nowDate)) {
                val daysAgo = ChronoUnit.DAYS.between(targetDate, nowDate)
                return if (daysAgo == 1L) "1 day ago" else "$daysAgo days ago"
            }
            // Future all-day event: show sub-day remainder or time until target date
            if (diffMillis > 0) {
                val totalSeconds = diffMillis / 1000
                val seconds = (totalSeconds % 60).toInt()
                val totalMinutes = totalSeconds / 60
                val minutes = (totalMinutes % 60).toInt()
                val totalHours = totalMinutes / 60
                val hours = (totalHours % 24).toInt()

                return when {
                    hours > 0 -> String.format(java.util.Locale.US, "%dh %dm %02ds left", hours, minutes, seconds)
                    minutes > 0 -> String.format(java.util.Locale.US, "%dm %02ds left", minutes, seconds)
                    seconds > 0 -> "${seconds}s left"
                    else -> "Today!"
                }
            }
            return "Today!"
        }

        // Specific time event:
        val isPast = event.isPast(nowMillis) || diffMillis <= 0
        val absDiff = abs(diffMillis)
        val totalSeconds = absDiff / 1000
        val seconds = (totalSeconds % 60).toInt()
        val totalMinutes = totalSeconds / 60
        val minutes = (totalMinutes % 60).toInt()
        val totalHours = totalMinutes / 60
        val hours = (totalHours % 24).toInt()

        return if (isPast) {
            when {
                absDiff < 1000 -> "0s left"
                hours > 0 -> String.format(java.util.Locale.US, "%dh %dm %02ds ago", hours, minutes, seconds)
                minutes > 0 -> String.format(java.util.Locale.US, "%dm %02ds ago", minutes, seconds)
                seconds > 0 -> "${seconds}s ago"
                else -> "Event completed"
            }
        } else {
            when {
                hours > 0 -> String.format(java.util.Locale.US, "%dh %dm %02ds left", hours, minutes, seconds)
                minutes > 0 -> String.format(java.util.Locale.US, "%dm %02ds left", minutes, seconds)
                seconds > 0 -> "${seconds}s left"
                else -> "0s left"
            }
        }
    }

    enum class WidgetSizeBucket {
        SMALL,
        MEDIUM,
        LARGE,
        XLARGE
    }

    fun getWidgetSizeDp(context: Context, options: Bundle?): Pair<Int, Int>? {
        if (options == null) return null
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)

        if (minW <= 0 && maxW <= 0 && minH <= 0 && maxH <= 0) return null

        val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val widthDp = if (isPortrait) (if (minW > 0) minW else maxW) else (if (maxW > 0) maxW else minW)
        val heightDp = if (isPortrait) (if (maxH > 0) maxH else minH) else (if (minH > 0) minH else maxH)
        return Pair(widthDp, heightDp)
    }

    fun getBucketForDimensions(widthDp: Int, heightDp: Int): WidgetSizeBucket {
        val minDim = kotlin.math.min(widthDp, heightDp)
        return when {
            minDim < 115 || heightDp < 110 -> WidgetSizeBucket.SMALL
            minDim < 185 -> WidgetSizeBucket.MEDIUM
            minDim < 260 -> WidgetSizeBucket.LARGE
            else -> WidgetSizeBucket.XLARGE
        }
    }

    fun buildCircleRemoteViews(
        context: Context,
        appWidgetId: Int,
        bucket: WidgetSizeBucket,
        resolvedEvent: CountdownEvent?,
        nowMillis: Long = System.currentTimeMillis()
    ): RemoteViews {
        val circleStyle = getWidgetCircleStyle(context, appWidgetId)
        val accentTheme = resolvedEvent?.theme ?: getWidgetColorTheme(context, appWidgetId)
        val isLiquidGlassEnabled = isWidgetLiquidGlassEnabled(context, appWidgetId)
        val showSeconds = isWidgetShowSecondsEnabled(context, appWidgetId)

        val (layoutRes, sizePx) = when (bucket) {
            WidgetSizeBucket.SMALL -> Pair(R.layout.widget_circle_small, 220)
            WidgetSizeBucket.MEDIUM -> Pair(R.layout.widget_circle, 340)
            WidgetSizeBucket.LARGE -> Pair(R.layout.widget_circle_large, 500)
            WidgetSizeBucket.XLARGE -> Pair(R.layout.widget_circle_xlarge, 680)
        }

        val views = RemoteViews(context.packageName, layoutRes)

        if (resolvedEvent != null) {
            val (progress, remainingDays) = calculateProgressAndDays(resolvedEvent, nowMillis)

            views.setViewVisibility(R.id.widget_circle_card, View.VISIBLE)
            views.setViewVisibility(R.id.widget_circle_empty_view, View.GONE)

            val numberText = when {
                remainingDays <= 0 -> abs(remainingDays).toString()
                else -> remainingDays.toString()
            }

            val labelText = when {
                remainingDays == 0 -> "Days"
                remainingDays == 1 -> "Day"
                remainingDays > 1 -> "Days"
                remainingDays == -1 -> "Day Ago"
                else -> "Days Ago"
            }

            val ringColor = accentTheme.ringColorHex
            val remainingTime = resolvedEvent.calculateRemainingTime(nowMillis)

            val bitmap = renderCircleCountdownBitmap(
                numberText = numberText,
                labelText = labelText,
                progress = progress,
                ringColorHex = ringColor,
                circleStyle = circleStyle,
                isLiquidGlassEnabled = isLiquidGlassEnabled,
                showSeconds = false,
                secondsText = null,
                sizePx = sizePx,
                isMedium = false
            )
            views.setImageViewBitmap(R.id.widget_circle_image, bitmap)

            if (bucket != WidgetSizeBucket.SMALL) {
                views.setViewVisibility(R.id.widget_circle_title, View.VISIBLE)
                views.setTextViewText(R.id.widget_circle_title, resolvedEvent.title)

                if (showSeconds) {
                    views.setViewVisibility(R.id.widget_circle_subtitle, View.VISIBLE)
                    val timeFormatted = formatSecondaryTimer(resolvedEvent, remainingTime, nowMillis)
                    views.setTextViewText(R.id.widget_circle_subtitle, timeFormatted)
                    val ringColorInt = try {
                        Color.parseColor(ringColor)
                    } catch (e: Exception) {
                        Color.parseColor("#20B9E8")
                    }
                    views.setTextColor(R.id.widget_circle_subtitle, ringColorInt)
                } else {
                    views.setViewVisibility(R.id.widget_circle_subtitle, View.GONE)
                }
            } else {
                views.setViewVisibility(R.id.widget_circle_title, View.GONE)
                views.setViewVisibility(R.id.widget_circle_subtitle, View.GONE)
            }

            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_NAVIGATE_TO_EVENT_ID, resolvedEvent.id)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_circle_root, pendingIntent)
        } else {
            views.setViewVisibility(R.id.widget_circle_card, View.GONE)
            views.setViewVisibility(R.id.widget_circle_empty_view, View.VISIBLE)

            val emptyBitmap = renderEmptyCircleCountdownBitmap(
                sizePx = sizePx,
                circleStyle = circleStyle,
                isLiquidGlassEnabled = isLiquidGlassEnabled
            )
            views.setImageViewBitmap(R.id.widget_circle_empty_image, emptyBitmap)

            if (bucket != WidgetSizeBucket.SMALL) {
                views.setViewVisibility(R.id.widget_circle_empty_text, View.VISIBLE)
                views.setTextViewText(R.id.widget_circle_empty_text, "No countdowns yet")
            } else {
                views.setViewVisibility(R.id.widget_circle_empty_text, View.GONE)
            }

            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_circle_root, pendingIntent)
        }

        return views
    }

    /**
     * Updates the standard "Countdown" circular widget synchronously.
     * Supports responsive resizing on Android 12+ (API 31+) via SizeF-mapped RemoteViews
     * and on prior Android versions via onAppWidgetOptionsChanged dimension calculations.
     */
    @JvmOverloads
    fun updateCircleWidgetSync(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        event: CountdownEvent? = null,
        optionsOverride: Bundle? = null
    ) {
        try {
            val resolvedEvent = event ?: getOrResolveEvent(context, appWidgetId)
            val nowMillis = System.currentTimeMillis()

            val views: RemoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val smallViews = buildCircleRemoteViews(context, appWidgetId, WidgetSizeBucket.SMALL, resolvedEvent, nowMillis)
                val mediumViews = buildCircleRemoteViews(context, appWidgetId, WidgetSizeBucket.MEDIUM, resolvedEvent, nowMillis)
                val largeViews = buildCircleRemoteViews(context, appWidgetId, WidgetSizeBucket.LARGE, resolvedEvent, nowMillis)
                val xlargeViews = buildCircleRemoteViews(context, appWidgetId, WidgetSizeBucket.XLARGE, resolvedEvent, nowMillis)

                val sizeMap = mapOf(
                    SizeF(60f, 60f) to smallViews,
                    SizeF(120f, 120f) to mediumViews,
                    SizeF(185f, 185f) to largeViews,
                    SizeF(260f, 260f) to xlargeViews
                )
                RemoteViews(sizeMap)
            } else {
                val options = optionsOverride ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
                val sizeDp = getWidgetSizeDp(context, options)
                val bucket = if (sizeDp != null) {
                    getBucketForDimensions(sizeDp.first, sizeDp.second)
                } else {
                    WidgetSizeBucket.MEDIUM
                }
                buildCircleRemoteViews(context, appWidgetId, bucket, resolvedEvent, nowMillis)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Updates the standard "Countdown" circular widget (2x2) asynchronously
     */
    fun updateCircleWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            updateCircleWidgetSync(context, appWidgetManager, appWidgetId)
        }
    }

    fun buildMediumRemoteViews(
        context: Context,
        appWidgetId: Int,
        bucket: WidgetSizeBucket,
        resolvedEvent: CountdownEvent?,
        nowMillis: Long = System.currentTimeMillis()
    ): RemoteViews {
        val circleStyle = getWidgetCircleStyle(context, appWidgetId)
        val accentTheme = resolvedEvent?.theme ?: getWidgetColorTheme(context, appWidgetId)
        val isLiquidGlassEnabled = isWidgetLiquidGlassEnabled(context, appWidgetId)
        val showSeconds = isWidgetShowSecondsEnabled(context, appWidgetId)

        val (layoutRes, sizePx) = when (bucket) {
            WidgetSizeBucket.SMALL -> Pair(R.layout.widget_medium_small, 220)
            WidgetSizeBucket.MEDIUM -> Pair(R.layout.widget_medium_circle, 360)
            WidgetSizeBucket.LARGE -> Pair(R.layout.widget_medium_large, 520)
            WidgetSizeBucket.XLARGE -> Pair(R.layout.widget_medium_xlarge, 700)
        }

        val views = RemoteViews(context.packageName, layoutRes)

        if (resolvedEvent != null) {
            val (progress, remainingDays) = calculateProgressAndDays(resolvedEvent, nowMillis)

            views.setViewVisibility(R.id.widget_medium_card, View.VISIBLE)
            views.setViewVisibility(R.id.widget_medium_empty_view, View.GONE)

            val numberText = when {
                remainingDays <= 0 -> abs(remainingDays).toString()
                else -> remainingDays.toString()
            }

            val labelText = when {
                remainingDays == 0 -> "Days"
                remainingDays == 1 -> "Day"
                remainingDays > 1 -> "Days"
                remainingDays == -1 -> "Day Ago"
                else -> "Days Ago"
            }

            val ringColor = accentTheme.ringColorHex
            val remainingTime = resolvedEvent.calculateRemainingTime(nowMillis)

            val bitmap = renderCircleCountdownBitmap(
                numberText = numberText,
                labelText = labelText,
                progress = progress,
                ringColorHex = ringColor,
                circleStyle = circleStyle,
                isLiquidGlassEnabled = isLiquidGlassEnabled,
                showSeconds = false,
                secondsText = null,
                sizePx = sizePx,
                isMedium = true
            )
            views.setImageViewBitmap(R.id.widget_medium_image, bitmap)

            if (bucket != WidgetSizeBucket.SMALL) {
                views.setViewVisibility(R.id.widget_medium_title, View.VISIBLE)
                views.setTextViewText(R.id.widget_medium_title, resolvedEvent.title)

                if (showSeconds) {
                    views.setViewVisibility(R.id.widget_medium_subtitle, View.VISIBLE)
                    val timeFormatted = formatSecondaryTimer(resolvedEvent, remainingTime, nowMillis)
                    views.setTextViewText(R.id.widget_medium_subtitle, timeFormatted)
                    val ringColorInt = try {
                        Color.parseColor(ringColor)
                    } catch (e: Exception) {
                        Color.parseColor("#20B9E8")
                    }
                    views.setTextColor(R.id.widget_medium_subtitle, ringColorInt)
                } else {
                    views.setViewVisibility(R.id.widget_medium_subtitle, View.GONE)
                }
            } else {
                views.setViewVisibility(R.id.widget_medium_title, View.GONE)
                views.setViewVisibility(R.id.widget_medium_subtitle, View.GONE)
            }

            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_NAVIGATE_TO_EVENT_ID, resolvedEvent.id)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_medium_root, pendingIntent)
        } else {
            views.setViewVisibility(R.id.widget_medium_card, View.GONE)
            views.setViewVisibility(R.id.widget_medium_empty_view, View.VISIBLE)

            val emptyBitmap = renderEmptyCircleCountdownBitmap(
                sizePx = sizePx,
                circleStyle = circleStyle,
                isLiquidGlassEnabled = isLiquidGlassEnabled
            )
            views.setImageViewBitmap(R.id.widget_medium_empty_image, emptyBitmap)

            if (bucket != WidgetSizeBucket.SMALL) {
                views.setViewVisibility(R.id.widget_medium_empty_text, View.VISIBLE)
                views.setTextViewText(R.id.widget_medium_empty_text, "No countdowns yet")
            } else {
                views.setViewVisibility(R.id.widget_medium_empty_text, View.GONE)
            }

            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_medium_root, pendingIntent)
        }

        return views
    }

    /**
     * Updates the "Countdown (medium)" circular widget (4x2 / 2x2 expanded) synchronously
     */
    @JvmOverloads
    fun updateMediumWidgetSync(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        event: CountdownEvent? = null,
        optionsOverride: Bundle? = null
    ) {
        try {
            val resolvedEvent = event ?: getOrResolveEvent(context, appWidgetId)
            val nowMillis = System.currentTimeMillis()

            val views: RemoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val smallViews = buildMediumRemoteViews(context, appWidgetId, WidgetSizeBucket.SMALL, resolvedEvent, nowMillis)
                val mediumViews = buildMediumRemoteViews(context, appWidgetId, WidgetSizeBucket.MEDIUM, resolvedEvent, nowMillis)
                val largeViews = buildMediumRemoteViews(context, appWidgetId, WidgetSizeBucket.LARGE, resolvedEvent, nowMillis)
                val xlargeViews = buildMediumRemoteViews(context, appWidgetId, WidgetSizeBucket.XLARGE, resolvedEvent, nowMillis)

                val sizeMap = mapOf(
                    SizeF(60f, 60f) to smallViews,
                    SizeF(120f, 120f) to mediumViews,
                    SizeF(185f, 185f) to largeViews,
                    SizeF(260f, 260f) to xlargeViews
                )
                RemoteViews(sizeMap)
            } else {
                val options = optionsOverride ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
                val sizeDp = getWidgetSizeDp(context, options)
                val bucket = if (sizeDp != null) {
                    getBucketForDimensions(sizeDp.first, sizeDp.second)
                } else {
                    WidgetSizeBucket.MEDIUM
                }
                buildMediumRemoteViews(context, appWidgetId, bucket, resolvedEvent, nowMillis)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Updates the "Countdown (medium)" circular widget (4x2 / 2x2 expanded) asynchronously
     */
    fun updateMediumWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            updateMediumWidgetSync(context, appWidgetManager, appWidgetId)
        }
    }

    // Compatibility methods
    fun updateCompactWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        updateCircleWidget(context, appWidgetManager, appWidgetId)
    }

    fun updateLargeWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        updateMediumWidget(context, appWidgetManager, appWidgetId)
    }

    fun getOrResolveEvent(context: Context, appWidgetId: Int): CountdownEvent? {
        val cached = eventCache[appWidgetId]
        if (cached != null) {
            return cached
        }
        val resolved = resolveEventForWidgetSync(context, appWidgetId)
        if (resolved != null) {
            eventCache[appWidgetId] = resolved
        }
        return resolved
    }

    private fun getAssignedEventIds(context: Context, excludeWidgetId: Int): Set<Long> {
        val allIds = getAllInstalledWidgetIds(context)
        val assigned = mutableSetOf<Long>()
        for (id in allIds) {
            if (id != excludeWidgetId) {
                val eventId = getWidgetEventId(context, id)
                if (eventId != EVENT_ID_AUTO && eventId != EVENT_ID_UNASSIGNED && eventId > 0L) {
                    assigned.add(eventId)
                }
            }
        }
        return assigned
    }

    fun resolveEventForWidgetSync(context: Context, appWidgetId: Int): CountdownEvent? {
        return try {
            val dao = getEventDao(context)
            val rawPrefId = getPrefs(context).getLong(PREF_PREFIX_KEY + appWidgetId, EVENT_ID_AUTO)
            val isAuto = isWidgetAutoMode(context, appWidgetId)

            // 1. If widget already has an assigned event, verify it exists
            if (rawPrefId > 0L) {
                val entity = dao?.getEventByIdSync(rawPrefId)
                if (entity != null) {
                    return entity.toDomain()
                } else {
                    // Stale event ID detected! The associated event was deleted from DB.
                    clearWidgetEventId(context, appWidgetId)
                    return null
                }
            }

            // If widget was unassigned because its dedicated event was deleted, do not hijack another event
            if (rawPrefId == EVENT_ID_UNASSIGNED) {
                return null
            }

            // 2. Check if a specific event was pinned recently
            val lastPinned = getPrefs(context).getLong("last_pinned_event_id", -1L)
            if (lastPinned > 0L) {
                val entity = dao?.getEventByIdSync(lastPinned)
                if (entity != null) {
                    setWidgetEventId(context, appWidgetId, lastPinned)
                    getPrefs(context).edit().remove("last_pinned_event_id").apply()
                    val pinnedThemeId = getPrefs(context).getString("last_pinned_theme_id", null)
                    val initialTheme = if (pinnedThemeId != null) {
                        getPrefs(context).edit().remove("last_pinned_theme_id").commit()
                        com.timezy.widget.data.model.EventTheme.fromId(pinnedThemeId)
                    } else {
                        entity.toDomain().theme
                    }
                    setWidgetColorTheme(context, appWidgetId, initialTheme)
                    val pinnedStyle = getPrefs(context).getString("last_pinned_style", null)
                    if (pinnedStyle != null) {
                        setWidgetStyle(context, appWidgetId, com.timezy.widget.data.model.CircleStyle.fromName(pinnedStyle))
                        getPrefs(context).edit().remove("last_pinned_style").commit()
                    }
                    return entity.toDomain()
                } else {
                    getPrefs(context).edit().remove("last_pinned_event_id").apply()
                }
            }

            // 3. Auto mode (or initial unconfigured placement where rawPrefId == EVENT_ID_AUTO):
            val allEntities = dao?.allEventsSync
            if (!allEntities.isNullOrEmpty()) {
                val allEvents = allEntities.map { it.toDomain() }
                val now = System.currentTimeMillis()

                // Find assigned event IDs from other widgets
                val assignedIds = getAssignedEventIds(context, excludeWidgetId = appWidgetId)

                // Try to find an unassigned upcoming event first
                val unassignedUpcoming = allEvents
                    .filter { it.id !in assignedIds }
                    .sortedBy { it.getEffectiveTargetEpochMillis(now) }
                    .firstOrNull { it.getEffectiveTargetEpochMillis(now) >= now }

                // Or any unassigned event
                val unassignedAny = unassignedUpcoming ?: allEvents.firstOrNull { it.id !in assignedIds }

                // Or the closest upcoming event across all events
                val upcoming = unassignedAny ?: (allEvents
                    .sortedBy { it.getEffectiveTargetEpochMillis(now) }
                    .firstOrNull { it.getEffectiveTargetEpochMillis(now) >= now }
                    ?: allEvents.first())

                if (!isAuto) {
                    setWidgetEventId(context, appWidgetId, upcoming.id)
                }

                val pinnedThemeId = getPrefs(context).getString("last_pinned_theme_id", null)
                val initialTheme = if (pinnedThemeId != null) {
                    getPrefs(context).edit().remove("last_pinned_theme_id").commit()
                    com.timezy.widget.data.model.EventTheme.fromId(pinnedThemeId)
                } else {
                    upcoming.theme
                }
                setWidgetColorTheme(context, appWidgetId, initialTheme)
                val pinnedStyle = getPrefs(context).getString("last_pinned_style", null)
                if (pinnedStyle != null) {
                    setWidgetStyle(context, appWidgetId, com.timezy.widget.data.model.CircleStyle.fromName(pinnedStyle))
                    getPrefs(context).edit().remove("last_pinned_style").commit()
                }

                return upcoming
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    data class InstalledWidgetInfo(
        val appWidgetId: Int,
        val title: String,
        val eventId: Long,
        val event: CountdownEvent?,
        val theme: com.timezy.widget.data.model.EventTheme,
        val circleStyle: com.timezy.widget.data.model.CircleStyle,
        val showSeconds: Boolean,
        val isLiquidGlass: Boolean,
        val isMedium: Boolean
    )

    fun getInstalledWidgetsInfo(context: Context): List<InstalledWidgetInfo> {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val circleIds = appWidgetManager.getAppWidgetIds(ComponentName(context, CircleCountdownWidgetProvider::class.java))
        val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MediumCountdownWidgetProvider::class.java))

        val list = mutableListOf<InstalledWidgetInfo>()
        for (id in circleIds) {
            val event = getOrResolveEvent(context, id)
            list.add(
                InstalledWidgetInfo(
                    appWidgetId = id,
                    title = event?.title ?: "Countdown",
                    eventId = getWidgetEventId(context, id),
                    event = event,
                    theme = getWidgetColorTheme(context, id),
                    circleStyle = getWidgetCircleStyle(context, id),
                    showSeconds = isWidgetShowSecondsEnabled(context, id),
                    isLiquidGlass = isWidgetLiquidGlassEnabled(context, id),
                    isMedium = false
                )
            )
        }
        for (id in mediumIds) {
            val event = getOrResolveEvent(context, id)
            list.add(
                InstalledWidgetInfo(
                    appWidgetId = id,
                    title = event?.title ?: "Countdown (medium)",
                    eventId = getWidgetEventId(context, id),
                    event = event,
                    theme = getWidgetColorTheme(context, id),
                    circleStyle = getWidgetCircleStyle(context, id),
                    showSeconds = isWidgetShowSecondsEnabled(context, id),
                    isLiquidGlass = isWidgetLiquidGlassEnabled(context, id),
                    isMedium = true
                )
            )
        }
        return list
    }

    fun updateSpecificWidget(context: Context, appWidgetId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            updateSpecificWidgetSync(context, appWidgetId)
        }
    }

    fun updateSpecificWidgetSync(context: Context, appWidgetId: Int) {
        eventCache.remove(appWidgetId)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val circleIds = appWidgetManager.getAppWidgetIds(ComponentName(context, CircleCountdownWidgetProvider::class.java))
        val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MediumCountdownWidgetProvider::class.java))
        if (circleIds.contains(appWidgetId)) {
            updateCircleWidgetSync(context, appWidgetManager, appWidgetId)
        } else if (mediumIds.contains(appWidgetId)) {
            updateMediumWidgetSync(context, appWidgetManager, appWidgetId)
        } else {
            updateCircleWidgetSync(context, appWidgetManager, appWidgetId)
            updateMediumWidgetSync(context, appWidgetManager, appWidgetId)
        }

        startLiveTicker(context)
        scheduleNextWidgetUpdate(context)
    }

    fun updateWidgetsForEvent(context: Context, eventId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            updateWidgetsForEventSync(context, eventId)
        }
    }

    fun updateWidgetsForEventSync(context: Context, eventId: Long) {
        val appWidgetManager = try { AppWidgetManager.getInstance(context) } catch (_: Exception) { null }
        val circleComponent = ComponentName(context, CircleCountdownWidgetProvider::class.java)
        val circleIds = try { appWidgetManager?.getAppWidgetIds(circleComponent) ?: IntArray(0) } catch (_: Exception) { IntArray(0) }
        val mediumComponent = ComponentName(context, MediumCountdownWidgetProvider::class.java)
        val mediumIds = try { appWidgetManager?.getAppWidgetIds(mediumComponent) ?: IntArray(0) } catch (_: Exception) { IntArray(0) }
        val allIds = (circleIds + mediumIds).distinct()

        // Fetch fresh domain event from database
        val dao = getEventDao(context)
        val updatedEvent = try {
            dao?.getEventByIdSync(eventId)?.toDomain()
        } catch (_: Exception) {
            null
        }

        if (updatedEvent == null) {
            // Event has been deleted: trigger event deletion lifecycle and cleanup
            handleEventDeletedSync(context, eventId)
            return
        }

        // Evict cached event for all widgets assigned to this event
        for (id in allIds) {
            if (getWidgetEventId(context, id) == eventId) {
                eventCache.remove(id)
                getPrefs(context).edit().remove(PREF_COLOR_KEY + id).apply()
            }
        }

        if (appWidgetManager != null) {
            for (id in circleIds) {
                if (getWidgetEventId(context, id) == eventId) {
                    updateCircleWidgetSync(context, appWidgetManager, id, updatedEvent)
                }
            }

            for (id in mediumIds) {
                if (getWidgetEventId(context, id) == eventId) {
                    updateMediumWidgetSync(context, appWidgetManager, id, updatedEvent)
                }
            }
        }
    }

    /**
     * Handles event deletion:
     * - Identifies all widget instances associated with the deleted event ID.
     * - Preserves widgets associated with other valid events.
     * - Attempts to remove obsolete widget instances if supported by the Android launcher.
     * - Respects Android launcher limitations (third-party apps cannot forcibly remove pinned widgets).
     * - Clears stale event ID, theme, and cached state from SharedPreferences and memory.
     * - Synchronously refreshes affected widgets to the fallback empty state with no stale countdown data.
     * - Re-evaluates Auto widgets and AlarmManager schedules.
     */
    fun handleEventDeletedSync(context: Context, deletedEventId: Long): List<WidgetRemovalResult> {
        val removalResults = mutableListOf<WidgetRemovalResult>()
        val allWidgetIds = getAllInstalledWidgetIds(context)
        val appWidgetManager = try { AppWidgetManager.getInstance(context) } catch (_: Exception) { null }
        val circleIds = try {
            appWidgetManager?.getAppWidgetIds(ComponentName(context, CircleCountdownWidgetProvider::class.java))?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
        val mediumIds = try {
            appWidgetManager?.getAppWidgetIds(ComponentName(context, MediumCountdownWidgetProvider::class.java))?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }

        val affectedWidgetIds = mutableListOf<Int>()
        val autoWidgetIds = mutableListOf<Int>()

        for (id in allWidgetIds) {
            val assignedId = getRawWidgetEventId(context, id)
            if (assignedId == deletedEventId) {
                affectedWidgetIds.add(id)
            } else if (assignedId == EVENT_ID_AUTO || isWidgetAutoMode(context, id)) {
                autoWidgetIds.add(id)
            }
        }

        // 1. Process widgets dedicated to the deleted event
        for (id in affectedWidgetIds) {
            val removalResult = removeWidgetInstanceIfSupported(context, id)
            removalResults.add(removalResult)

            // Clear stale state for this widget
            clearWidgetEventId(context, id)
            eventCache.remove(id)

            // If not removed by launcher (unavoidable Android launcher limitation), refresh to clean empty state
            if (!removalResult.isRemoved && appWidgetManager != null) {
                if (circleIds.contains(id)) {
                    updateCircleWidgetSync(context, appWidgetManager, id, event = null)
                } else if (mediumIds.contains(id)) {
                    updateMediumWidgetSync(context, appWidgetManager, id, event = null)
                } else {
                    updateCircleWidgetSync(context, appWidgetManager, id, event = null)
                    updateMediumWidgetSync(context, appWidgetManager, id, event = null)
                }
            }
        }

        // 2. Clear any pending pinned event preferences if they match the deleted event
        val prefs = getPrefs(context)
        if (prefs.getLong("last_pinned_event_id", -1L) == deletedEventId) {
            prefs.edit()
                .remove("last_pinned_event_id")
                .remove("last_pinned_theme_id")
                .remove("last_pinned_style")
                .apply()
        }

        // 3. Update Auto widgets (if any) to re-evaluate against remaining events
        for (id in autoWidgetIds) {
            eventCache.remove(id)
            val newEvent = resolveEventForWidgetSync(context, id)
            if (appWidgetManager != null) {
                if (circleIds.contains(id)) {
                    updateCircleWidgetSync(context, appWidgetManager, id, event = newEvent)
                } else if (mediumIds.contains(id)) {
                    updateMediumWidgetSync(context, appWidgetManager, id, event = newEvent)
                }
            }
        }

        // 4. Update scheduled alarms and live ticker
        scheduleNextWidgetUpdate(context)
        if (!anyWidgetHasActiveCountdownWithSeconds(context)) {
            stopLiveTicker()
        }

        return removalResults
    }

    fun handleEventDeleted(context: Context, deletedEventId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            handleEventDeletedSync(context, deletedEventId)
        }
    }

    /**
     * Handles clearing all events in database:
     * - Cleans stale event IDs and state for all installed widgets.
     * - Resets all widgets to clean empty state without stale countdown data.
     * - Cancels all scheduled alarms and stops live ticker.
     */
    fun handleAllEventsClearedSync(context: Context): List<WidgetRemovalResult> {
        val removalResults = mutableListOf<WidgetRemovalResult>()
        val allWidgetIds = getAllInstalledWidgetIds(context)
        val appWidgetManager = try { AppWidgetManager.getInstance(context) } catch (_: Exception) { null }
        val circleIds = try {
            appWidgetManager?.getAppWidgetIds(ComponentName(context, CircleCountdownWidgetProvider::class.java))?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
        val mediumIds = try {
            appWidgetManager?.getAppWidgetIds(ComponentName(context, MediumCountdownWidgetProvider::class.java))?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }

        for (id in allWidgetIds) {
            val removalResult = removeWidgetInstanceIfSupported(context, id)
            removalResults.add(removalResult)

            clearWidgetEventId(context, id)
            eventCache.remove(id)

            if (!removalResult.isRemoved && appWidgetManager != null) {
                if (circleIds.contains(id)) {
                    updateCircleWidgetSync(context, appWidgetManager, id, event = null)
                } else if (mediumIds.contains(id)) {
                    updateMediumWidgetSync(context, appWidgetManager, id, event = null)
                } else {
                    updateCircleWidgetSync(context, appWidgetManager, id, event = null)
                    updateMediumWidgetSync(context, appWidgetManager, id, event = null)
                }
            }
        }

        val prefs = getPrefs(context)
        prefs.edit()
            .remove("last_pinned_event_id")
            .remove("last_pinned_theme_id")
            .remove("last_pinned_style")
            .apply()

        cancelScheduledUpdates(context)
        stopLiveTicker()

        return removalResults
    }

    fun handleAllEventsCleared(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            handleAllEventsClearedSync(context)
        }
    }

    fun updateAllWidgetsSync(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)

        // Standard "Countdown" widgets
        val circleComponent = ComponentName(context, CircleCountdownWidgetProvider::class.java)
        val circleIds = appWidgetManager.getAppWidgetIds(circleComponent)
        for (id in circleIds) {
            val event = getOrResolveEvent(context, id)
            updateCircleWidgetSync(context, appWidgetManager, id, event)
        }

        // Medium "Countdown (medium)" widgets
        val mediumComponent = ComponentName(context, MediumCountdownWidgetProvider::class.java)
        val mediumIds = appWidgetManager.getAppWidgetIds(mediumComponent)
        for (id in mediumIds) {
            val event = getOrResolveEvent(context, id)
            updateMediumWidgetSync(context, appWidgetManager, id, event)
        }

        scheduleNextWidgetUpdate(context)

        // Ensure live ticker is started if screen is on
        mainHandler?.post {
            startLiveTicker(context)
        }
    }

    @JvmOverloads
    fun updateAllWidgets(context: Context, forceReloadEvents: Boolean = true) {
        if (forceReloadEvents) {
            invalidateEventCache()
        } else {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime < 200L) {
                return
            }
            lastUpdateTime = now
        }

        CoroutineScope(Dispatchers.IO).launch {
            updateAllWidgetsSync(context)
        }
    }

    /**
     * Schedules the next periodic/event update via AlarmManager.
     * Respects Android battery guidelines:
     * - If showSeconds is true and device is interactive (screen on): refreshes every 30s as safety heartbeat.
     * - If device is in sleep/Doze: triggers at next midnight rollover or next event target arrival.
     * - If showSeconds is false: triggers only at next midnight rollover or event completion (no 30s alarms).
     * - If 0 active widgets exist: cancels all alarms.
     */
    fun scheduleNextWidgetUpdate(context: Context) {
        if (!hasActiveWidgets(context)) {
            cancelScheduledUpdates(context)
            return
        }

        val hasAnySeconds = anyWidgetHasSecondsEnabled(context)
        val nowMillis = System.currentTimeMillis()

        val powerManager = try { context.getSystemService(Context.POWER_SERVICE) as? PowerManager } catch (_: Exception) { null }
        val isInteractive = powerManager?.isInteractive ?: true

        // Next midnight in local time zone
        val zone = ZoneId.systemDefault()
        val nextMidnightMillis = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        var triggerAtMillis = nextMidnightMillis

        if (hasAnySeconds && isInteractive) {
            // Safety heartbeat alarm while screen is on
            val next30s = nowMillis + 30_000L
            triggerAtMillis = minOf(triggerAtMillis, next30s)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = getEventDao(context)
                val allEvents = dao?.allEventsSync
                if (allEvents.isNullOrEmpty()) {
                    cancelScheduledUpdates(context)
                    return@launch
                }

                val nextEventTarget = allEvents
                    .map { it.toDomain().getEffectiveTargetEpochMillis(nowMillis) }
                    .filter { it > nowMillis }
                    .minOrNull()

                if (nextEventTarget != null && nextEventTarget < triggerAtMillis) {
                    triggerAtMillis = nextEventTarget
                }

                if (triggerAtMillis <= nowMillis) {
                    triggerAtMillis = nowMillis + 15_000L
                }

                val alarmManager = try { context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager } catch (_: Exception) { null } ?: return@launch
                val intent = Intent(context, CircleCountdownWidgetProvider::class.java).apply {
                    action = ACTION_SCHEDULED_WIDGET_UPDATE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_SCHEDULED_UPDATE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        } else {
                            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                        }
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun cancelScheduledUpdates(context: Context) {
        try {
            val alarmManager = try { context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager } catch (_: Exception) { null } ?: return
            val intent = Intent(context, CircleCountdownWidgetProvider::class.java).apply {
                action = ACTION_SCHEDULED_WIDGET_UPDATE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_SCHEDULED_UPDATE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun checkAndCancelIfNoWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val circleIds = appWidgetManager.getAppWidgetIds(ComponentName(context, CircleCountdownWidgetProvider::class.java))
        val mediumIds = appWidgetManager.getAppWidgetIds(ComponentName(context, MediumCountdownWidgetProvider::class.java))
        if (circleIds.isEmpty() && mediumIds.isEmpty()) {
            cancelScheduledUpdates(context)
        }
    }

    @JvmOverloads
    fun requestPinCircleWidget(
        context: Context,
        eventId: Long,
        theme: com.timezy.widget.data.model.EventTheme? = null,
        style: com.timezy.widget.data.model.CircleStyle? = null
    ): Boolean {
        val resolvedTheme = theme ?: try {
            AppDatabase.getInstance(context).eventDao().getEventByIdSync(eventId)?.toDomain()?.theme
        } catch (_: Exception) {
            null
        }
        return requestPinWidgetInternal(
            context = context,
            eventId = eventId,
            theme = resolvedTheme,
            style = style,
            providerClass = CircleCountdownWidgetProvider::class.java,
            widgetName = "Countdown"
        )
    }

    @JvmOverloads
    fun requestPinMediumWidget(
        context: Context,
        eventId: Long,
        theme: com.timezy.widget.data.model.EventTheme? = null,
        style: com.timezy.widget.data.model.CircleStyle? = null
    ): Boolean {
        val resolvedTheme = theme ?: try {
            AppDatabase.getInstance(context).eventDao().getEventByIdSync(eventId)?.toDomain()?.theme
        } catch (_: Exception) {
            null
        }
        return requestPinWidgetInternal(
            context = context,
            eventId = eventId,
            theme = resolvedTheme,
            style = style,
            providerClass = MediumCountdownWidgetProvider::class.java,
            widgetName = "Countdown (medium)"
        )
    }

    // Compatibility redirects
    fun requestPinCircleGlassWidget(context: Context, eventId: Long): Boolean {
        return requestPinCircleWidget(context, eventId)
    }

    fun requestPinTransparentWidget(context: Context, eventId: Long): Boolean {
        return requestPinMediumWidget(context, eventId)
    }

    fun requestPinCompactWidget(context: Context, eventId: Long): Boolean {
        return requestPinCircleWidget(context, eventId)
    }

    private fun requestPinWidgetInternal(
        context: Context,
        eventId: Long,
        theme: com.timezy.widget.data.model.EventTheme? = null,
        style: com.timezy.widget.data.model.CircleStyle? = null,
        providerClass: Class<*>,
        widgetName: String
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                // Pre-save last_pinned_event_id, theme, and style so new widget resolves them immediately
                val editor = getPrefs(context).edit().putLong("last_pinned_event_id", eventId)
                if (theme != null) {
                    editor.putString("last_pinned_theme_id", theme.id)
                }
                if (style != null) {
                    editor.putString("last_pinned_style", style.name)
                }
                editor.apply()

                val provider = ComponentName(context, providerClass)
                val successIntent = Intent(context, WidgetPinReceiver::class.java).apply {
                    action = WidgetPinReceiver.ACTION_PIN_SUCCESS
                    putExtra(WidgetPinReceiver.EXTRA_PINNED_EVENT_ID, eventId)
                    if (theme != null) {
                        putExtra(WidgetPinReceiver.EXTRA_PINNED_THEME_ID, theme.id)
                    }
                    if (style != null) {
                        putExtra(WidgetPinReceiver.EXTRA_PINNED_STYLE, style.name)
                    }
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val successPendingIntent = PendingIntent.getBroadcast(
                    context,
                    (eventId and 0x7FFFFFFF).toInt(),
                    successIntent,
                    flags
                )

                val success = appWidgetManager.requestPinAppWidget(provider, null, successPendingIntent)
                if (success) {
                    Toast.makeText(context, "Adding $widgetName Widget to Home Screen...", Toast.LENGTH_SHORT).show()
                }
                return success
            }
        }
        Toast.makeText(context, "Home screen widget pinning is not supported by your launcher.", Toast.LENGTH_LONG).show()
        return false
    }
}
