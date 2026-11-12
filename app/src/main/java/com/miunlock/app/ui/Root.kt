package com.miunlock.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.miunlock.app.BuildConfig
import com.miunlock.app.domain.AppIntent
import com.miunlock.app.data.DebugLog
import com.miunlock.app.domain.AppState

/** Экраны приложения. Стартовый — выбор режима. */
private enum class Screen { HOME, APPLY, UNLOCK }

/** Открывает ссылку в браузере. Нет браузера — молча ничего не делаем. */
internal fun openLink(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** Адрес репозитория: один на всё приложение, чтобы ссылки не расходились. */
internal const val REPO_URL = "https://github.com/Matvel007/MiUnlockApp"

/** Тема приложения на 4PDA: туда идут тестеры. */
internal const val FORUM_URL = "https://4pda.to/forum/index.php?showtopic=1124335"

/**
 * Точка входа навигации. Режимы независимы: у каждого своё состояние
 * и своё хранилище, переход между ними ничего не сбрасывает.
 */
@Composable
fun RootScreen(state: AppState, dispatch: (AppIntent) -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val context = LocalContext.current
    val language = LocalLanguage.current

    // Шапка лога должна говорить, какой режим открыт и на каком языке интерфейс:
    // без этого присланный лог не расшифровать.
    LaunchedEffect(screen, language) {
        DebugLog.setMode(
            when (screen) {
                Screen.HOME -> "home"
                Screen.APPLY -> "apply"
                Screen.UNLOCK -> "unlock"
            },
        )
        DebugLog.setLanguage(language)
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            version = BuildConfig.VERSION_NAME,
            onApply = { screen = Screen.APPLY },
            onUnlock = { screen = Screen.UNLOCK },
            onGitHub = { openLink(context, REPO_URL) },
            onForum = { openLink(context, FORUM_URL) },
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
