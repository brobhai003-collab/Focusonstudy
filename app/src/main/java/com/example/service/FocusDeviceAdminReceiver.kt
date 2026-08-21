package com.example.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class FocusDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        val isSessionActive = FocusTimerService.isSessionActive.value
        val isStrict = FocusTimerService.isStrictMode.value
        if (isSessionActive && isStrict) {
            return "🔒 STRICT MODE ENFORCED: Deactivation and uninstallation of Dedication are locked until your active focus timer completes!"
        }
        return "Warning: Disabling Device Admin will remove uninstallation and tamper protection."
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(
            context,
            "Dedication Strict Protection Activated! Uninstall shield is armed.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(
            context,
            "Dedication Device Protection Disabled.",
            Toast.LENGTH_SHORT
        ).show()
    }
}
