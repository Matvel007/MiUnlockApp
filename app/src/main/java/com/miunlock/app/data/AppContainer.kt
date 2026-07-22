package com.miunlock.app.data

import android.content.Context

class AppContainer(context: Context) {
    val settingsStore = SettingsStore(context)
    val api = XiaomiApi()
    val timeSync = TimeSync()
}
