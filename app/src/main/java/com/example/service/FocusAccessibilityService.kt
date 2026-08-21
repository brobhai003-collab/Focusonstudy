package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.FocusLockApp
import com.example.data.model.FocusScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Collections

class FocusAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var lastBlockedTime = 0L
    private val debounceMillis = 1500L

    private val blockedDomainsCache = Collections.synchronizedSet(mutableSetOf<String>())

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Observe Room database updates for any custom-added or toggled websites in real-time
        serviceScope.launch {
            try {
                val app = applicationContext as? FocusLockApp
                val db = app?.database ?: com.example.data.local.FocusLockDatabase.getDatabase(applicationContext)
                db.focusDao().getAllBlockedWebsites().collect { list ->
                    val enabled = list.filter { it.isEnabled }.map { it.domain.lowercase().trim() }
                    synchronized(blockedDomainsCache) {
                        blockedDomainsCache.clear()
                        blockedDomainsCache.addAll(enabled)
                    }
                }
            } catch (e: Exception) {
                Log.e("FocusAccessibility", "Error collecting blocked websites: ${e.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app package
        if (packageName == applicationContext.packageName) return

        val currentTime = SystemClock.uptimeMillis()

        // 1. Check if Strict Mode is protecting Settings/Uninstall
        if (isStrictSettingsTampering(packageName, event)) {
            if (currentTime - lastBlockedTime > debounceMillis) {
                lastBlockedTime = currentTime
                performGlobalAction(GLOBAL_ACTION_HOME)
                launchBlockScreen(
                    packageName = packageName,
                    appName = "System Settings",
                    reason = "🔒 Strict Mode Active: Settings & Uninstall are locked during session!"
                )
            }
            return
        }

        // 2. Check Short-form video blocker (Shorts / Reels)
        val isShortsBlockEnabled =
            FocusLockApp.instance.preferencesRepository.isShortsBlockerEnabled.value

        if (isShortsBlockEnabled) {
            val isShortFormContent = detectShortFormContent(packageName, rootInActiveWindow)
            if (isShortFormContent) {
                if (currentTime - lastBlockedTime > debounceMillis) {
                    lastBlockedTime = currentTime
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    launchBlockScreen(
                        packageName = packageName,
                        appName = if (packageName.contains("youtube")) "YouTube Shorts" else "Instagram Reels",
                        reason = "⚡ Short-Form Doomscroll Shield: Shorts & Reels are blocked!"
                    )
                }
                return
            }
        }

        // 3. Check Website Blocker in Browser (Applies when Web Shield is ON or Focus Session is Active)
        val isWebBlockEnabled =
            FocusLockApp.instance.preferencesRepository.isWebBlockerEnabled.value
        val isSessionActive = FocusTimerService.isSessionActive.value ||
            FocusLockApp.instance.preferencesRepository.isSessionCurrentlyActive()

        if ((isWebBlockEnabled || isSessionActive) && isBrowserPackage(packageName)) {
            val blockedDomain = detectBlockedWebsite(rootInActiveWindow)
            if (blockedDomain != null) {
                if (currentTime - lastBlockedTime > debounceMillis) {
                    lastBlockedTime = currentTime
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    launchBlockScreen(
                        packageName = packageName,
                        appName = "Browser ($blockedDomain)",
                        reason = "🌐 Website Blocked: $blockedDomain is on your distraction blacklist."
                    )
                }
                return
            }
        }

        // 4. Check if Active Focus Timer / Active Schedule / Active Task Unlock requires blocking
        serviceScope.launch {
            checkAndBlockAppIfNeeded(packageName, currentTime)
        }
    }

    private suspend fun checkAndBlockAppIfNeeded(packageName: String, currentTime: Long) {
        val app = FocusLockApp.instance.database.focusDao().getBlockedApp(packageName)
        val isExplicitlyBlocked = app?.isBlocked == true
        val isWhitelisted = app?.isWhitelisted == true

        if (isWhitelisted) return // Always allow whitelisted apps

        // Check A: Active Focus Session Running (In-memory or Persistent)
        val prefsRepo = FocusLockApp.instance.preferencesRepository
        val isPersistentActive = prefsRepo.isSessionCurrentlyActive()
        val isTimerRunning = FocusTimerService.isSessionActive.value || isPersistentActive

        if (isPersistentActive && !FocusTimerService.isSessionActive.value) {
            // Auto re-spawn FocusTimerService in foreground if it was killed
            val saved = prefsRepo.getActiveSession()
            if (saved != null && saved.targetEndTimeMillis > System.currentTimeMillis()) {
                val remaining = ((saved.targetEndTimeMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(10L)
                val mode = try { com.example.data.model.FocusMode.valueOf(saved.mode) } catch (e: Exception) { com.example.data.model.FocusMode.TIMER }
                val sound = try { com.example.data.model.AmbientSound.valueOf(saved.sound) } catch (e: Exception) { com.example.data.model.AmbientSound.NONE }
                FocusTimerService.start(
                    context = applicationContext,
                    mode = mode,
                    durationSeconds = remaining,
                    label = saved.label,
                    isStrict = saved.isStrict,
                    sound = sound
                )
            }
        }

        if (isTimerRunning && isExplicitlyBlocked) {
            triggerBlock(packageName, app?.appName ?: packageName, "🎯 Focus Session is Active! Stay locked in.")
            return
        }

        // Check B: Active Scheduled Lockout Window
        val activeSchedule = getActiveScheduleMatchingNow()
        if (activeSchedule != null && isExplicitlyBlocked) {
            triggerBlock(
                packageName,
                app?.appName ?: packageName,
                "⏰ Scheduled Lockout Active: ${activeSchedule.label}"
            )
            return
        }

        // Check C: Task-Based Unlock Pending
        val pendingTask = FocusLockApp.instance.database.focusDao().getActiveTaskUnlock()
        if (pendingTask != null && !pendingTask.isUnlocked) {
            if (packageName != pendingTask.targetPackageName && isExplicitlyBlocked) {
                val remainingMins = (pendingTask.requiredMinutes - pendingTask.completedMinutes).coerceAtLeast(1)
                triggerBlock(
                    packageName,
                    app?.appName ?: packageName,
                    "🎯 Task Unlock Quest: Complete $remainingMins min on ${pendingTask.targetAppName} to unlock!"
                )
                return
            }
        }
    }

    private fun triggerBlock(packageName: String, appName: String, reason: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBlockedTime > debounceMillis) {
            lastBlockedTime = now
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchBlockScreen(packageName, appName, reason)
        }
    }

    private fun launchBlockScreen(packageName: String, appName: String, reason: String) {
        val intent = Intent(applicationContext, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BlockActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(BlockActivity.EXTRA_APP_NAME, appName)
            putExtra(BlockActivity.EXTRA_REASON, reason)
        }
        startActivity(intent)
    }

    private fun isStrictSettingsTampering(packageName: String, event: AccessibilityEvent): Boolean {
        val isStrictActive = FocusTimerService.isSessionActive.value && 
                (FocusTimerService.isStrictMode.value || FocusLockApp.instance.preferencesRepository.isStrictActive())
        if (!isStrictActive) return false

        val lowerPkg = packageName.lowercase()
        if (lowerPkg.contains("settings") || 
            lowerPkg.contains("packageinstaller") || 
            lowerPkg.contains("systemui") ||
            lowerPkg.contains("deviceadmin")
        ) {
            val text = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).lowercase()
            val myPackage = applicationContext.packageName.lowercase()
            
            return text.contains("uninstall") || 
                   text.contains("force stop") || 
                   text.contains("clear data") || 
                   text.contains("clear storage") || 
                   text.contains("device admin") || 
                   text.contains("device administrator") || 
                   text.contains("dedication") || 
                   text.contains("focuslock") ||
                   text.contains(myPackage) ||
                   lowerPkg.contains("packageinstaller")
        }
        return false
    }

    private fun detectShortFormContent(packageName: String, root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        try {
            // YouTube Shorts Detection
            if (packageName.contains("com.google.android.youtube")) {
                val shortsViews = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/shorts_container")
                if (shortsViews.isNotEmpty()) return true

                val reelViews = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_recycler")
                if (reelViews.isNotEmpty()) return true

                val playerViews = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_player_view_layout")
                if (playerViews.isNotEmpty()) return true
            }

            // Instagram Reels Detection
            if (packageName.contains("com.instagram.android")) {
                val reelsViews = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/reel_viewer_container")
                if (reelsViews.isNotEmpty()) return true

                val clipsViews = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/clips_video_container")
                if (clipsViews.isNotEmpty()) return true

                val tabViews = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/clips_tab")
                if (tabViews.isNotEmpty()) return true
            }

            // TikTok
            if (packageName.contains("zhiliaoapp.musically") || packageName.contains("tiktok")) {
                return true
            }
        } catch (e: Exception) {
            // Node inspection failure safe-guard
        }

        return false
    }

    private fun isBrowserPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("chrome") ||
                lower.contains("firefox") ||
                lower.contains("sbrowser") ||
                lower.contains("opera") ||
                lower.contains("edge") ||
                lower.contains("brave") ||
                lower.contains("duckduckgo") ||
                lower.contains("browser") ||
                lower.contains("webview")
    }

    private fun detectBlockedWebsite(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        try {
            val candidateTexts = mutableListOf<String>()

            // 1. Check known browser URL bar view IDs
            val urlBarIds = listOf(
                "com.android.chrome:id/url_bar",
                "com.android.chrome:id/location_bar",
                "com.android.chrome:id/search_box_text",
                "org.mozilla.firefox:id/url_bar",
                "org.mozilla.firefox:id/toolbar",
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/url_bar",
                "com.microsoft.emmx:id/url_bar",
                "com.opera.browser:id/url_field",
                "com.opera.mini.native:id/url_field",
                "com.brave.browser:id/url_bar",
                "com.duckduckgo.mobile.android:id/omnibarTextInput"
            )

            for (id in urlBarIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                for (n in nodes) {
                    n.text?.toString()?.let { candidateTexts.add(it.lowercase()) }
                    n.contentDescription?.toString()?.let { candidateTexts.add(it.lowercase()) }
                }
            }

            // 2. Recursive scan for any address bar or webview node text if needed
            collectAddressBarTexts(root, candidateTexts, maxDepth = 4)

            // Current enabled domains snapshot
            val activeDomains: Set<String> = synchronized(blockedDomainsCache) {
                blockedDomainsCache.toSet()
            }

            for (rawText in candidateTexts) {
                val text = rawText.lowercase().trim()
                if (text.isEmpty()) continue

                for (domain in activeDomains) {
                    val cleanDomain = domain.lowercase().trim()
                    if (cleanDomain.isEmpty()) continue

                    val domainKeyword = cleanDomain.removePrefix("www.").removePrefix("m.")
                    if (text.contains(cleanDomain) || (domainKeyword.length >= 4 && text.contains(domainKeyword))) {
                        return cleanDomain
                    }
                }
            }
        } catch (e: Exception) {
            // Handled
        }
        return null
    }

    private fun collectAddressBarTexts(node: AccessibilityNodeInfo?, list: MutableList<String>, maxDepth: Int) {
        if (node == null || maxDepth <= 0) return
        val cls = node.className?.toString() ?: ""
        if (cls.contains("EditText", ignoreCase = true) || cls.contains("TextView", ignoreCase = true) || node.isEditable || node.isFocused) {
            node.text?.toString()?.let { list.add(it.lowercase()) }
            node.contentDescription?.toString()?.let { list.add(it.lowercase()) }
        }
        for (i in 0 until node.childCount) {
            collectAddressBarTexts(node.getChild(i), list, maxDepth - 1)
        }
    }

    private suspend fun getActiveScheduleMatchingNow(): FocusScheduleEntity? {
        val schedules = FocusLockApp.instance.database.focusDao().getActiveSchedules()
        if (schedules.isEmpty()) return null

        val cal = Calendar.getInstance()
        val currentDay = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        for (sch in schedules) {
            val days = sch.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (days.contains(currentDay)) {
                val startM = sch.startHour * 60 + sch.startMinute
                val endM = sch.endHour * 60 + sch.endMinute
                if (startM <= endM) {
                    if (currentMinutes in startM..endM) return sch
                } else {
                    // Overnight schedule (e.g. 22:00 to 06:00)
                    if (currentMinutes >= startM || currentMinutes <= endM) return sch
                }
            }
        }
        return null
    }

    private fun isCommonDistraction(pkg: String): Boolean {
        val l = pkg.lowercase()
        return l == "com.facebook.katana" ||
                l == "com.facebook.lite" ||
                l == "com.facebook.orca" ||
                l == "com.instagram.android" ||
                l == "com.google.android.youtube" ||
                l == "in.moj.app" ||
                l == "com.sharechat.moj" ||
                l == "com.sharechat.android" ||
                l.contains("facebook") ||
                l.contains("instagram") ||
                l.contains("tiktok") ||
                l.contains("musically") ||
                l.contains("snapchat") ||
                l.contains("reddit") ||
                l.contains("twitter") ||
                l.contains("pinterest") ||
                l.contains("youtube") ||
                l.contains("moj")
    }

    override fun onInterrupt() {}
}
