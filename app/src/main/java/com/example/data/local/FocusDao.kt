package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.BlockedAppEntity
import com.example.data.model.BlockedWebsiteEntity
import com.example.data.model.FocusScheduleEntity
import com.example.data.model.FocusSessionEntity
import com.example.data.model.TaskUnlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusDao {

    // --- Blocked Apps ---
    @Query("SELECT * FROM blocked_apps ORDER BY appName ASC")
    fun getAllBlockedApps(): Flow<List<BlockedAppEntity>>

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getBlockedApp(packageName: String): BlockedAppEntity?

    @Query("SELECT * FROM blocked_apps")
    suspend fun getBlockedAppsList(): List<BlockedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApp(app: BlockedAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApps(apps: List<BlockedAppEntity>)

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun deleteBlockedApp(packageName: String)

    @Query("UPDATE blocked_apps SET isBlocked = :isBlocked, isWhitelisted = :isWhitelisted WHERE packageName = :packageName")
    suspend fun updateAppStatus(packageName: String, isBlocked: Boolean, isWhitelisted: Boolean)

    @Query("UPDATE blocked_apps SET blockShortsOnly = :blockShortsOnly WHERE packageName = :packageName")
    suspend fun updateShortsBlocking(packageName: String, blockShortsOnly: Boolean)

    // --- Focus Sessions ---
    @Query("SELECT * FROM focus_sessions ORDER BY completedAt DESC")
    fun getAllSessions(): Flow<List<FocusSessionEntity>>

    @Query("SELECT SUM(durationSeconds) FROM focus_sessions WHERE isSuccessful = 1")
    fun getTotalFocusSeconds(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: FocusSessionEntity): Long

    // --- Schedules ---
    @Query("SELECT * FROM focus_schedules ORDER BY startHour ASC, startMinute ASC")
    fun getAllSchedules(): Flow<List<FocusScheduleEntity>>

    @Query("SELECT * FROM focus_schedules WHERE isEnabled = 1")
    suspend fun getActiveSchedules(): List<FocusScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: FocusScheduleEntity)

    @Update
    suspend fun updateSchedule(schedule: FocusScheduleEntity)

    @Query("DELETE FROM focus_schedules WHERE id = :id")
    suspend fun deleteSchedule(id: Long)

    // --- Task Unlocks ---
    @Query("SELECT * FROM task_unlocks ORDER BY createdAt DESC")
    fun getAllTaskUnlocks(): Flow<List<TaskUnlockEntity>>

    @Query("SELECT * FROM task_unlocks WHERE isUnlocked = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveTaskUnlock(): TaskUnlockEntity?

    @Query("SELECT * FROM task_unlocks WHERE isUnlocked = 0 ORDER BY createdAt DESC LIMIT 1")
    fun getActiveTaskUnlockFlow(): Flow<TaskUnlockEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskUnlock(task: TaskUnlockEntity): Long

    @Update
    suspend fun updateTaskUnlock(task: TaskUnlockEntity)

    @Query("DELETE FROM task_unlocks WHERE id = :id")
    suspend fun deleteTaskUnlock(id: Long)

    // --- Blocked Websites ---
    @Query("SELECT * FROM blocked_websites ORDER BY domain ASC")
    fun getAllBlockedWebsites(): Flow<List<BlockedWebsiteEntity>>

    @Query("SELECT * FROM blocked_websites WHERE isEnabled = 1")
    suspend fun getEnabledWebsites(): List<BlockedWebsiteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWebsite(website: BlockedWebsiteEntity)

    @Query("DELETE FROM blocked_websites WHERE id = :id")
    suspend fun deleteWebsite(id: Long)

    @Query("UPDATE blocked_websites SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateWebsiteStatus(id: Long, isEnabled: Boolean)
}
