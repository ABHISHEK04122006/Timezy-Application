package com.timezy.widget

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.timezy.widget.data.database.EventDao
import com.timezy.widget.data.database.EventEntity
import com.timezy.widget.data.model.CountdownEvent
import com.timezy.widget.data.model.EventCategory
import com.timezy.widget.data.model.EventTheme
import com.timezy.widget.widget.WidgetUpdateHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Unit tests verifying widget lifecycle management and event synchronization
 * when countdown events are deleted, including multi-widget handling, stale state cleanup,
 * and launcher limitation compliance.
 */
class WidgetLifecycleEventDeletionTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeDao: FakeEventDao

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        fakeDao = FakeEventDao()
        WidgetUpdateHelper.prefsOverrideForTesting = fakePrefs
        WidgetUpdateHelper.eventDaoOverrideForTesting = fakeDao
        WidgetUpdateHelper.isWidgetRemovalSupportedForTesting = null
        WidgetUpdateHelper.installedWidgetIdsOverrideForTesting = null
        WidgetUpdateHelper.invalidateEventCache()
    }

    @After
    fun tearDown() {
        WidgetUpdateHelper.prefsOverrideForTesting = null
        WidgetUpdateHelper.eventDaoOverrideForTesting = null
        WidgetUpdateHelper.isWidgetRemovalSupportedForTesting = null
        WidgetUpdateHelper.installedWidgetIdsOverrideForTesting = null
        WidgetUpdateHelper.invalidateEventCache()
    }

    @Test
    fun testEventDeletionClearsDedicatedWidgetStateAndStaleEventId() {
        val widgetId = 101
        val eventId = 100L

        val event = CountdownEvent(
            id = eventId,
            title = "Graduation Day",
            targetDateTimeEpochMillis = System.currentTimeMillis() + 86400000L,
            category = EventCategory.EDUCATION,
            theme = EventTheme.EMERALD_MINT
        )
        fakeDao.insertEvent(EventEntity.fromDomain(event))

        WidgetUpdateHelper.installedWidgetIdsOverrideForTesting = listOf(widgetId)
        WidgetUpdateHelper.setWidgetEventId(null, widgetId, eventId)
        WidgetUpdateHelper.setWidgetColorTheme(null, widgetId, EventTheme.EMERALD_MINT)
        WidgetUpdateHelper.eventCache[widgetId] = event

        assertEquals(eventId, WidgetUpdateHelper.getWidgetEventId(null, widgetId))
        assertTrue(WidgetUpdateHelper.hasWidgetColorTheme(null, widgetId))
        assertNotNull(WidgetUpdateHelper.eventCache[widgetId])

        // Delete event from DB first (as repository does)
        fakeDao.deleteEventById(eventId)

        // Run deletion synchronization
        val results = WidgetUpdateHelper.handleEventDeletedSync(createMockContext(), eventId)

        // 1. Stale event ID must be cleared
        assertEquals(WidgetUpdateHelper.EVENT_ID_AUTO, WidgetUpdateHelper.getWidgetEventId(null, widgetId))
        assertEquals(WidgetUpdateHelper.EVENT_ID_UNASSIGNED, WidgetUpdateHelper.getRawWidgetEventId(null, widgetId))

        // 2. In-memory cache must be evicted
        assertNull(WidgetUpdateHelper.eventCache[widgetId])

        // 3. Stale theme inherited from event must be cleared
        assertFalse(WidgetUpdateHelper.hasWidgetColorTheme(null, widgetId))

        // 4. Result must report launcher limitation accurately without false claim of removal
        assertEquals(1, results.size)
        val result = results[0]
        assertEquals(widgetId, result.appWidgetId)
        assertFalse(result.isRemoved)
        assertNotNull(result.launcherLimitation)
        assertTrue(result.launcherLimitation!!.contains("launcher limitation"))
    }

    @Test
    fun testMultipleWidgetsPreservesOtherValidEvents() {
        val widgetA = 201
        val widgetB = 202
        val eventAId = 10L
        val eventBId = 20L

        val eventA = CountdownEvent(
            id = eventAId,
            title = "Project Deadline",
            targetDateTimeEpochMillis = System.currentTimeMillis() + 86400000L,
            category = EventCategory.CAREER,
            theme = EventTheme.NEON_CYAN
        )
        val eventB = CountdownEvent(
            id = eventBId,
            title = "Flight to Tokyo",
            targetDateTimeEpochMillis = System.currentTimeMillis() + 500000000L,
            category = EventCategory.TRAVEL,
            theme = EventTheme.SUNSET_EMBER
        )
        fakeDao.insertEvent(EventEntity.fromDomain(eventA))
        fakeDao.insertEvent(EventEntity.fromDomain(eventB))

        WidgetUpdateHelper.installedWidgetIdsOverrideForTesting = listOf(widgetA, widgetB)

        // Configure Widget A for Event A
        WidgetUpdateHelper.setWidgetEventId(null, widgetA, eventAId)
        WidgetUpdateHelper.setWidgetColorTheme(null, widgetA, EventTheme.NEON_CYAN)
        WidgetUpdateHelper.eventCache[widgetA] = eventA

        // Configure Widget B for Event B
        WidgetUpdateHelper.setWidgetEventId(null, widgetB, eventBId)
        WidgetUpdateHelper.setWidgetColorTheme(null, widgetB, EventTheme.SUNSET_EMBER)
        WidgetUpdateHelper.eventCache[widgetB] = eventB

        // Delete Event A from DB
        fakeDao.deleteEventById(eventAId)

        // Delete Event A synchronization
        val results = WidgetUpdateHelper.handleEventDeletedSync(createMockContext(), eventAId)

        // Only Widget A should be affected
        assertEquals(1, results.size)
        assertEquals(widgetA, results[0].appWidgetId)
        assertEquals(WidgetUpdateHelper.EVENT_ID_AUTO, WidgetUpdateHelper.getWidgetEventId(null, widgetA))
        assertEquals(WidgetUpdateHelper.EVENT_ID_UNASSIGNED, WidgetUpdateHelper.getRawWidgetEventId(null, widgetA))

        // Widget B must remain completely intact and unaffected
        assertEquals(eventBId, WidgetUpdateHelper.getWidgetEventId(null, widgetB))
        assertEquals(EventTheme.SUNSET_EMBER, WidgetUpdateHelper.getWidgetColorTheme(null, widgetB))
        assertNotNull(WidgetUpdateHelper.eventCache[widgetB])
        assertEquals("Flight to Tokyo", WidgetUpdateHelper.eventCache[widgetB]?.title)
    }

    @Test
    fun testDedicatedWidgetDoesNotHijackRemainingEvents() {
        val widgetA = 301
        val eventAId = 111L
        val eventBId = 222L

        val eventA = CountdownEvent(
            id = eventAId,
            title = "Old Concert",
            targetDateTimeEpochMillis = System.currentTimeMillis() + 86400000L,
            category = EventCategory.OTHER,
            theme = EventTheme.EMERALD_MINT
        )
        val eventB = CountdownEvent(
            id = eventBId,
            title = "Remaining Anniversary",
            targetDateTimeEpochMillis = System.currentTimeMillis() + 172800000L,
            category = EventCategory.OTHER,
            theme = EventTheme.ROSE_VELVET
        )
        fakeDao.insertEvent(EventEntity.fromDomain(eventA))
        fakeDao.insertEvent(EventEntity.fromDomain(eventB))

        WidgetUpdateHelper.installedWidgetIdsOverrideForTesting = listOf(widgetA)
        WidgetUpdateHelper.setWidgetEventId(null, widgetA, eventAId)

        // Delete Event A
        fakeDao.deleteEventById(eventAId)
        WidgetUpdateHelper.handleEventDeletedSync(createMockContext(), eventAId)

        // Widget A is now in unassigned state
        assertEquals(WidgetUpdateHelper.EVENT_ID_UNASSIGNED, WidgetUpdateHelper.getRawWidgetEventId(null, widgetA))

        // When resolving, it must return null rather than hijacking other events in the DB
        val resolved = WidgetUpdateHelper.resolveEventForWidgetSync(createMockContext(), widgetA)
        assertNull(resolved)
    }

    @Test
    fun testLauncherLimitationReportedAccuratelyWithoutFalseSuccess() {
        val widgetId = 401
        WidgetUpdateHelper.isWidgetRemovalSupportedForTesting = false

        val result = WidgetUpdateHelper.removeWidgetInstanceIfSupported(createMockContext(), widgetId)

        // Must respect Android launcher limitations and not falsely report success
        assertFalse(result.isRemoved)
        assertNotNull(result.launcherLimitation)
        assertTrue(result.launcherLimitation!!.contains("Third-party apps cannot forcibly remove pinned widgets"))
    }

    @Test
    fun testWidgetRemovalExecutedWhenSupportedByLauncher() {
        val widgetId = 402
        WidgetUpdateHelper.isWidgetRemovalSupportedForTesting = true
        WidgetUpdateHelper.setWidgetEventId(null, widgetId, 555L)

        val result = WidgetUpdateHelper.removeWidgetInstanceIfSupported(createMockContext(), widgetId)

        // When supported, reports successful removal and cleans all widget preferences
        assertTrue(result.isRemoved)
        assertNull(result.launcherLimitation)
        assertEquals(WidgetUpdateHelper.EVENT_ID_AUTO, WidgetUpdateHelper.getWidgetEventId(null, widgetId))
    }

    @Test
    fun testClearAllEventsCleansAllWidgets() {
        val widget1 = 501
        val widget2 = 502
        WidgetUpdateHelper.installedWidgetIdsOverrideForTesting = listOf(widget1, widget2)

        WidgetUpdateHelper.setWidgetEventId(null, widget1, 10L)
        WidgetUpdateHelper.setWidgetEventId(null, widget2, 20L)
        WidgetUpdateHelper.setWidgetColorTheme(null, widget1, EventTheme.COSMIC_PURPLE)
        WidgetUpdateHelper.setWidgetColorTheme(null, widget2, EventTheme.ROSE_VELVET)

        val results = WidgetUpdateHelper.handleAllEventsClearedSync(createMockContext())

        assertEquals(2, results.size)
        assertEquals(WidgetUpdateHelper.EVENT_ID_AUTO, WidgetUpdateHelper.getWidgetEventId(null, widget1))
        assertEquals(WidgetUpdateHelper.EVENT_ID_AUTO, WidgetUpdateHelper.getWidgetEventId(null, widget2))
        assertFalse(WidgetUpdateHelper.hasWidgetColorTheme(null, widget1))
        assertFalse(WidgetUpdateHelper.hasWidgetColorTheme(null, widget2))
        assertTrue(WidgetUpdateHelper.eventCache.isEmpty())
    }

    @Test
    fun testAutonomousDetectionOfDeletedEventDuringResolve() {
        val widgetId = 601
        val deletedEventId = 999L

        WidgetUpdateHelper.setWidgetEventId(null, widgetId, deletedEventId)
        assertEquals(deletedEventId, WidgetUpdateHelper.getRawWidgetEventId(null, widgetId))

        // When resolving for a widget whose event was deleted from DB (fakeDao has no 999L)
        val resolved = WidgetUpdateHelper.resolveEventForWidgetSync(createMockContext(), widgetId)

        // Autonomous detection must clear stale event ID and return null
        assertNull(resolved)
        assertEquals(WidgetUpdateHelper.EVENT_ID_UNASSIGNED, WidgetUpdateHelper.getRawWidgetEventId(null, widgetId))
    }

    @Test
    fun testPendingPinnedPreferencesClearedOnDeletion() {
        val deletedEventId = 888L
        fakePrefs.edit()
            .putLong("last_pinned_event_id", deletedEventId)
            .putString("last_pinned_theme_id", EventTheme.SUNSET_EMBER.id)
            .putString("last_pinned_style", "LIQUID_GLASS")
            .apply()

        assertTrue(fakePrefs.contains("last_pinned_event_id"))

        WidgetUpdateHelper.handleEventDeletedSync(createMockContext(), deletedEventId)

        assertFalse(fakePrefs.contains("last_pinned_event_id"))
        assertFalse(fakePrefs.contains("last_pinned_theme_id"))
        assertFalse(fakePrefs.contains("last_pinned_style"))
    }

    @Test
    fun testManualWidgetPlacementPreserved_EventCreationDoesNotPinWidget() {
        val now = Instant.parse("2026-10-23T12:00:00Z").toEpochMilli()
        val newEvent = CountdownEvent(
            id = 777L,
            title = "New Concert",
            targetDateTimeEpochMillis = now + 86400000L,
            category = EventCategory.OTHER,
            theme = EventTheme.NEON_CYAN
        )

        // In Timezy, creating an event only writes to repository and updates active widgets;
        // it must never automatically call requestPinAppWidget or create widgets on launcher.
        assertFalse(fakePrefs.contains("last_pinned_event_id"))
        assertEquals(0, fakePrefs.map.filterKeys { it.startsWith("appwidget_event_") }.size)
    }

    private fun createMockContext(): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
                return fakePrefs
            }
            override fun getApplicationContext(): Context {
                return this
            }
            override fun getSystemService(name: String): Any? {
                return null
            }
        }
    }
}

class FakeEventDao(val eventsMap: MutableMap<Long, EventEntity> = mutableMapOf()) : EventDao {
    override fun getAllEventsLiveData(): LiveData<MutableList<EventEntity>> {
        val ld = MutableLiveData<MutableList<EventEntity>>()
        ld.value = eventsMap.values.toMutableList()
        return ld
    }

    override fun getAllEventsSync(): MutableList<EventEntity> {
        return eventsMap.values.toMutableList()
    }

    override fun getEventByIdLiveData(id: Long): LiveData<EventEntity> {
        val ld = MutableLiveData<EventEntity>()
        ld.value = eventsMap[id]
        return ld
    }

    override fun getEventByIdSync(id: Long): EventEntity? {
        return eventsMap[id]
    }

    override fun insertEvent(event: EventEntity): Long {
        val id = if (event.id > 0) event.id else (eventsMap.keys.maxOrNull() ?: 0L) + 1L
        event.id = id
        eventsMap[id] = event
        return id
    }

    override fun updateEvent(event: EventEntity) {
        eventsMap[event.id] = event
    }

    override fun deleteEvent(event: EventEntity) {
        eventsMap.remove(event.id)
    }

    override fun deleteEventById(id: Long): Int {
        return if (eventsMap.remove(id) != null) 1 else 0
    }

    override fun updateFavorite(id: Long, isFavorite: Boolean): Int {
        eventsMap[id]?.let { it.isFavorite = isFavorite }
        return 1
    }

    override fun clearAllEvents(): Int {
        val count = eventsMap.size
        eventsMap.clear()
        return count
    }
}
