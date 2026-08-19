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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FocusViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FocusLockApp
    private val focusRepo = app.focusRepository
    private val prefsRepo = app.preferencesRepository
    private val usageRepo = app.usageStatsRepository

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

    val filteredInstalledApps = combine(installedApps, appSearchQuery) { apps, query ->
        if (query.isBlank()) apps
        else apps.filter { it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadInstalledApps()
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            try {
                _installedApps.value = focusRepo.getInstalledApps()
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
        _selectedTimerMode.value = mode
    }

    fun setSessionName(name: String) {
        _customSessionName.value = name
    }

    // --- Focus Actions ---
    fun startFocusSession() {
        val durationSeconds = _selectedDurationMinutes.value * 60L
        FocusTimerService.start(
            context = getApplication(),
            mode = _selectedTimerMode.value,
            durationSeconds = durationSeconds,
            label = _customSessionName.value.ifBlank { "Deep Focus" },
            isStrict = isStrictMode.value,
            sound = selectedAmbient.value
        )
    }

    fun pauseFocusSession() {
        FocusTimerService.pause(getApplication())
    }

    fun resumeFocusSession() {
        FocusTimerService.resume(getApplication())
    }

    fun stopFocusSession() {
        FocusTimerService.stop(getApplication())
    }

    fun setAmbientSound(sound: AmbientSound) {
        prefsRepo.setAmbientSound(sound)
        if (isSessionActive.value) {
            FocusTimerService.setSound(getApplication(), sound)
        }
    }

    fun toggleAppBlock(packageName: String, appName: String, currentlyBlocked: Boolean) {
        viewModelScope.launch {
            focusRepo.setAppBlocked(packageName, appName, !currentlyBlocked)
            loadInstalledApps()
        }
    }

    fun toggleAppWhitelist(packageName: String, appName: String, currentlyWhitelisted: Boolean) {
        viewModelScope.launch {
            focusRepo.setAppWhitelisted(packageName, appName, !currentlyWhitelisted)
            loadInstalledApps()
        }
    }

    fun toggleAppShortsOnly(packageName: String, appName: String, currentShortsOnly: Boolean) {
        viewModelScope.launch {
            focusRepo.setAppShortsBlockOnly(packageName, appName, !currentShortsOnly)
            loadInstalledApps()
        }
    }

    fun toggleStrictMode(enabled: Boolean) {
        prefsRepo.setStrictMode(enabled)
    }

    fun toggleShortsBlocker(enabled: Boolean) {
        prefsRepo.setShortsBlocker(enabled)
    }

    fun toggleWebBlocker(enabled: Boolean) {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            focusRepo.updateSchedule(schedule.copy(isEnabled = !schedule.isEnabled))
        }
    }

    fun deleteSchedule(id: Long) {
        viewModelScope.launch {
            focusRepo.deleteSchedule(id)
        }
    }

    // Task-based Unlock
    fun createTaskUnlock(targetPkg: String, targetName: String, targetMins: Int) {
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
        viewModelScope.launch {
            focusRepo.deleteTaskUnlock(id)
        }
    }

    // Blocked Websites
    fun addBlockedWebsite(domain: String) {
        viewModelScope.launch {
            focusRepo.addBlockedWebsite(domain)
        }
    }

    fun toggleWebsite(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            focusRepo.toggleWebsite(id, enabled)
        }
    }

    fun deleteWebsite(id: Long) {
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

    fun isDeviceAdminActive(): Boolean {
        val dpm = getApplication<Application>().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(getApplication(), FocusDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }
}
