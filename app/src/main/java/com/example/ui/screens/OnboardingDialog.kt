package com.example.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.FocusDeviceAdminReceiver
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralStrict
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.VioletNeon
import com.example.ui.viewmodel.FocusViewModel

@Composable
fun OnboardingDialog(
    viewModel: FocusViewModel,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val hasAccessibility by viewModel.hasAccessibility.collectAsStateWithLifecycle()
    val hasUsage by viewModel.hasUsageStats.collectAsStateWithLifecycle()
    val hasOverlay by viewModel.hasOverlay.collectAsStateWithLifecycle()
    val hasDeviceAdmin by viewModel.hasDeviceAdmin.collectAsStateWithLifecycle()

    // OnResume lifecycle listener to immediately detect permissions when user returns from Android Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val currentStep = when {
        !hasAccessibility -> 1
        !hasUsage -> 2
        !hasOverlay -> 3
        !hasDeviceAdmin -> 4
        else -> 5 // All 4 mandatory permissions granted!
    }

    AlertDialog(
        onDismissRequest = {
            // Strictly non-dismissable: user must grant all mandatory permissions
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Step Progress Indicator (1 - 2 - 3 - 4)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepBadge(stepNumber = 1, isActive = currentStep == 1, isCompleted = hasAccessibility, label = "Access")
                    StepDivider(isCompleted = hasAccessibility)
                    StepBadge(stepNumber = 2, isActive = currentStep == 2, isCompleted = hasUsage, label = "Usage")
                    StepDivider(isCompleted = hasUsage)
                    StepBadge(stepNumber = 3, isActive = currentStep == 3, isCompleted = hasOverlay, label = "Overlay")
                    StepDivider(isCompleted = hasOverlay)
                    StepBadge(stepNumber = 4, isActive = currentStep == 4, isCompleted = hasDeviceAdmin, label = "Admin")
                }

                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "onboarding_step_animation"
                ) { step ->
                    when (step) {
                        1 -> {
                            // STEP 1: Accessibility Service (Mandatory)
                            PermissionStepContent(
                                icon = Icons.Default.Accessibility,
                                iconColor = VioletNeon,
                                title = "1. Enable Focus Engine",
                                subtitle = "MANDATORY STEP 1 OF 4",
                                description = "Dedication requires Accessibility Service to detect when distracting apps (e.g. Instagram, YouTube Shorts, Games) or blocked websites are opened.",
                                actionText = "Open Accessibility Settings",
                                buttonTestTag = "open_accessibility_settings_button",
                                onActionClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(fallbackIntent)
                                    }
                                }
                            )
                        }
                        2 -> {
                            // STEP 2: Usage Access (Mandatory)
                            PermissionStepContent(
                                icon = Icons.Default.QueryStats,
                                iconColor = CyanNeon,
                                title = "2. Grant Usage Access",
                                subtitle = "MANDATORY STEP 2 OF 4",
                                description = "Allows Dedication to accurately calculate your daily screen time, productive focus minutes, and app usage limits.",
                                actionText = "Grant Usage Access",
                                buttonTestTag = "grant_usage_access_button",
                                onActionClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(fallbackIntent)
                                    }
                                }
                            )
                        }
                        3 -> {
                            // STEP 3: Display Overlay (Mandatory)
                            PermissionStepContent(
                                icon = Icons.Default.Layers,
                                iconColor = AmberWarning,
                                title = "3. Allow Shield Overlay",
                                subtitle = "MANDATORY STEP 3 OF 4",
                                description = "Enables Dedication to display the full-screen Focus Shield instantly over distracting apps during active focus sessions.",
                                actionText = "Allow Display Overlay",
                                buttonTestTag = "grant_overlay_permission_button",
                                onActionClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        try {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            ).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(fallbackIntent)
                                        }
                                    }
                                }
                            )
                        }
                        4 -> {
                            // STEP 4: Device Administrator / Anti-Uninstall Shield (Mandatory)
                            PermissionStepContent(
                                icon = Icons.Default.AdminPanelSettings,
                                iconColor = CoralStrict,
                                title = "4. Anti-Uninstall Shield",
                                subtitle = "MANDATORY STEP 4 OF 4",
                                description = "Dedication uses Device Administrator authorization to prevent uninstallation, force-stopping, or bypassing while focus sessions are active.",
                                actionText = "Activate Anti-Uninstall Shield",
                                buttonTestTag = "activate_admin_permission_button",
                                onActionClick = {
                                    viewModel.requestDeviceAdminPermission(context)
                                }
                            )
                        }
                        else -> {
                            // ALL 4 PERMISSIONS GRANTED!
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(CircleShape)
                                        .background(EmeraldSuccess.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Success",
                                        tint = EmeraldSuccess,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "All Systems Armed!",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Core security protocols & Anti-Uninstall shields are active. You can now start focusing.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        PermissionStatusItem(text = "Accessibility Focus Engine Active", isGranted = true)
                                        PermissionStatusItem(text = "Usage & Screen Time Tracker Active", isGranted = true)
                                        PermissionStatusItem(text = "Focus Shield Overlay Active", isGranted = true)
                                        PermissionStatusItem(text = "Device Admin (Anti-Uninstall) Active", isGranted = true)
                                    }
                                }
                                Spacer(modifier = Modifier.height(20.dp))
                                Button(
                                    onClick = onComplete,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanNeon,
                                        contentColor = Color(0xFF00242B)
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("launch_focuslock_button")
                                ) {
                                    Text("ENTER DEDICATION", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun PermissionStepContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    description: String,
    actionText: String,
    buttonTestTag: String,
    onActionClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Surface(
            color = iconColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = subtitle,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                color = iconColor,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onActionClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = iconColor,
                contentColor = if (iconColor == CyanNeon || iconColor == AmberWarning) Color(0xFF00242B) else Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag(buttonTestTag)
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(actionText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "App will automatically advance when you enable this setting.",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StepBadge(
    stepNumber: Int,
    isActive: Boolean,
    isCompleted: Boolean,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isCompleted -> EmeraldSuccess
                        isActive -> CyanNeon
                        else -> Color(0xFF334155)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Done",
                    tint = Color(0xFF00242B),
                    modifier = Modifier.size(15.dp)
                )
            } else {
                Text(
                    text = "$stepNumber",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = if (isActive) Color(0xFF00242B) else Color.White
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = if (isActive || isCompleted) FontWeight.Bold else FontWeight.Normal,
            color = if (isCompleted) EmeraldSuccess else if (isActive) CyanNeon else Color(0xFF64748B)
        )
    }
}

@Composable
private fun StepDivider(isCompleted: Boolean) {
    Box(
        modifier = Modifier
            .width(20.dp)
            .height(2.dp)
            .padding(horizontal = 2.dp)
            .background(if (isCompleted) EmeraldSuccess else Color(0xFF334155))
    )
}

@Composable
private fun PermissionStatusItem(
    text: String,
    isGranted: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (isGranted) EmeraldSuccess else CoralStrict,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isGranted) Color(0xFFE2E8F0) else Color(0xFF94A3B8),
            fontWeight = FontWeight.Medium
        )
    }
}
