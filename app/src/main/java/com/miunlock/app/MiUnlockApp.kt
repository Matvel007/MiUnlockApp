package com.miunlock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.miunlock.app.data.AppContainer
import com.miunlock.app.data.DebugLog
import com.miunlock.app.data.LogLevel
import com.miunlock.app.data.LogTag
import com.miunlock.app.service.UnlockService
import java.io.File

class MiUnlockApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createChannels()
        installCrashHandler()
        reportPreviousCrash()
    }

    /**
     * Ловит падения. Без этого тестер видит только «приложение закрылось»,
     * а причина пропадает вместе с процессом. Лог и стектрейс пишутся в файл
     * и попадут в журнал при следующем запуске.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = DebugLog.exportText() +
                    "\n--- crash ---\n${thread.name}\n" + error.stackTraceToString()
                crashFile().writeText(report)
            }.onFailure { Log.e("MiUnlockApp", "не удалось сохранить отчёт о падении", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Отчёт о прошлом падении переносится в журнал и удаляется с диска. */
    private fun reportPreviousCrash() {
        val file = crashFile()
        if (!file.exists()) return
        runCatching {
            val text = file.readText()
            file.delete()
            DebugLog.add(
                LogLevel.ERROR,
                LogTag.APP,
                "прошлый запуск завершился падением:\n${text.takeLast(4000)}",
            )
        }
    }

    private fun crashFile() = File(filesDir, "last_crash.txt")

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
