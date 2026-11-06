package com.timezy.widget

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.timezy.widget.data.database.AppDatabase
import com.timezy.widget.data.preferences.AppPreferences
import com.timezy.widget.data.repository.EventRepository
import com.timezy.widget.data.repository.EventRepositoryImpl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.timezy.widget.widget.WidgetUpdateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CountdownApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val repository: EventRepository by lazy {
        EventRepositoryImpl(database.eventDao(), this)
    }

    val preferences: AppPreferences by lazy {
        AppPreferences(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        WidgetUpdateHelper.init(this)
        createNotificationChannels()
        registerScreenStateReceiver()
        WidgetUpdateHelper.updateAllWidgets(this)
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> {
                        WidgetUpdateHelper.updateAllWidgets(context)
                        WidgetUpdateHelper.startLiveTicker(context)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        WidgetUpdateHelper.stopLiveTicker()
                        WidgetUpdateHelper.scheduleNextWidgetUpdate(context)
                    }
                }
            }
        }
        registerReceiver(receiver, filter)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_REMINDERS_ID,
                "Event Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications and countdown alerts for your upcoming events"
                enableVibration(true)
                setShowBadge(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_REMINDERS_ID = "countdown_event_reminders"

        lateinit var instance: CountdownApplication
            private set
    }
}
