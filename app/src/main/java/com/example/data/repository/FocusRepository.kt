package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.data.local.FocusDao
import com.example.data.model.BlockedAppEntity
import com.example.data.model.BlockedWebsiteEntity
import com.example.data.model.FocusScheduleEntity
import com.example.data.model.FocusSessionEntity
import com.example.data.model.InstalledApp
import com.example.data.model.TaskUnlockEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FocusRepository(
    private val context: Context,
    private val focusDao: FocusDao
) {
    val blockedApps: Flow<List<BlockedAppEntity>> = focusDao.getAllBlockedApps()
    val allSessions: Flow<List<FocusSessionEntity>> = focusDao.getAllSessions()
    val allSchedules: Flow<List<FocusScheduleEntity>> = focusDao.getAllSchedules()
    val activeTaskUnlock: Flow<TaskUnlockEntity?> = focusDao.getActiveTaskUnlockFlow()
    val blockedWebsites: Flow<List<BlockedWebsiteEntity>> = focusDao.getAllBlockedWebsites()

    suspend fun getInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val blockedList = focusDao.getBlockedAppsList().associateBy { it.packageName }

        val appMap = mutableMapOf<String, InstalledApp>()

        for (resolveInfo in resolveInfos) {
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg == context.packageName) continue // Don't block self

            // Exclude internal system ui and background helpers
            if (pkg == "android" || pkg == "com.android.systemui" || pkg.startsWith("com.android.internal")) {
                continue
            }

            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: Exception) {
                null
            }

            val isSystem = if (appInfo != null) {
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            } else false

            // Use canonical Application Label, avoiding sub-component/ads activity names
            var appName = if (appInfo != null) {
                pm.getApplicationLabel(appInfo).toString()
            } else {
                resolveInfo.loadLabel(pm).toString()
            }

            // Normalization for known apps
            when (pkg) {
                "com.facebook.katana" -> appName = "Facebook"
                "com.facebook.lite" -> appName = "Facebook Lite"
                "com.facebook.orca" -> appName = "Messenger"
                "com.instagram.android" -> appName = "Instagram"
                "com.google.android.youtube" -> appName = "YouTube"
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> appName = "TikTok"
                "com.snapchat.android" -> appName = "Snapchat"
                "com.twitter.android" -> appName = "X (Twitter)"
                "com.reddit.frontpage" -> appName = "Reddit"
                "com.whatsapp" -> appName = "WhatsApp"
                "org.telegram.messenger" -> appName = "Telegram"
                "com.netflix.mediaclient" -> appName = "Netflix"
                "com.spotify.music" -> appName = "Spotify"
            }

            val blockedEntity = blockedList[pkg]
            appMap[pkg] = InstalledApp(
                packageName = pkg,
                appName = appName,
                isSystemApp = isSystem,
                isBlocked = blockedEntity?.isBlocked ?: isKnownDistraction(pkg),
                isWhitelisted = blockedEntity?.isWhitelisted ?: isKnownEssential(pkg),
                blockShortsOnly = blockedEntity?.blockShortsOnly ?: false
            )
        }

        // Always include popular social/distraction candidates in the catalogue so users can proactively configure them
        val popularCandidates = listOf(
            InstalledApp("com.facebook.katana", "Facebook", false, isBlocked = blockedList["com.facebook.katana"]?.isBlocked ?: true, isWhitelisted = false),
            InstalledApp("com.instagram.android", "Instagram", false, isBlocked = blockedList["com.instagram.android"]?.isBlocked ?: true, isWhitelisted = false, blockShortsOnly = true),
            InstalledApp("com.google.android.youtube", "YouTube", false, isBlocked = blockedList["com.google.android.youtube"]?.isBlocked ?: true, isWhitelisted = false, blockShortsOnly = true),
            InstalledApp("com.zhiliaoapp.musically", "TikTok", false, isBlocked = blockedList["com.zhiliaoapp.musically"]?.isBlocked ?: true, isWhitelisted = false),
            InstalledApp("com.snapchat.android", "Snapchat", false, isBlocked = blockedList["com.snapchat.android"]?.isBlocked ?: true, isWhitelisted = false),
            InstalledApp("com.twitter.android", "X (Twitter)", false, isBlocked = blockedList["com.twitter.android"]?.isBlocked ?: true, isWhitelisted = false),
            InstalledApp("com.reddit.frontpage", "Reddit", false, isBlocked = blockedList["com.reddit.frontpage"]?.isBlocked ?: true, isWhitelisted = false),
            InstalledApp("com.whatsapp", "WhatsApp", false, isBlocked = blockedList["com.whatsapp"]?.isBlocked ?: false, isWhitelisted = blockedList["com.whatsapp"]?.isWhitelisted ?: true)
        )

        for (candidate in popularCandidates) {
            if (!appMap.containsKey(candidate.packageName)) {
                appMap[candidate.packageName] = candidate
            }
        }

        val apps = appMap.values.sortedWith(
            compareByDescending<InstalledApp> { it.isBlocked }
                .thenBy { it.appName.lowercase() }
        )

        // If database is empty, seed defaults
        if (blockedList.isEmpty()) {
            val defaults = apps.filter { it.isBlocked || it.isWhitelisted }.map {
                BlockedAppEntity(
                    packageName = it.packageName,
                    appName = it.appName,
                    isBlocked = it.isBlocked,
                    isWhitelisted = it.isWhitelisted,
                    blockShortsOnly = it.blockShortsOnly
                )
            }
            if (defaults.isNotEmpty()) {
                focusDao.insertBlockedApps(defaults)
            }
            seedDefaultWebsites()
            seedDefaultSchedule()
        }

        apps
    }

    private suspend fun seedDefaultWebsites() {
        val defaultWebs = listOf(
            BlockedWebsiteEntity(domain = "instagram.com"),
            BlockedWebsiteEntity(domain = "tiktok.com"),
            BlockedWebsiteEntity(domain = "facebook.com"),
            BlockedWebsiteEntity(domain = "twitter.com"),
            BlockedWebsiteEntity(domain = "x.com"),
            BlockedWebsiteEntity(domain = "reddit.com"),
            BlockedWebsiteEntity(domain = "youtube.com/shorts")
        )
        defaultWebs.forEach { focusDao.insertWebsite(it) }
    }

    private suspend fun seedDefaultSchedule() {
        focusDao.insertSchedule(
            FocusScheduleEntity(
                label = "Evening Deep Focus",
                startHour = 18,
                startMinute = 0,
                endHour = 20,
                endMinute = 0,
                daysOfWeek = "1,2,3,4,5",
                isEnabled = true
            )
        )
    }

    private fun isKnownDistraction(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "com.facebook.katana" ||
                lower == "com.facebook.lite" ||
                lower == "com.facebook.orca" ||
                lower.contains("facebook") ||
                lower.contains("instagram") ||
                lower.contains("tiktok") ||
                lower.contains("musically") ||
                lower.contains("snapchat") ||
                lower.contains("twitter") ||
                lower.contains("reddit") ||
                lower.contains("pinterest") ||
                lower.contains("netflix") ||
                lower.contains("tinder") ||
                lower.contains("pubg") ||
                lower.contains("roblox")
    }

    private fun isKnownEssential(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("calculator") ||
                lower.contains("notes") ||
                lower.contains("clock") ||
                lower.contains("dialer") ||
                lower.contains("phone") ||
                lower.contains("contacts") ||
                lower.contains("whatsapp")
    }

    suspend fun setAppBlocked(packageName: String, appName: String, blocked: Boolean) {
        focusDao.insertBlockedApp(
            BlockedAppEntity(
                packageName = packageName,
                appName = appName,
                isBlocked = blocked,
                isWhitelisted = false
            )
        )
    }

    suspend fun setAppWhitelisted(packageName: String, appName: String, whitelisted: Boolean) {
        focusDao.insertBlockedApp(
            BlockedAppEntity(
                packageName = packageName,
                appName = appName,
                isBlocked = false,
                isWhitelisted = whitelisted
            )
        )
    }

    suspend fun setAppShortsBlockOnly(packageName: String, appName: String, shortsOnly: Boolean) {
        focusDao.insertBlockedApp(
            BlockedAppEntity(
                packageName = packageName,
                appName = appName,
                isBlocked = false,
                isWhitelisted = false,
                blockShortsOnly = shortsOnly
            )
        )
    }

    suspend fun recordSession(session: FocusSessionEntity): Long = focusDao.insertSession(session)

    suspend fun addSchedule(schedule: FocusScheduleEntity) = focusDao.insertSchedule(schedule)
    suspend fun updateSchedule(schedule: FocusScheduleEntity) = focusDao.updateSchedule(schedule)
    suspend fun deleteSchedule(id: Long) = focusDao.deleteSchedule(id)

    suspend fun addTaskUnlock(task: TaskUnlockEntity) = focusDao.insertTaskUnlock(task)
    suspend fun updateTaskUnlock(task: TaskUnlockEntity) = focusDao.updateTaskUnlock(task)
    suspend fun deleteTaskUnlock(id: Long) = focusDao.deleteTaskUnlock(id)

    suspend fun addBlockedWebsite(domain: String) =
        focusDao.insertWebsite(BlockedWebsiteEntity(domain = domain.trim().lowercase()))
    suspend fun toggleWebsite(id: Long, enabled: Boolean) = focusDao.updateWebsiteStatus(id, enabled)
    suspend fun deleteWebsite(id: Long) = focusDao.deleteWebsite(id)
}
