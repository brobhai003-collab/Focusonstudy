package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class FocusMode {
    TIMER,
    STOPWATCH,
    POMODORO
}

enum class PomodoroPhase {
    WORK,
    SHORT_BREAK,
    LONG_BREAK
}

enum class AmbientSound(val displayName: String, val description: String) {
    NONE("Silent Focus", "No background sound"),
    LOFI_BEATS("Lofi Chill Synth", "Gentle relaxing harmonic synthesizer pulses"),
    WHITE_NOISE("Pure White Noise", "Constant soothing frequency mask"),
    RAINSTORM("Heavy Rainstorm", "Calming rain and thunderstorm resonance"),
    DEEP_SPACE("Binaural Alpha Waves", "Deep 432Hz focus stimulation"),
    FOREST_STREAM("Forest River", "Organic stream and natural ambiance")
}

@Entity(tableName = "blocked_apps")
data class BlockedAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isBlocked: Boolean = true,
    val isWhitelisted: Boolean = false,
    val blockShortsOnly: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val mode: String,
    val durationSeconds: Long,
    val completedAt: Long = System.currentTimeMillis(),
    val isStrict: Boolean = false,
    val isSuccessful: Boolean = true,
    val distractionCount: Int = 0
)

@Entity(tableName = "focus_schedules")
data class FocusScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: String = "1,2,3,4,5,6,7", // 1=Mon, 7=Sun
    val isEnabled: Boolean = true
)

@Entity(tableName = "task_unlocks")
data class TaskUnlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetPackageName: String,
    val targetAppName: String,
    val requiredMinutes: Int,
    val completedMinutes: Int = 0,
    val isUnlocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "blocked_websites")
data class BlockedWebsiteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val isEnabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val isBlocked: Boolean = false,
    val isWhitelisted: Boolean = false,
    val blockShortsOnly: Boolean = false
)

data class FocusRoom(
    val id: String,
    val name: String,
    val hostName: String,
    val topic: String,
    val participantCount: Int,
    val durationMinutes: Int,
    val isProOnly: Boolean = false,
    val activeParticipants: List<String> = emptyList()
)

data class LeaderboardUser(
    val rank: Int,
    val username: String,
    val focusMinutesToday: Int,
    val focusMinutesWeek: Int,
    val streakDays: Int,
    val badgeTitle: String,
    val avatarEmoji: String,
    val isCurrentUser: Boolean = false
)

data class ScreenTimeAppUsage(
    val packageName: String,
    val appName: String,
    val usageMillis: Long,
    val launchCount: Int,
    val isDistracting: Boolean
)

enum class MascotState(val emoji: String, val title: String, val quote: String) {
    IDLE("🤖", "ZenBot Ready", "Ready to lock in? Pick a focus mode and crush your goals!"),
    FOCUSING("🛡️", "Focus Shield Active", "Distractions locked out. Keep going, you got this!"),
    POMODORO_WORK("⚡", "Deep Work Sprint", "Pure laser concentration. No social media allowed!"),
    POMODORO_BREAK("☕", "Rest & Recharge", "Great sprint! Stretch, drink water, and rest your eyes."),
    STRICT_ACTIVE("🔒", "Strict Mode Engaged", "No escape! Uninstall & bypass shields are active."),
    TASK_RUNNING("🎯", "Task Quest in Progress", "Complete your productive target time to unlock other apps!"),
    VICTORY("🏆", "Session Conquered!", "Incredible discipline! Added focus points to your streak.")
}
