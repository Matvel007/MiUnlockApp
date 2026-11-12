package com.miunlock.app.data

import android.content.Context
import com.miunlock.app.unlock.UnlockStore

class AppContainer(context: Context) {
    val settingsStore = SettingsStore(context)
    val api = XiaomiApi()
    val timeSync = TimeSync()
    /** Сессия режима разблокировки — отдельно от сессии заявки. */
    val unlockStore = UnlockStore(context)
}
