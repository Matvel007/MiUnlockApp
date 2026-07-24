package com.miunlock.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miunlock.app.R

class QrLoginService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                intent?.getStringExtra(EXTRA_CHANNEL_NAME) ?: "QR sign-in",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("MiUnlockApp")
                .setContentText(intent?.getStringExtra(EXTRA_TEXT) ?: "Waiting for Xiaomi QR sign-in")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "qr_login"
        private const val NOTIFICATION_ID = 6903
        private const val EXTRA_CHANNEL_NAME = "channel_name"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, channelName: String, text: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, QrLoginService::class.java)
                    .putExtra(EXTRA_CHANNEL_NAME, channelName)
                    .putExtra(EXTRA_TEXT, text),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QrLoginService::class.java))
        }
    }
}
