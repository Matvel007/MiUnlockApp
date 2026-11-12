package com.miunlock.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.miunlock.app.BuildConfig
import com.miunlock.app.domain.AppIntent
import com.miunlock.app.domain.AppState

/** Экраны приложения. Стартовый — выбор режима. */
private enum class Screen { HOME, APPLY, UNLOCK }

private const val REPO_URL = "https://github.com/Matvel007/MiUnlockApp"

/**
 * Точка входа навигации. Режимы независимы: у каждого своё состояние
 * и своё хранилище, переход между ними ничего не сбрасывает.
 */
@Composable
fun RootScreen(state: AppState, dispatch: (AppIntent) -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val context = LocalContext.current

    when (screen) {
        Screen.HOME -> HomeScreen(
            version = BuildConfig.VERSION_NAME,
            onApply = { screen = Screen.APPLY },
            onUnlock = { screen = Screen.UNLOCK },
            onGitHub = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                }
            },
        )
        Screen.APPLY -> MiUnlockScreen(
            state = state,
            dispatch = dispatch,
            onHome = { screen = Screen.HOME },
        )
        Screen.UNLOCK -> UnlockScreen(
            onBack = { screen = Screen.HOME },
            onLanguage = { dispatch(AppIntent.SetLanguage(it)) },
        )
    }
}
