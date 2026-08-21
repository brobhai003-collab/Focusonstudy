package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.AppBlockerScreen
import com.example.ui.screens.AuthDialog
import com.example.ui.screens.InsightsAndSettingsScreen
import com.example.ui.screens.OnboardingDialog
import com.example.ui.screens.PremiumPaywallDialog
import com.example.ui.screens.SchedulesAndTaskScreen
import com.example.ui.screens.TimerScreen
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralStrict
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.VioletNeon
import com.example.ui.viewmodel.FocusViewModel
import com.example.ui.viewmodel.InsightsViewModel

sealed class NavItem(val title: String, val icon: ImageVector, val tag: String) {
    object Timer : NavItem("Timer", Icons.Default.Timer, "nav_timer")
    object Blocker : NavItem("Shield", Icons.Default.Block, "nav_blocker")
    object Schedules : NavItem("Schedules", Icons.Default.Schedule, "nav_schedules")
    object Insights : NavItem("Insights", Icons.Default.Insights, "nav_insights")
}

class MainActivity : ComponentActivity() {

    private val focusViewModel: FocusViewModel by viewModels()
    private val insightsViewModel: InsightsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "enableEdgeToEdge warning: ${e.message}")
        }
        setContent {
            MyApplicationTheme(darkTheme = true) {
                FocusLockMainApp(
                    focusViewModel = focusViewModel,
                    insightsViewModel = insightsViewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusLockMainApp(
    focusViewModel: FocusViewModel,
    insightsViewModel: InsightsViewModel
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    var showProDialog by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }

    val navItems = listOf(
        NavItem.Timer,
        NavItem.Blocker,
        NavItem.Schedules,
        NavItem.Insights
    )

    val currentUser by focusViewModel.currentUser.collectAsStateWithLifecycle()
    val isProUser by focusViewModel.isProUser.collectAsStateWithLifecycle()
    val isSessionActive by focusViewModel.isSessionActive.collectAsStateWithLifecycle()
    val isStrictMode by focusViewModel.isStrictMode.collectAsStateWithLifecycle()
    val currentStreak by focusViewModel.currentStreak.collectAsStateWithLifecycle()
    val isOnboardingDone by focusViewModel.isOnboardingDone.collectAsStateWithLifecycle()

    val showOnboarding = !isOnboardingDone

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_mascot_zen),
                            contentDescription = "ZenBot Mascot",
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Dedication",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 19.sp,
                                    color = Color.White
                                )
                                if (isStrictMode && isSessionActive) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = CoralStrict.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, CoralStrict)
                                    ) {
                                        Text(
                                            text = "STRICT",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black,
                                            color = CoralStrict,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = if (isSessionActive) "Focus Shield Armed" else "Reclaim your deep focus",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSessionActive) CyanNeon else Color(0xFF94A3B8)
                            )
                        }
                    }
                },
                actions = {
                    // Cloud Account / Sign-In Button
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showAuthDialog = true }
                            .padding(end = 6.dp)
                            .testTag("top_bar_auth_button"),
                        color = if (currentUser != null) EmeraldSuccess.copy(alpha = 0.15f) else Color(0xFF1E293B),
                        border = if (currentUser != null) androidx.compose.foundation.BorderStroke(1.dp, EmeraldSuccess) else null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (currentUser != null) Icons.Default.CloudDone else Icons.Default.AccountCircle,
                                contentDescription = "Account",
                                tint = if (currentUser != null) EmeraldSuccess else CyanNeon,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (currentUser != null) (currentUser?.displayName?.split(" ")?.firstOrNull() ?: "SYNCED") else "LOGIN",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (currentUser != null) EmeraldSuccess else Color.White
                            )
                        }
                    }

                    // Pro Badge / Action
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showProDialog = true }
                            .padding(end = 12.dp)
                            .testTag("top_bar_pro_badge"),
                        color = if (isProUser) AmberWarning.copy(alpha = 0.2f) else Color(0xFF1E293B),
                        border = if (isProUser) androidx.compose.foundation.BorderStroke(1.dp, AmberWarning) else null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Pro",
                                tint = if (isProUser) AmberWarning else Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isProUser) "PRO" else "UPGRADE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = if (isProUser) AmberWarning else Color(0xFFCBD5E1)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp,
                modifier = Modifier.testTag("bottom_nav_bar")
            ) {
                navItems.forEachIndexed { index, item ->
                    val isSelected = selectedIndex == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedIndex = index },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00242B),
                            selectedTextColor = CyanNeon,
                            indicatorColor = CyanNeon,
                            unselectedIconColor = Color(0xFF64748B),
                            unselectedTextColor = Color(0xFF64748B)
                        ),
                        modifier = Modifier.testTag(item.tag)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(
                targetState = selectedIndex,
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                label = "screen_crossfade"
            ) { targetIndex ->
                when (targetIndex) {
                    0 -> TimerScreen(
                        viewModel = focusViewModel,
                        onNavigateToPro = { showProDialog = true },
                        onNavigateToBlocker = { selectedIndex = 1 }
                    )
                    1 -> AppBlockerScreen(
                        viewModel = focusViewModel
                    )
                    2 -> SchedulesAndTaskScreen(
                        viewModel = focusViewModel,
                        onNavigateToPro = { showProDialog = true }
                    )
                    3 -> InsightsAndSettingsScreen(
                        viewModel = focusViewModel,
                        insightsViewModel = insightsViewModel,
                        onNavigateToPro = { showProDialog = true }
                    )
                }
            }
        }
    }

    // Pro Upgrade Dialog
    if (showProDialog) {
        PremiumPaywallDialog(
            isProUser = isProUser,
            onUpgrade = { newStatus ->
                focusViewModel.setProUser(newStatus)
            },
            onDismiss = { showProDialog = false }
        )
    }

    // Cloud Account & Sync Dialog
    if (showAuthDialog) {
        AuthDialog(
            viewModel = focusViewModel,
            onDismiss = { showAuthDialog = false }
        )
    }

    // First-run Onboarding Permissions Wizard (Strict Mandatory Enforce)
    if (showOnboarding) {
        OnboardingDialog(
            viewModel = focusViewModel,
            onComplete = {
                focusViewModel.setOnboardingDone(true)
            }
        )
    }
}
