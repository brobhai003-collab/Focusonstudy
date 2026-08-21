package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.FocusLockApp
import com.example.MainActivity
import com.example.R
import com.example.data.model.AmbientSound
import com.example.data.model.FocusMode
import com.example.data.model.FocusSessionEntity
import com.example.data.model.PomodoroPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FocusTimerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null
    private val audioSynth = AmbientAudioSynthesizer()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                val modeStr = intent?.getStringExtra(EXTRA_MODE) ?: FocusMode.TIMER.name
                val mode = FocusMode.valueOf(modeStr)
                val targetDuration = intent?.getLongExtra(EXTRA_DURATION_SECONDS, 25 * 60L) ?: (25 * 60L)
                val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Deep Focus"
                val isStrict = intent?.getBooleanExtra(EXTRA_IS_STRICT, false) ?: false
                val soundName = intent?.getStringExtra(EXTRA_SOUND) ?: AmbientSound.NONE.name
                val sound = AmbientSound.valueOf(soundName)

                startFocusSession(mode, targetDuration, label, isStrict, sound)
            }
            ACTION_PAUSE -> {
                pauseSession()
            }
            ACTION_RESUME -> {
                resumeSession()
            }
            ACTION_STOP -> {
                stopFocusSession(userCancelled = true)
            }
            ACTION_SWITCH_SOUND -> {
                val soundName = intent?.getStringExtra(EXTRA_SOUND) ?: AmbientSound.NONE.name
                val sound = AmbientSound.valueOf(soundName)
                _currentSound.value = sound
                audioSynth.startPlaying(sound)
            }
        }

        return START_STICKY
    }

    private fun startFocusSession(
        mode: FocusMode,
        targetDuration: Long,
        label: String,
        isStrict: Boolean,
        sound: AmbientSound
    ) {
        _isSessionActive.value = true
        _isPaused.value = false
        _currentMode.value = mode
        _targetDurationSeconds.value = targetDuration
        _sessionLabel.value = label
        _isStrictMode.value = isStrict
        _currentSound.value = sound
        _pomodoroPhase.value = PomodoroPhase.WORK

        if (mode == FocusMode.STOPWATCH) {
            _remainingSeconds.value = 0
            _elapsedSeconds.value = 0
        } else {
            _remainingSeconds.value = targetDuration
            _elapsedSeconds.value = 0
        }

        // Persist session to SharedPreferences with epoch timestamps for reboot/restart recovery
        try {
            val startTime = System.currentTimeMillis()
            val targetEndTime = startTime + (targetDuration * 1000L)
            FocusLockApp.instance.preferencesRepository.saveActiveSession(
                startTimeMillis = startTime,
                targetEndTimeMillis = targetEndTime,
                mode = mode.name,
                label = label,
                isStrict = isStrict,
                sound = sound.name
            )
        } catch (e: Exception) {
            // Ignored
        }

        audioSynth.startPlaying(sound)
        startForeground(NOTIFICATION_ID, buildNotification())
        startTimerLoop()
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && _isSessionActive.value) {
                delay(1000)
                if (!_isPaused.value) {
                    when (_currentMode.value) {
                        FocusMode.STOPWATCH -> {
                            _elapsedSeconds.value += 1
                            _remainingSeconds.value += 1
                        }
                        FocusMode.TIMER -> {
                            _elapsedSeconds.value += 1
                            if (_remainingSeconds.value > 0) {
                                _remainingSeconds.value -= 1
                            }
                            if (_remainingSeconds.value <= 0) {
                                onSessionFinished()
                                break
                            }
                        }
                        FocusMode.POMODORO -> {
                            _elapsedSeconds.value += 1
                            if (_remainingSeconds.value > 0) {
                                _remainingSeconds.value -= 1
                            }
                            if (_remainingSeconds.value <= 0) {
                                handlePomodoroTransition()
                            }
                        }
                    }
                    updateNotification()
                }
            }
        }
    }

    private fun handlePomodoroTransition() {
        when (_pomodoroPhase.value) {
            PomodoroPhase.WORK -> {
                _pomodoroCycles.value += 1
                val isLong = _pomodoroCycles.value % 4 == 0
                _pomodoroPhase.value = if (isLong) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK
                _remainingSeconds.value = if (isLong) 15 * 60L else 5 * 60L
            }
            PomodoroPhase.SHORT_BREAK, PomodoroPhase.LONG_BREAK -> {
                _pomodoroPhase.value = PomodoroPhase.WORK
                _remainingSeconds.value = 25 * 60L
            }
        }
    }

    private fun isSessionStrictLocked(): Boolean {
        return _isStrictMode.value || (_isSessionActive.value && _elapsedSeconds.value >= 60)
    }

    private fun pauseSession() {
        if (_isStrictMode.value || isSessionStrictLocked()) return // Strict mode completely blocks pause
        _isPaused.value = true
        updateNotification()
    }

    private fun resumeSession() {
        _isPaused.value = false
        updateNotification()
    }

    private fun onSessionFinished() {
        val duration = _elapsedSeconds.value
        val label = _sessionLabel.value
        val mode = _currentMode.value.name
        val isStrict = _isStrictMode.value

        try {
            FocusLockApp.instance.preferencesRepository.clearActiveSession()
        } catch (e: Exception) {
            // Handled
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                FocusLockApp.instance.focusRepository.recordSession(
                    FocusSessionEntity(
                        label = label,
                        mode = mode,
                        durationSeconds = duration,
                        isStrict = isStrict,
                        isSuccessful = true
                    )
                )
                FocusLockApp.instance.preferencesRepository.recordSessionSuccess()
                
                // Sync progress with Firebase Cloud Profile
                try {
                    val currentStreak = FocusLockApp.instance.preferencesRepository.currentStreak.value
                    val isPro = FocusLockApp.instance.preferencesRepository.isProUser.value
                    FocusLockApp.instance.authRepository.syncStats(
                        streak = currentStreak,
                        totalMinutes = (duration / 60).coerceAtLeast(1),
                        sessions = 1,
                        isPro = isPro
                    )
                } catch (e: Exception) {
                    // Non-blocking sync
                }
            } catch (e: Exception) {
                // Handled gracefully
            }
        }

        stopFocusSession(userCancelled = false)
    }

    private fun stopFocusSession(userCancelled: Boolean) {
        if (userCancelled && (_isStrictMode.value || isSessionStrictLocked())) {
            // Cannot cancel prematurely during strict mode or after grace period
            return
        }

        try {
            FocusLockApp.instance.preferencesRepository.clearActiveSession()
        } catch (e: Exception) {
            // Handled
        }

        if (userCancelled && _elapsedSeconds.value > 60) {
            val duration = _elapsedSeconds.value
            val label = _sessionLabel.value
            val mode = _currentMode.value.name
            val isStrict = _isStrictMode.value
            serviceScope.launch(Dispatchers.IO) {
                FocusLockApp.instance.focusRepository.recordSession(
                    FocusSessionEntity(
                        label = label,
                        mode = mode,
                        durationSeconds = duration,
                        isStrict = isStrict,
                        isSuccessful = false
                    )
                )
            }
        }

        audioSynth.stopPlaying()
        timerJob?.cancel()
        _isSessionActive.value = false
        _isPaused.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val timeFormatted = formatTime(
            if (_currentMode.value == FocusMode.STOPWATCH) _elapsedSeconds.value else _remainingSeconds.value
        )

        val title = when (_currentMode.value) {
            FocusMode.POMODORO -> "Pomodoro: ${_pomodoroPhase.value.name.replace("_", " ")} ($timeFormatted)"
            FocusMode.STOPWATCH -> "Focus Stopwatch: $timeFormatted"
            FocusMode.TIMER -> "Focus Session: $timeFormatted remaining"
        }

        val content = if (_isStrictMode.value) "🔒 Strict Mode Active • Distracting apps blocked" else "🛡️ Focus Shield Active • ${_sessionLabel.value}"

        return NotificationCompat.Builder(this, FocusLockApp.CHANNEL_FOCUS_TIMER)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    override fun onDestroy() {
        audioSynth.stopPlaying()
        timerJob?.cancel()
        _isSessionActive.value = false
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_PAUSE = "com.example.service.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.service.ACTION_RESUME"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_SWITCH_SOUND = "com.example.service.ACTION_SWITCH_SOUND"

        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_DURATION_SECONDS = "extra_duration_seconds"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_IS_STRICT = "extra_is_strict"
        const val EXTRA_SOUND = "extra_sound"

        // Observable State
        private val _isSessionActive = MutableStateFlow(false)
        val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        private val _currentMode = MutableStateFlow(FocusMode.TIMER)
        val currentMode: StateFlow<FocusMode> = _currentMode.asStateFlow()

        private val _pomodoroPhase = MutableStateFlow(PomodoroPhase.WORK)
        val pomodoroPhase: StateFlow<PomodoroPhase> = _pomodoroPhase.asStateFlow()

        private val _remainingSeconds = MutableStateFlow(25 * 60L)
        val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

        private val _elapsedSeconds = MutableStateFlow(0L)
        val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

        private val _targetDurationSeconds = MutableStateFlow(25 * 60L)
        val targetDurationSeconds: StateFlow<Long> = _targetDurationSeconds.asStateFlow()

        private val _pomodoroCycles = MutableStateFlow(0)
        val pomodoroCycles: StateFlow<Int> = _pomodoroCycles.asStateFlow()

        private val _sessionLabel = MutableStateFlow("Deep Focus")
        val sessionLabel: StateFlow<String> = _sessionLabel.asStateFlow()

        private val _isStrictMode = MutableStateFlow(false)
        val isStrictMode: StateFlow<Boolean> = _isStrictMode.asStateFlow()

        private val _currentSound = MutableStateFlow(AmbientSound.NONE)
        val currentSound: StateFlow<AmbientSound> = _currentSound.asStateFlow()

        fun start(
            context: Context,
            mode: FocusMode,
            durationSeconds: Long,
            label: String,
            isStrict: Boolean,
            sound: AmbientSound
        ) {
            val intent = Intent(context, FocusTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_DURATION_SECONDS, durationSeconds)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_IS_STRICT, isStrict)
                putExtra(EXTRA_SOUND, sound.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context, FocusTimerService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, FocusTimerService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FocusTimerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun setSound(context: Context, sound: AmbientSound) {
            val intent = Intent(context, FocusTimerService::class.java).apply {
                action = ACTION_SWITCH_SOUND
                putExtra(EXTRA_SOUND, sound.name)
            }
            context.startService(intent)
        }
    }
}
