package com.miunlock.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.miunlock.app.MiUnlockApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MiUnlockApp
                if (app.container.settingsStore.shouldResume()) UnlockService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
