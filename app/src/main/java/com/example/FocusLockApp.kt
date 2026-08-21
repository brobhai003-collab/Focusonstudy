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

    val database: FocusLockDatabase by lazy { FocusLockDatabase.getDatabase(this) }
    val focusRepository: FocusRepository by lazy { FocusRepository(this, database.focusDao()) }
    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(this) }
    val usageStatsRepository: UsageStatsRepository by lazy { UsageStatsRepository(this) }
    val authRepository: AuthRepository by lazy { AuthRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("FocusLockApp", "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            // Eagerly warmup repositories in background
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    focusRepository.ensureDefaultWebsitesSeeded()
                } catch (e: Exception) {
                    android.util.Log.w("FocusLockApp", "Default websites seed skipped: ${e.message}")
                }

                // Check if an active session needs to be resumed on process restart
                try {
                    val saved = preferencesRepository.getActiveSession()
                    if (saved != null && !com.example.service.FocusTimerService.isSessionActive.value) {
                        val now = System.currentTimeMillis()
                        if (saved.targetEndTimeMillis > now) {
                            val remaining = ((saved.targetEndTimeMillis - now) / 1000L).coerceAtLeast(10L)
                            val mode = try { com.example.data.model.FocusMode.valueOf(saved.mode) } catch (e: Exception) { com.example.data.model.FocusMode.TIMER }
                            val sound = try { com.example.data.model.AmbientSound.valueOf(saved.sound) } catch (e: Exception) { com.example.data.model.AmbientSound.NONE }
                            com.example.service.FocusTimerService.start(
                                context = this@FocusLockApp,
                                mode = mode,
                                durationSeconds = remaining,
                                label = saved.label,
                                isStrict = saved.isStrict,
                                sound = sound
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FocusLockApp", "Session auto-resume check error: ${e.message}")
                }
            }

            createNotificationChannels()
        } catch (e: Exception) {
            android.util.Log.e("FocusLockApp", "Error during app init: ${e.message}", e)
        }
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
