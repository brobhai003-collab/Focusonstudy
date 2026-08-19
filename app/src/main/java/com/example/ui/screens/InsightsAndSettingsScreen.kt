package com.example.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.FocusDeviceAdminReceiver
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralStrict
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.VioletNeon
import com.example.ui.viewmodel.FocusViewModel
import com.example.ui.viewmodel.InsightsViewModel

@Composable
fun InsightsAndSettingsScreen(
    viewModel: FocusViewModel,
    insightsViewModel: InsightsViewModel,
    onNavigateToPro: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Screen Time Stats", "Strict Mode & Permissions")

    val dailyUsage by insightsViewModel.dailyAppUsage.collectAsStateWithLifecycle()
    val isLoadingUsage by insightsViewModel.isLoading.collectAsStateWithLifecycle()
    val totalScreenTimeMins by insightsViewModel.totalScreenTimeMinutes.collectAsStateWithLifecycle()
    val productiveMins by insightsViewModel.productiveMinutes.collectAsStateWithLifecycle()
    val distractedMins by insightsViewModel.distractedMinutes.collectAsStateWithLifecycle()

    val isProUser by viewModel.isProUser.collectAsStateWithLifecycle()
    val isStrictMode by viewModel.isStrictMode.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Tabs Header
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = CyanNeon,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = CyanNeon
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) CyanNeon else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedTab == 0) {
            // TAB 1: Screen Time & Insights
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Overview Summary Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Today's Screen Time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { insightsViewModel.refreshUsageData() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = CyanNeon)
                                }
                            }

                            Text(
                                text = "${totalScreenTimeMins / 60}h ${totalScreenTimeMins % 60}m",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black,
                                color = CyanNeon
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Breakdown Bar
                            val total = (productiveMins + distractedMins).coerceAtLeast(1).toFloat()
                            val prodRatio = productiveMins.toFloat() / total
                            val distRatio = distractedMins.toFloat() / total

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(prodRatio.coerceAtLeast(0.05f))
                                        .background(EmeraldSuccess)
                                        .fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(distRatio.coerceAtLeast(0.05f))
                                        .background(CoralStrict)
                                        .fillMaxSize()
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(EmeraldSuccess))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Productive: ${productiveMins}m",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldSuccess
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(CoralStrict))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Distracting: ${distractedMins}m",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = CoralStrict
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("App Usage Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (isLoadingUsage) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CyanNeon)
                        }
                    }
                } else {
                    items(dailyUsage, key = { it.packageName }) { appUsage ->
                        val mins = appUsage.usageMillis / (60 * 1000)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (appUsage.isDistracting) CoralStrict.copy(alpha = 0.2f) else EmeraldSuccess.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (appUsage.isDistracting) "⏳" else "⚡",
                                            fontSize = 16.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(appUsage.appName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        Text(
                                            if (appUsage.isDistracting) "Distraction App" else "Productive Use",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (appUsage.isDistracting) CoralStrict else EmeraldSuccess
                                        )
                                    }
                                }

                                Text(
                                    text = "${mins}m",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (appUsage.isDistracting) CoralStrict else CyanNeon
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        } else {
            // TAB 2: Strict Mode & Permissions
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // FocusLock Pro Subscription Banner
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF261D00)),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, AmberWarning),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToPro() }
                            .testTag("upgrade_pro_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = AmberWarning, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "FocusLock Pro",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = AmberWarning
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(color = AmberWarning, shape = RoundedCornerShape(4.dp)) {
                                        Text(
                                            text = if (isProUser) "ACTIVE" else "$1/MO",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color.Black,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (isProUser) "You have all Pro superpowers unlocked!" else "Unlocks Strict Mode, Pomodoro, Multiplayer Rooms & All Soundscapes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFFECC0)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Strict Mode Card
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isStrictMode) Color(0xFF331422) else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = if (isStrictMode) androidx.compose.foundation.BorderStroke(1.dp, CoralStrict) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = CoralStrict, modifier = Modifier.size(28.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Strict Mode (Uninstall Protection)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("Prevents deleting app or split-screen bypass", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Switch(
                                    checked = isStrictMode,
                                    onCheckedChange = { enabled ->
                                        if (!isProUser) onNavigateToPro()
                                        else viewModel.toggleStrictMode(enabled)
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = CoralStrict),
                                    modifier = Modifier.scale(0.85f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text("System Permissions Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("FocusLock requires these Android system permissions to operate effectively:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 1. Accessibility Service Permission
                item {
                    val hasAccessibility = viewModel.hasAccessibilityPermission()
                    PermissionSetupRow(
                        title = "Accessibility Service",
                        desc = "Detects foreground distracting apps and YouTube Shorts / IG Reels feeds.",
                        isGranted = hasAccessibility,
                        onGrantClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    )
                }

                // 2. Usage Stats Permission
                item {
                    val hasUsage = viewModel.hasUsageStatsPermission()
                    PermissionSetupRow(
                        title = "Usage Access (Screen Time)",
                        desc = "Calculates accurate screen time and task quest completion tracking.",
                        isGranted = hasUsage,
                        onGrantClick = {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    )
                }

                // 3. Display Over Other Apps (Overlay)
                item {
                    val hasOverlay = viewModel.hasOverlayPermission()
                    PermissionSetupRow(
                        title = "Display Over Other Apps",
                        desc = "Allows instant Focus Shield overlay when opening blocked apps.",
                        isGranted = hasOverlay,
                        onGrantClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        }
                    )
                }

                // 4. Device Administrator (Strict Mode)
                item {
                    val isAdmin = viewModel.isDeviceAdminActive()
                    PermissionSetupRow(
                        title = "Device Administrator",
                        desc = "Guarantees uninstall protection while Strict Mode focus is running.",
                        isGranted = isAdmin,
                        onGrantClick = {
                            val compName = ComponentName(context, FocusDeviceAdminReceiver::class.java)
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "FocusLock uses Device Administrator to prevent unauthorized uninstallation during active strict sessions.")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }
    }
}

@Composable
fun PermissionSetupRow(
    title: String,
    desc: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    if (isGranted) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = EmeraldSuccess, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) EmeraldSuccess.copy(alpha = 0.2f) else CyanNeon,
                    contentColor = if (isGranted) EmeraldSuccess else Color(0xFF00242B)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (isGranted) "Enabled" else "Enable", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
