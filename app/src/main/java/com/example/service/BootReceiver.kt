package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.FocusLockApp
import com.example.data.model.AmbientSound
import com.example.data.model.FocusMode
import com.example.data.model.FocusSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" || 
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            try {
                val prefsRepo = FocusLockApp.instance.preferencesRepository
                val savedSession = prefsRepo.getActiveSession() ?: return
                val now = System.currentTimeMillis()

                if (savedSession.targetEndTimeMillis > now) {
                    val remainingSeconds = ((savedSession.targetEndTimeMillis - now) / 1000L).coerceAtLeast(10L)
                    val mode = try { FocusMode.valueOf(savedSession.mode) } catch (e: Exception) { FocusMode.TIMER }
                    val sound = try { AmbientSound.valueOf(savedSession.sound) } catch (e: Exception) { AmbientSound.NONE }

                    Log.d("BootReceiver", "Resuming strict focus session on boot: $remainingSeconds seconds left.")
                    FocusTimerService.start(
                        context = context,
                        mode = mode,
                        durationSeconds = remainingSeconds,
                        label = savedSession.label,
                        isStrict = savedSession.isStrict,
                        sound = sound
                    )
                } else {
                    // Session target completed while phone was off
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val duration = ((savedSession.targetEndTimeMillis - savedSession.startTimeMillis) / 1000L).coerceAtLeast(60L)
                            FocusLockApp.instance.focusRepository.recordSession(
                                FocusSessionEntity(
                                    label = savedSession.label,
                                    mode = savedSession.mode,
                                    durationSeconds = duration,
                                    isStrict = savedSession.isStrict,
                                    isSuccessful = true
                                )
                            )
                            prefsRepo.recordSessionSuccess()
                            prefsRepo.clearActiveSession()
                        } catch (e: Exception) {
                            Log.e("BootReceiver", "Error saving completed session: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error recovering session on boot: ${e.message}", e)
            }
        }
    }
}
