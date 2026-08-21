package com.example.ui.viewmodel

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.FocusLockApp
import com.example.data.model.AmbientSound
import com.example.data.model.BlockedAppEntity
import com.example.data.model.BlockedWebsiteEntity
import com.example.data.model.FocusMode
import com.example.data.model.FocusScheduleEntity
import com.example.data.model.FocusSessionEntity
import com.example.data.model.InstalledApp
import com.example.data.model.MascotState
import com.example.data.model.PomodoroPhase
import com.example.data.model.TaskUnlockEntity
import com.example.service.FocusDeviceAdminReceiver
import com.example.service.FocusTimerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FocusViewModel(application: Application) : AndroidViewModel(application) {

    private val app = (application as? FocusLockApp) ?: FocusLockApp.instance
    private val focusRepo = app.focusRepository
    private val prefsRepo = app.preferencesRepository
    private val usageRepo = app.usageStatsRepository
    private val authRepo = app.authRepository

    // --- Firebase Auth & Profile State ---
    val currentUser = authRepo.currentUser
    val userProfile = authRepo.userProfile
    val authError = authRepo.authError
    val isSyncing = authRepo.isSyncing

    // --- State from Service & Prefs ---
    val isSessionActive: StateFlow<Boolean> = FocusTimerService.isSessionActive
    val isPaused: StateFlow<Boolean> = FocusTimerService.isPaused
    val currentMode: StateFlow<FocusMode> = FocusTimerService.currentMode
    val pomodoroPhase: StateFlow<PomodoroPhase> = FocusTimerService.pomodoroPhase
    val remainingSeconds: StateFlow<Long> = FocusTimerService.remainingSeconds
    val elapsedSeconds: StateFlow<Long> = FocusTimerService.elapsedSeconds
    val targetDurationSeconds: StateFlow<Long> = FocusTimerService.targetDurationSeconds
    val pomodoroCycles: StateFlow<Int> = FocusTimerService.pomodoroCycles
    val sessionLabel: StateFlow<String> = FocusTimerService.sessionLabel

    val isProUser: StateFlow<Boolean> = prefsRepo.isProUser
    val isStrictMode: StateFlow<Boolean> = prefsRepo.isStrictMode
    val isShortsBlockerEnabled: StateFlow<Boolean> = prefsRepo.isShortsBlockerEnabled
    val isWebBlockerEnabled: StateFlow<Boolean> = prefsRepo.isWebBlockerEnabled

    // Strict Lock is ONLY active when Strict Mode is explicitly enabled by the user
    val isSessionStrictLocked: StateFlow<Boolean> = combine(
        isSessionActive,
        isStrictMode
    ) { active, strict ->
        active && strict
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Complete modification lock during active session ONLY when strict mode is armed
    val isModificationLocked: StateFlow<Boolean> = combine(
        isSessionActive,
        isStrictMode
    ) { active, strict ->
        active && strict
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // In normal mode, user has no auto-lock countdown and can stop anytime
    val graceSecondsRemaining: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
    val selectedAmbient: StateFlow<AmbientSound> = prefsRepo.selectedAmbient
    val currentStreak: StateFlow<Int> = prefsRepo.currentStreak
    val isOnboardingDone: StateFlow<Boolean> = prefsRepo.isOnboardingDone

    // --- Configurable Timer Options ---
    private val _selectedDurationMinutes = MutableStateFlow(25)
    val selectedDurationMinutes: StateFlow<Int> = _selectedDurationMinutes.asStateFlow()

    private val _selectedTimerMode = MutableStateFlow(FocusMode.TIMER)
    val selectedTimerMode: StateFlow<FocusMode> = _selectedTimerMode.asStateFlow()

    private val _customSessionName = MutableStateFlow("Deep Focus")
    val customSessionName: StateFlow<String> = _customSessionName.asStateFlow()

    // --- Data from Room ---
    val blockedAppsList: StateFlow<List<BlockedAppEntity>> = focusRepo.blockedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessionsHistory: StateFlow<List<FocusSessionEntity>> = focusRepo.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val schedules: StateFlow<List<FocusScheduleEntity>> = focusRepo.allSchedules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeTaskUnlock: StateFlow<TaskUnlockEntity?> = focusRepo.activeTaskUnlock
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val blockedWebsites: StateFlow<List<BlockedWebsiteEntity>> = focusRepo.blockedWebsites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Installed Apps ---
    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _appSearchQuery = MutableStateFlow("")
    val appSearchQuery: StateFlow<String> = _appSearchQuery.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    val filteredInstalledApps: StateFlow<List<InstalledApp>> = combine(installedApps, appSearchQuery) { apps, query ->
        if (query.isBlank()) apps
        else apps.filter { it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Permissions Reactive State ---
    private val _hasAccessibility = MutableStateFlow(false)
    val hasAccessibility: StateFlow<Boolean> = _hasAccessibility.asStateFlow()

    private val _hasUsageStats = MutableStateFlow(false)
    val hasUsageStats: StateFlow<Boolean> = _hasUsageStats.asStateFlow()

    private val _hasOverlay = MutableStateFlow(false)
    val hasOverlay: StateFlow<Boolean> = _hasOverlay.asStateFlow()

    private val _hasDeviceAdmin = MutableStateFlow(false)
    val hasDeviceAdmin: StateFlow<Boolean> = _hasDeviceAdmin.asStateFlow()

    val hasAllRequiredPermissions: StateFlow<Boolean> = combine(_hasAccessibility, _hasUsageStats, _hasOverlay) { a, u, o ->
        a && u && o
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        loadInstalledApps()
        refreshPermissions()
    }

    fun loadInstalledApps(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (_installedApps.value.isEmpty()) {
                _isLoadingApps.value = true
            }
            try {
                _installedApps.value = focusRepo.getInstalledApps(forceRefresh)
            } catch (e: Exception) {
                // Handled
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun setAppSearchQuery(query: String) {
        _appSearchQuery.value = query
    }

    fun setSelectedDuration(minutes: Int) {
        _selectedDurationMinutes.value = minutes
    }

    fun setSelectedMode(mode: FocusMode) {
        if (mode == FocusMode.POMODORO && !prefsRepo.isProUser.value) {
            _selectedTimerMode.value = FocusMode.TIMER
            return
        }
        _selectedTimerMode.value = mode
    }

    fun setSessionName(name: String) {
        _customSessionName.value = name
    }

    // --- Focus Actions ---
    fun startFocusSession() {
        val isPro = prefsRepo.isProUser.value
        val effectiveMode = if (_selectedTimerMode.value == FocusMode.POMODORO && !isPro) FocusMode.TIMER else _selectedTimerMode.value
        val effectiveStrict = if (isStrictMode.value && !isPro) false else isStrictMode.value
        val effectiveSound = if ((selectedAmbient.value == AmbientSound.LOFI_BEATS || selectedAmbient.value == AmbientSound.DEEP_SPACE) && !isPro) AmbientSound.NONE else selectedAmbient.value
        val durationSeconds = _selectedDurationMinutes.value * 60L
        FocusTimerService.start(
            context = getApplication(),
            mode = effectiveMode,
            durationSeconds = durationSeconds,
            label = _customSessionName.value.ifBlank { "Deep Focus" },
            isStrict = effectiveStrict,
            sound = effectiveSound
        )
    }

    fun pauseFocusSession() {
        if (isSessionActive.value && isStrictMode.value) {
            return // Strict mode completely forbids pausing
        }
        FocusTimerService.pause(getApplication())
    }

    fun resumeFocusSession() {
        FocusTimerService.resume(getApplication())
    }

    fun stopFocusSession() {
        if (isSessionActive.value && isStrictMode.value) {
            return // Strict mode completely forbids stopping prematurely
        }
        FocusTimerService.stop(getApplication())
    }

    fun setAmbientSound(sound: AmbientSound) {
        prefsRepo.setAmbientSound(sound)
        if (isSessionActive.value) {
            FocusTimerService.setSound(getApplication(), sound)
        }
    }

    fun toggleAppBlock(packageName: String, appName: String, currentlyBlocked: Boolean) {
        if (isModificationLocked.value) {
            return // Blocked app modifications are forbidden during strict active session
        }
        val newBlocked = !currentlyBlocked
        // Instant Optimistic In-Memory State Update (0ms delay)
        _installedApps.value = _installedApps.value.map { app ->
            if (app.packageName == packageName) {
                app.copy(
                    isBlocked = newBlocked,
                    isWhitelisted = if (newBlocked) false else app.isWhitelisted
                )
            } else app
        }
        // Async background Room DB persistence
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            focusRepo.setAppBlocked(packageName, appName, newBlocked)
        }
    }

    fun toggleAppWhitelist(packageName: String, appName: String, currentlyWhitelisted: Boolean) {
        if (isModificationLocked.value) {
            return // Whitelist modifications are forbidden during strict active session
        }
        val newWhitelisted = !currentlyWhitelisted
        // Instant Optimistic In-Memory State Update (0ms delay)
        _installedApps.value = _installedApps.value.map { app ->
            if (app.packageName == packageName) {
                app.copy(
                    isWhitelisted = newWhitelisted,
                    isBlocked = if (newWhitelisted) false else app.isBlocked
                )
            } else app
        }
        // Async background Room DB persistence
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            focusRepo.setAppWhitelisted(packageName, appName, newWhitelisted)
        }
    }

    fun toggleAppShortsOnly(packageName: String, appName: String, currentShortsOnly: Boolean) {
        if (isModificationLocked.value) {
            return // Modifications forbidden during strict active session
        }
        val newShorts = !currentShortsOnly
        // Instant Optimistic In-Memory State Update (0ms delay)
        _installedApps.value = _installedApps.value.map { app ->
            if (app.packageName == packageName) {
                app.copy(blockShortsOnly = newShorts)
            } else app
        }
        // Async background Room DB persistence
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            focusRepo.setAppShortsBlockOnly(packageName, appName, newShorts)
        }
    }

    fun toggleStrictMode(enabled: Boolean, context: Context? = null, onAdminRequired: (() -> Unit)? = null) {
        if (isSessionActive.value) {
            // FORBIDDEN: Cannot toggle Strict Mode during an active session!
            return
        }
        if (enabled && !prefsRepo.isProUser.value) {
            prefsRepo.setStrictMode(false)
            return
        }
        if (enabled) {
            if (!isDeviceAdminActive()) {
                // Device Admin is required before Strict Mode can be armed
                if (context != null) {
                    requestDeviceAdminPermission(context)
                }
                onAdminRequired?.invoke()
                prefsRepo.setStrictMode(false)
                return
            }
            prefsRepo.setStrictMode(true)
        } else {
            prefsRepo.setStrictMode(false)
        }
    }

    fun requestDeviceAdminPermission(context: Context) {
        val compName = ComponentName(context, FocusDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Dedication uses Device Administrator to prevent unauthorized uninstallation or bypassing while Strict Mode focus is active."
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(settingsIntent)
            }
        }
    }

    fun toggleShortsBlocker(enabled: Boolean) {
        if (isModificationLocked.value) return
        prefsRepo.setShortsBlocker(enabled)
    }

    fun toggleWebBlocker(enabled: Boolean) {
        if (isModificationLocked.value) return
        prefsRepo.setWebBlocker(enabled)
    }

    fun setProUser(isPro: Boolean) {
        prefsRepo.setProUser(isPro)
    }

    fun setOnboardingDone(done: Boolean) {
        prefsRepo.setOnboardingDone(done)
    }

    // Schedules
    fun addSchedule(label: String, startHour: Int, startMin: Int, endHour: Int, endMin: Int, days: String) {
        if (isModificationLocked.value) return
        viewModelScope.launch {
            if (!prefsRepo.isProUser.value) {
                if (schedules.value.isNotEmpty()) {
                    return@launch
                }
            }
            focusRepo.addSchedule(
                FocusScheduleEntity(
                    label = label,
                    startHour = startHour,
                    startMinute = startMin,
                    endHour = endHour,
                    endMinute = endMin,
                    daysOfWeek = days,
                    isEnabled = true
                )
            )
        }
    }

    fun toggleSchedule(schedule: FocusScheduleEntity) {
        if (isModificationLocked.value) return
        viewModelScope.launch {
            focusRepo.updateSchedule(schedule.copy(isEnabled = !schedule.isEnabled))
        }
    }

    fun deleteSchedule(id: Long) {
        if (isModificationLocked.value) return
        viewModelScope.launch {
            focusRepo.deleteSchedule(id)
        }
    }

    // Task-based Unlock
    fun createTaskUnlock(targetPkg: String, targetName: String, targetMins: Int) {
        if (isModificationLocked.value) return
        viewModelScope.launch {
            focusRepo.addTaskUnlock(
                TaskUnlockEntity(
                    targetPackageName = targetPkg,
                    targetAppName = targetName,
                    requiredMinutes = targetMins,
                    completedMinutes = 0,
                    isUnlocked = false
                )
            )
        }
    }

    fun cancelTaskUnlock(id: Long) {
        if (isModificationLocked.value) return
        viewModelScope.launch {
            focusRepo.deleteTaskUnlock(id)
        }
    }

    // Blocked Websites
    fun addBlockedWebsite(domain: String) {
        if (isModificationLocked.value) return
        viewModelScope.launch {
            focusRepo.addBlockedWebsite(domain)
        }
    }

    fun toggleWebsite(id: Long, enabled: Boolean) {
        if (isModificationLocked.value) return
        viewModelScope.launch {
            focusRepo.toggleWebsite(id, enabled)
        }
    }

    fun toggleWebsiteDomains(domains: List<String>, enabled: Boolean) {
        if (isModificationLocked.value) return
        viewModelScope.launch {
            focusRepo.setDomainsEnabled(domains, enabled)
        }
    }

    fun deleteWebsite(id: Long) {
        if (isModificationLocked.value) return
        viewModelScope.launch {
            focusRepo.deleteWebsite(id)
        }
    }

    // Mascot State
    fun getMascotState(): MascotState {
        return when {
            isSessionActive.value && isStrictMode.value -> MascotState.STRICT_ACTIVE
            isSessionActive.value && currentMode.value == FocusMode.POMODORO -> {
                if (pomodoroPhase.value == PomodoroPhase.WORK) MascotState.POMODORO_WORK
                else MascotState.POMODORO_BREAK
            }
            isSessionActive.value -> MascotState.FOCUSING
            activeTaskUnlock.value != null && !(activeTaskUnlock.value?.isUnlocked ?: true) -> MascotState.TASK_RUNNING
            currentStreak.value >= 7 -> MascotState.VICTORY
            else -> MascotState.IDLE
        }
    }

    fun refreshPermissions() {
        _hasAccessibility.value = hasAccessibilityPermission()
        _hasUsageStats.value = hasUsageStatsPermission()
        _hasOverlay.value = hasOverlayPermission()
        val adminActive = isDeviceAdminActive()
        _hasDeviceAdmin.value = adminActive
        // If device admin was revoked from Android settings while no session is active, turn off strict mode
        if (!adminActive && !isSessionActive.value && isStrictMode.value) {
            prefsRepo.setStrictMode(false)
        }
    }

    // Permissions Helper Checks
    fun hasAccessibilityPermission(): Boolean {
        val context = getApplication<Application>()
        val expectedComponentName = ComponentName(context, "com.example.service.FocusAccessibilityService")
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(context.packageName)
    }

    fun hasUsageStatsPermission(): Boolean {
        return usageRepo.hasUsageAccessPermission()
    }

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(getApplication())
        } else true
    }

    fun hasAllRequiredPermissions(): Boolean {
        return hasAccessibilityPermission() && hasUsageStatsPermission() && hasOverlayPermission()
    }

    fun isDeviceAdminActive(): Boolean {
        val dpm = getApplication<Application>().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(getApplication(), FocusDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    // --- Authentication Actions ---
    fun signInWithEmail(email: String, pass: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = authRepo.signInWithEmail(email, pass)
            result.fold(
                onSuccess = {
                    onResult(true, null)
                },
                onFailure = { err ->
                    onResult(false, err.message)
                }
            )
        }
    }

    fun signUpWithEmail(email: String, pass: String, name: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = authRepo.signUpWithEmail(email, pass, name)
            result.fold(
                onSuccess = {
                    syncLocalProfileToCloud()
                    onResult(true, null)
                },
                onFailure = { err ->
                    onResult(false, err.message)
                }
            )
        }
    }

    fun signInWithGoogleIdToken(idToken: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = authRepo.signInWithGoogleIdToken(idToken)
            result.fold(
                onSuccess = {
                    onResult(true, null)
                },
                onFailure = { err ->
                    onResult(false, err.message)
                }
            )
        }
    }

    fun signOut() {
        authRepo.signOut()
    }

    fun syncLocalProfileToCloud() {
        viewModelScope.launch {
            authRepo.syncStats(
                streak = prefsRepo.currentStreak.value,
                totalMinutes = 0,
                sessions = sessionsHistory.value.size,
                isPro = prefsRepo.isProUser.value
            )
        }
    }

    fun clearAuthError() {
        authRepo.clearError()
    }
}
