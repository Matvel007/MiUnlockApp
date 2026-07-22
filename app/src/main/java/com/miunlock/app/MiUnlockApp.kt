package com.miunlock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.miunlock.app.data.AppContainer
import com.miunlock.app.service.UnlockService

class MiUnlockApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    UnlockService.STATUS_CHANNEL,
                    getString(R.string.channel_status_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = getString(R.string.channel_status_desc) }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    UnlockService.RESULT_CHANNEL,
                    getString(R.string.channel_result_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = getString(R.string.channel_result_desc) }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    UnlockService.STATUS_INDICATOR_CHANNEL,
                    getString(R.string.channel_status_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = getString(R.string.channel_status_desc)
                    setSound(null, null)
                }
            )
        }
    }
}
