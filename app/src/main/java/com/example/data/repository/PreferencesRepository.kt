package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.AmbientSound
import com.example.data.model.FocusMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("focuslock_prefs", Context.MODE_PRIVATE)

    private val _isProUser = MutableStateFlow(prefs.getBoolean(KEY_IS_PRO, false))
    val isProUser: StateFlow<Boolean> = _isProUser.asStateFlow()

    private val _isStrictMode = MutableStateFlow(prefs.getBoolean(KEY_STRICT_MODE, false))
    val isStrictMode: StateFlow<Boolean> = _isStrictMode.asStateFlow()

    private val _isShortsBlockerEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_SHORTS_BLOCKER, false))
    val isShortsBlockerEnabled: StateFlow<Boolean> = _isShortsBlockerEnabled.asStateFlow()

    private val _isWebBlockerEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_WEB_BLOCKER, false))
    val isWebBlockerEnabled: StateFlow<Boolean> = _isWebBlockerEnabled.asStateFlow()

    private val _selectedAmbient = MutableStateFlow(
        AmbientSound.valueOf(prefs.getString(KEY_AMBIENT_SOUND, AmbientSound.NONE.name) ?: AmbientSound.NONE.name)
    )
    val selectedAmbient: StateFlow<AmbientSound> = _selectedAmbient.asStateFlow()

    private val _currentStreak = MutableStateFlow(prefs.getInt(KEY_STREAK, 0))
    val currentStreak: StateFlow<Int> = _currentStreak.asStateFlow()

    private val _isOnboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
    val isOnboardingDone: StateFlow<Boolean> = _isOnboardingDone.asStateFlow()

    fun setProUser(isPro: Boolean) {
        prefs.edit().putBoolean(KEY_IS_PRO, isPro).apply()
        _isProUser.value = isPro
    }

    fun setStrictMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT_MODE, enabled).apply()
        _isStrictMode.value = enabled
    }

    fun setShortsBlocker(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHORTS_BLOCKER, enabled).apply()
        _isShortsBlockerEnabled.value = enabled
    }

    fun setWebBlocker(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_BLOCKER, enabled).apply()
        _isWebBlockerEnabled.value = enabled
    }

    fun setAmbientSound(sound: AmbientSound) {
        prefs.edit().putString(KEY_AMBIENT_SOUND, sound.name).apply()
        _selectedAmbient.value = sound
    }

    fun setOnboardingDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
        _isOnboardingDone.value = done
    }

    fun saveActiveSession(
        startTimeMillis: Long,
        targetEndTimeMillis: Long,
        mode: String,
        label: String,
        isStrict: Boolean,
        sound: String
    ) {
        prefs.edit()
            .putBoolean(KEY_SESSION_RUNNING, true)
            .putLong(KEY_SESSION_START_TIME, startTimeMillis)
            .putLong(KEY_SESSION_TARGET_END_TIME, targetEndTimeMillis)
            .putString(KEY_SESSION_MODE, mode)
            .putString(KEY_SESSION_LABEL, label)
            .putBoolean(KEY_SESSION_IS_STRICT, isStrict)
            .putString(KEY_SESSION_SOUND, sound)
            .apply()
    }

    fun clearActiveSession() {
        prefs.edit()
            .putBoolean(KEY_SESSION_RUNNING, false)
            .remove(KEY_SESSION_START_TIME)
            .remove(KEY_SESSION_TARGET_END_TIME)
            .remove(KEY_SESSION_MODE)
            .remove(KEY_SESSION_LABEL)
            .remove(KEY_SESSION_IS_STRICT)
            .remove(KEY_SESSION_SOUND)
            .apply()
    }

    fun getActiveSession(): SavedSession? {
        if (!prefs.getBoolean(KEY_SESSION_RUNNING, false)) return null
        val targetEndTime = prefs.getLong(KEY_SESSION_TARGET_END_TIME, 0L)
        if (targetEndTime <= 0L) return null
        return SavedSession(
            startTimeMillis = prefs.getLong(KEY_SESSION_START_TIME, 0L),
            targetEndTimeMillis = targetEndTime,
            mode = prefs.getString(KEY_SESSION_MODE, FocusMode.TIMER.name) ?: FocusMode.TIMER.name,
            label = prefs.getString(KEY_SESSION_LABEL, "Deep Focus") ?: "Deep Focus",
            isStrict = prefs.getBoolean(KEY_SESSION_IS_STRICT, false),
            sound = prefs.getString(KEY_SESSION_SOUND, AmbientSound.NONE.name) ?: AmbientSound.NONE.name
        )
    }

    fun isSessionCurrentlyActive(): Boolean {
        val session = getActiveSession() ?: return false
        return session.targetEndTimeMillis > System.currentTimeMillis()
    }

    fun isStrictSessionCurrentlyActive(): Boolean {
        val session = getActiveSession() ?: return false
        return session.isStrict && session.targetEndTimeMillis > System.currentTimeMillis()
    }

    fun isStrictActive(): Boolean {
        return _isStrictMode.value || isStrictSessionCurrentlyActive()
    }

    fun recordSessionSuccess() {
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString(KEY_LAST_STREAK_DATE, "")

        if (lastDate != todayStr) {
            val newStreak = _currentStreak.value + 1
            prefs.edit()
                .putInt(KEY_STREAK, newStreak)
                .putString(KEY_LAST_STREAK_DATE, todayStr)
                .apply()
            _currentStreak.value = newStreak
        }
    }

    companion object {
        private const val KEY_IS_PRO = "key_is_pro_user"
        private const val KEY_STRICT_MODE = "key_strict_mode"
        private const val KEY_SHORTS_BLOCKER = "key_shorts_blocker"
        private const val KEY_WEB_BLOCKER = "key_web_blocker"
        private const val KEY_AMBIENT_SOUND = "key_ambient_sound"
        private const val KEY_STREAK = "key_current_streak"
        private const val KEY_LAST_STREAK_DATE = "key_last_streak_date"
        private const val KEY_ONBOARDING_DONE = "key_onboarding_done"

        private const val KEY_SESSION_RUNNING = "key_session_running"
        private const val KEY_SESSION_START_TIME = "key_session_start_time"
        private const val KEY_SESSION_TARGET_END_TIME = "key_session_target_end_time"
        private const val KEY_SESSION_MODE = "key_session_mode"
        private const val KEY_SESSION_LABEL = "key_session_label"
        private const val KEY_SESSION_IS_STRICT = "key_session_is_strict"
        private const val KEY_SESSION_SOUND = "key_session_sound"
    }
}

data class SavedSession(
    val startTimeMillis: Long,
    val targetEndTimeMillis: Long,
    val mode: String,
    val label: String,
    val isStrict: Boolean,
    val sound: String
)

