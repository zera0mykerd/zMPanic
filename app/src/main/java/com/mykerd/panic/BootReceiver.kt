package com.mykerd.panic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val pendingResult = goAsync()
            Thread {
                try {
                    Log.d("zMPanicBoot", "Checking state after boot...")
                    val stealthPrefs = SecureConfig.getStealthPrefs(context)
                    val state = stealthPrefs.getString("stealth_state", "NORMAL")
                    
                    if (state == "PENDING_ACTIVATION" || state == "CONFIRMING_ACTIVATION" || state == "ACTIVE") {
                        Log.i("zMPanicBoot", "Resuming service after reboot in state: $state")
                        val serviceIntent = Intent(context, PanicService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("zMPanicBoot", "Error in BootReceiver: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }.start()
        }
    }
}
