package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AmbientSound
import com.example.data.model.FocusMode
import com.example.data.model.PomodoroPhase
import com.example.ui.components.CircularProgressTimer
import com.example.ui.components.ZenMascotCard
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralStrict
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.VioletNeon
import com.example.ui.viewmodel.FocusViewModel

@Composable
fun TimerScreen(
    viewModel: FocusViewModel,
    onNavigateToPro: () -> Unit,
    onNavigateToBlocker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSessionActive by viewModel.isSessionActive.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()
    val pomodoroPhase by viewModel.pomodoroPhase.collectAsStateWithLifecycle()
    val remainingSeconds by viewModel.remainingSeconds.collectAsStateWithLifecycle()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsStateWithLifecycle()
    val targetDurationSeconds by viewModel.targetDurationSeconds.collectAsStateWithLifecycle()
    val selectedDurationMinutes by viewModel.selectedDurationMinutes.collectAsStateWithLifecycle()
    val selectedTimerMode by viewModel.selectedTimerMode.collectAsStateWithLifecycle()
    val selectedAmbient by viewModel.selectedAmbient.collectAsStateWithLifecycle()
    val isStrictMode by viewModel.isStrictMode.collectAsStateWithLifecycle()
    val isSessionStrictLocked by viewModel.isSessionStrictLocked.collectAsStateWithLifecycle()
    val graceSecondsRemaining by viewModel.graceSecondsRemaining.collectAsStateWithLifecycle()
    val isProUser by viewModel.isProUser.collectAsStateWithLifecycle()
    val currentStreak by viewModel.currentStreak.collectAsStateWithLifecycle()
    val blockedApps by viewModel.blockedAppsList.collectAsStateWithLifecycle()

    var showAmbientDialog by remember { mutableStateOf(false) }
    var showGiveUpConfirmDialog by remember { mutableStateOf(false) }

    val blockedCount = blockedApps.count { it.isBlocked }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))

            // Mascot Companion Card
            ZenMascotCard(
                mascotState = viewModel.getMascotState(),
                currentStreak = currentStreak,
                isPro = isProUser,
                onUpgradeClick = onNavigateToPro
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Timer Mode Selector (Disabled when session is active)
            if (!isSessionActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FocusMode.values().forEach { mode ->
                        val isSelected = selectedTimerMode == mode
                        val isLocked = mode == FocusMode.POMODORO && !isProUser

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (isLocked) onNavigateToPro()
                                    else viewModel.setSelectedMode(mode)
                                }
                                .testTag("mode_${mode.name.lowercase()}"),
                            color = if (isSelected) CyanNeon else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (mode) {
                                        FocusMode.TIMER -> "Timer"
                                        FocusMode.STOPWATCH -> "Stopwatch"
                                        FocusMode.POMODORO -> "Pomodoro"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color(0xFF002229) else MaterialTheme.colorScheme.onSurface
                                )
                                if (isLocked) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = "Pro",
                                        modifier = Modifier.size(12.dp),
                                        tint = AmberWarning
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Circular Progress Display
            val displaySeconds = if (isSessionActive) {
                if (currentMode == FocusMode.STOPWATCH) elapsedSeconds else remainingSeconds
            } else {
                if (selectedTimerMode == FocusMode.STOPWATCH) 0L else selectedDurationMinutes * 60L
            }

            val totalTarget = if (isSessionActive) targetDurationSeconds.coerceAtLeast(1) else (selectedDurationMinutes * 60L).coerceAtLeast(1)
            val rawProgress = if (isSessionActive) {
                if (currentMode == FocusMode.STOPWATCH) 1f
                else ((totalTarget - remainingSeconds).toFloat() / totalTarget.toFloat()).coerceIn(0f, 1f)
            } else 0f

            val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                targetValue = rawProgress,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = if (isSessionActive && !isPaused) 1000 else 350,
                    easing = androidx.compose.animation.core.LinearEasing
                ),
                label = "circular_timer_progress"
            )

            val timeFormatted = String.format("%02d:%02d", displaySeconds / 60, displaySeconds % 60)

            val modeLabel = if (isSessionActive) {
                when (currentMode) {
                    FocusMode.POMODORO -> "POMODORO: ${pomodoroPhase.name}"
                    FocusMode.STOPWATCH -> "STOPWATCH COUNT UP"
                    FocusMode.TIMER -> if (isStrictMode) "STRICT LOCK" else "FOCUS TIMER"
                }
            } else {
                when (selectedTimerMode) {
                    FocusMode.POMODORO -> "POMODORO 25/5"
                    FocusMode.STOPWATCH -> "OPEN STOPWATCH"
                    FocusMode.TIMER -> if (isStrictMode) "STRICT MODE READY" else "TIMER READY"
                }
            }

            val statusSubtext = if (isSessionActive) {
                if (isPaused) "Session Paused" else "Shield Active ($blockedCount apps locked)"
            } else {
                "$blockedCount apps will be locked"
            }

            CircularProgressTimer(
                progress = animatedProgress,
                timeText = timeFormatted,
                statusText = statusSubtext,
                modeTag = modeLabel,
                isStrict = isStrictMode
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Duration Presets (Only if Timer mode & not running)
            if (!isSessionActive && selectedTimerMode != FocusMode.STOPWATCH) {
                Text(
                    text = "Quick Select Duration",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf(15, 25, 45, 60, 90).forEach { mins ->
                        val isSel = selectedDurationMinutes == mins
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.setSelectedDuration(mins) },
                            color = if (isSel) CyanNeon.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSel) androidx.compose.foundation.BorderStroke(1.dp, CyanNeon) else null,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "${mins}m",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) CyanNeon else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Quick Options Row (Ambient Sound & Strict Mode)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Ambient Sound Pill
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showAmbientDialog = true }
                        .testTag("ambient_sound_button"),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = VioletNeon,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ambient Sound",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = selectedAmbient.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Strict Mode Toggle Pill
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !isSessionActive) {
                            if (!isProUser) onNavigateToPro()
                            else viewModel.toggleStrictMode(!isStrictMode)
                        }
                        .testTag("strict_mode_button"),
                    color = if (isStrictMode) CoralStrict.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (isStrictMode) androidx.compose.foundation.BorderStroke(1.dp, CoralStrict) else null,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (isStrictMode) CoralStrict else if (!isProUser) AmberWarning else Color(0xFF64748B),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Strict Mode",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!isProUser) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("PRO", fontSize = 9.sp, color = AmberWarning, fontWeight = FontWeight.Black)
                                } else if (isSessionActive) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("LOCKED", fontSize = 8.sp, color = CoralStrict, fontWeight = FontWeight.Black)
                                }
                            }
                            Text(
                                text = if (isSessionActive) {
                                    if (isStrictMode) "Locked ON" else "Locked OFF"
                                } else if (isStrictMode) "Armed ON" else "Off (Tap)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isStrictMode) CoralStrict else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Main Primary Action Buttons
            if (!isSessionActive) {
                Button(
                    onClick = { viewModel.startFocusSession() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("start_focus_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanNeon,
                        contentColor = Color(0xFF00242B)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("START FOCUS SESSION", fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 1.sp)
                }
            } else {
                if (isSessionStrictLocked) {
                    // Auto Strict Lock Enforced Banner & Completely Locked Controls
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("strict_mode_locked_banner"),
                        colors = CardDefaults.cardColors(
                            containerColor = CoralStrict.copy(alpha = 0.12f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, CoralStrict),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(CoralStrict.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Strict Mode Enforced",
                                    tint = CoralStrict,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isStrictMode) "Strict Mode Active" else "Auto Strict-Lock Engaged",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CoralStrict
                                )
                                Text(
                                    text = if (isStrictMode) 
                                        "Strict Mode is on. Pause & Give Up are locked until session ends."
                                    else 
                                        "1-minute grace passed! Pause & Give Up are locked until timer completes to keep you distraction-free.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Grace Period Banner
                        if (graceSecondsRemaining > 0) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp),
                                color = AmberWarning.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = AmberWarning,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Grace Period: Auto-Lock in ${graceSecondsRemaining}s",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AmberWarning
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Pause / Resume
                            Button(
                                onClick = {
                                    if (isPaused) viewModel.resumeFocusSession()
                                    else viewModel.pauseFocusSession()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .testTag("pause_resume_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VioletNeon,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPaused) "Resume" else "Pause", fontWeight = FontWeight.Bold)
                            }

                            // Stop / Give Up Button
                            OutlinedButton(
                                onClick = { showGiveUpConfirmDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .testTag("stop_focus_button"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = CoralStrict
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, CoralStrict),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = CoralStrict)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Give Up", fontWeight = FontWeight.Bold, color = CoralStrict)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Shortcut to Blocked Apps Screen
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onNavigateToBlocker() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Apps Locked during Focus", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Surface(
                        color = CyanNeon.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "$blockedCount Apps",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = CyanNeon,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Ambient Sound Selection Bottom Sheet / Dialog
    if (showAmbientDialog) {
        AmbientSoundSelectorDialog(
            currentSound = selectedAmbient,
            isPro = isProUser,
            onSoundSelect = { sound ->
                viewModel.setAmbientSound(sound)
                showAmbientDialog = false
            },
            onDismiss = { showAmbientDialog = false },
            onUpgradeToPro = onNavigateToPro
        )
    }

    // Give Up Confirmation Dialog
    if (showGiveUpConfirmDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showGiveUpConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = null,
                    tint = CoralStrict,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Give Up Focus Session?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "If you quit early, this session will be marked as abandoned. Your streak and focus minutes will not be credited.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGiveUpConfirmDialog = false
                        viewModel.stopFocusSession()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoralStrict,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("confirm_give_up_button")
                ) {
                    Text("Yes, Give Up", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showGiveUpConfirmDialog = false },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("cancel_give_up_button")
                ) {
                    Text("Keep Focusing", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
fun AmbientSoundSelectorDialog(
    currentSound: AmbientSound,
    isPro: Boolean,
    onSoundSelect: (AmbientSound) -> Unit,
    onDismiss: () -> Unit,
    onUpgradeToPro: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = VioletNeon)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Focus Soundscapes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "High-fidelity dynamic synthesizer audio engineered to induce flow state and block out background noise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                AmbientSound.values().forEach { sound ->
                    val isSelected = currentSound == sound
                    val isLocked = (sound == AmbientSound.LOFI_BEATS || sound == AmbientSound.DEEP_SPACE) && !isPro

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                if (isLocked) {
                                    onDismiss()
                                    onUpgradeToPro()
                                } else {
                                    onSoundSelect(sound)
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) VioletNeon.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, VioletNeon) else null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sound.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) VioletNeon else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = sound.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isLocked) {
                                Icon(Icons.Default.Lock, contentDescription = "Pro", tint = AmberWarning, modifier = Modifier.size(16.dp))
                            } else if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = "Selected", tint = VioletNeon, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = Color(0xFF00242B))
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}
