package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class AuthRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("focuslock_user_account", Context.MODE_PRIVATE)

    private val _userProfile = MutableStateFlow<UserProfile?>(loadSavedProfile())
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private val _currentUser = MutableStateFlow<UserProfile?>(_userProfile.value)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val repoScope = CoroutineScope(Dispatchers.IO)

    private fun loadSavedProfile(): UserProfile? {
        val uid = prefs.getString("user_uid", null) ?: return null
        val email = prefs.getString("user_email", "") ?: ""
        val displayName = prefs.getString("user_name", "Focus Warrior") ?: "Focus Warrior"
        val streak = prefs.getInt("user_streak", 1)
        val totalMinutes = prefs.getLong("user_total_minutes", 0L)
        val sessions = prefs.getInt("user_sessions", 0)
        val isPro = prefs.getBoolean("user_is_pro", false)
        val lastSync = prefs.getLong("user_last_sync", System.currentTimeMillis())

        return UserProfile(
            uid = uid,
            email = email,
            displayName = displayName,
            streak = streak,
            totalFocusMinutes = totalMinutes,
            sessionsCompleted = sessions,
            isPro = isPro,
            lastSyncTimestamp = lastSync
        )
    }

    suspend fun signInWithEmail(email: String, pass: String): Result<UserProfile> {
        _authError.value = null
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            val err = "Please enter a valid email address."
            _authError.value = err
            return Result.failure(Exception(err))
        }
        if (pass.trim().length < 6) {
            val err = "Password must be at least 6 characters."
            _authError.value = err
            return Result.failure(Exception(err))
        }

        _isSyncing.value = true
        delay(600) // Realistic smooth auth transition
        _isSyncing.value = false

        val uid = UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString()
        val name = cleanEmail.substringBefore("@").replace(".", " ").capitalizeWords()

        val profile = UserProfile(
            uid = uid,
            email = cleanEmail,
            displayName = name,
            streak = prefs.getInt("user_streak", 1),
            totalFocusMinutes = prefs.getLong("user_total_minutes", 0L),
            sessionsCompleted = prefs.getInt("user_sessions", 0),
            isPro = prefs.getBoolean("user_is_pro", false),
            lastSyncTimestamp = System.currentTimeMillis()
        )

        saveProfile(profile)
        return Result.success(profile)
    }

    suspend fun signUpWithEmail(email: String, pass: String, name: String): Result<UserProfile> {
        _authError.value = null
        val cleanEmail = email.trim()
        val cleanName = name.trim().ifEmpty { cleanEmail.substringBefore("@").replace(".", " ").capitalizeWords() }

        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            val err = "Please enter a valid email address."
            _authError.value = err
            return Result.failure(Exception(err))
        }
        if (pass.trim().length < 6) {
            val err = "Password must be at least 6 characters."
            _authError.value = err
            return Result.failure(Exception(err))
        }

        _isSyncing.value = true
        delay(600)
        _isSyncing.value = false

        val uid = UUID.randomUUID().toString()
        val profile = UserProfile(
            uid = uid,
            email = cleanEmail,
            displayName = cleanName,
            streak = 1,
            totalFocusMinutes = 0L,
            sessionsCompleted = 0,
            isPro = false,
            lastSyncTimestamp = System.currentTimeMillis()
        )

        saveProfile(profile)
        return Result.success(profile)
    }

    suspend fun signInWithGoogleIdToken(idToken: String): Result<UserProfile> {
        _authError.value = null
        _isSyncing.value = true
        delay(500)
        _isSyncing.value = false

        val uid = UUID.randomUUID().toString()
        val profile = UserProfile(
            uid = uid,
            email = "warrior@google.com",
            displayName = "Dedication Master",
            streak = prefs.getInt("user_streak", 1),
            totalFocusMinutes = prefs.getLong("user_total_minutes", 0L),
            sessionsCompleted = prefs.getInt("user_sessions", 0),
            isPro = prefs.getBoolean("user_is_pro", false),
            lastSyncTimestamp = System.currentTimeMillis()
        )

        saveProfile(profile)
        return Result.success(profile)
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _currentUser.value = null
        _userProfile.value = null
    }

    private fun saveProfile(profile: UserProfile) {
        prefs.edit()
            .putString("user_uid", profile.uid)
            .putString("user_email", profile.email)
            .putString("user_name", profile.displayName)
            .putInt("user_streak", profile.streak)
            .putLong("user_total_minutes", profile.totalFocusMinutes)
            .putInt("user_sessions", profile.sessionsCompleted)
            .putBoolean("user_is_pro", profile.isPro)
            .putLong("user_last_sync", profile.lastSyncTimestamp)
            .apply()

        _currentUser.value = profile
        _userProfile.value = profile
    }

    suspend fun syncStats(streak: Int, totalMinutes: Long, sessions: Int, isPro: Boolean) {
        val current = _currentUser.value ?: return
        val updated = current.copy(
            streak = maxOf(streak, current.streak),
            totalFocusMinutes = maxOf(totalMinutes, current.totalFocusMinutes),
            sessionsCompleted = maxOf(sessions, current.sessionsCompleted),
            isPro = isPro || current.isPro,
            lastSyncTimestamp = System.currentTimeMillis()
        )
        saveProfile(updated)
    }

    fun clearError() {
        _authError.value = null
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
