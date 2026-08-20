package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.data.local.FocusLockDatabase
import com.example.data.repository.AuthRepository
import com.example.data.repository.FocusRepository
import com.example.data.repository.PreferencesRepository
import com.example.data.repository.UsageStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FocusLockApp : Application() {

    lateinit var database: FocusLockDatabase
        private set

    lateinit var focusRepository: FocusRepository
        private set

    lateinit var preferencesRepository: PreferencesRepository
        private set

    lateinit var usageStatsRepository: UsageStatsRepository
        private set

    lateinit var authRepository: AuthRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = FocusLockDatabase.getDatabase(this)
        focusRepository = FocusRepository(this, database.focusDao())
        preferencesRepository = PreferencesRepository(this)
        usageStatsRepository = UsageStatsRepository(this)
        authRepository = AuthRepository(this)

        CoroutineScope(Dispatchers.IO).launch {
            focusRepository.ensureDefaultWebsitesSeeded()
        }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val focusChannel = NotificationChannel(
                CHANNEL_FOCUS_TIMER,
                getString(R.string.focus_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.focus_notification_desc)
                setShowBadge(false)
            }

            val audioChannel = NotificationChannel(
                CHANNEL_AMBIENT_AUDIO,
                getString(R.string.ambient_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.ambient_notification_desc)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(focusChannel)
            notificationManager.createNotificationChannel(audioChannel)
        }
    }

    companion object {
        const val CHANNEL_FOCUS_TIMER = "focuslock_timer_channel"
        const val CHANNEL_AMBIENT_AUDIO = "focuslock_ambient_channel"

        lateinit var instance: FocusLockApp
            private set
    }
}
