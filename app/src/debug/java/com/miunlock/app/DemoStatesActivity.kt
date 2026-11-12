package com.miunlock.app

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.ui.Bilingual
import com.miunlock.app.ui.LocalLanguage
import com.miunlock.app.ui.UnlockScreenContent
import com.miunlock.app.ui.UnlockUiState
import com.miunlock.app.ui.theme.MiOrange
import com.miunlock.app.ui.theme.MiUnlockTheme
import com.miunlock.app.unlock.UnlockSession
import com.miunlock.app.unlock.UnlockStep

/**
 * Стенд состояний экрана разблокировки. Только debug-сборка: в release не попадает.
 * Нужен, чтобы увидеть каждый экран процесса заранее — без живого телефона.
 */
class DemoStatesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Режим проверки подписки на сессию: пишем сессию в настоящее хранилище
        // и закрываемся. Экран разблокировки должен обновиться сам, без перезахода.
        if (intent.getBooleanExtra("writeSession", false)) {
            kotlinx.coroutines.MainScope().launch {
                com.miunlock.app.unlock.UnlockStore(this@DemoStatesActivity).saveSession(
                    UnlockSession(
                        serviceToken = "debug-token",
                        ssecurity = "c2Vzc2VjdXJpdHkxMjM0NTY=",
                        userId = "9876543210",
                        deviceId = "wb_debug",
                        cookies = mapOf("cUserId" to "9876543210"),
                        sid = "unlockApi",
                    ),
                )
                finish()
            }
            return
        }
        setContent {
            // Номер состояния можно задать при запуске: adb ... --ei state 6
            var index by remember { mutableStateOf(intent.getIntExtra("state", 0).coerceIn(0, demos().size - 1)) }
            var language by remember { mutableStateOf("ru") }
            val demo = demos()[index]
            MiUnlockTheme {
                CompositionLocalProvider(LocalLanguage provides language) {
                    Box(Modifier.fillMaxSize()) {
                        UnlockScreenContent(
                            state = demo.state,
                            language = language,
                            onBack = {},
                            onLogin = {},
                            onScan = {},
                            onUnlock = {},
                            onAnswerConfirm = {},
                            onZone = {},
                            onProxy = {},
                            onLanguage = { language = it },
                            onLogout = {},
                            onGitHub = {},
                        )
                        Box(Modifier.align(Alignment.BottomCenter)) {
                            DemoBar(
                                title = demo.title,
                                index = index,
                                total = demos().size,
                                language = language,
                                onPrev = { index = (index - 1 + demos().size) % demos().size },
                                onNext = { index = (index + 1) % demos().size },
                                onLanguage = { language = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class Demo(val title: String, val state: UnlockUiState)

private val loggedIn = UnlockSession(
    serviceToken = "token",
    ssecurity = "ssecurity",
    userId = "1234567890",
    deviceId = "wb_0123456789",
    cookies = mapOf("cUserId" to "1234567890"),
    sid = "unlockApi",
)

private fun demos(): List<Demo> = listOf(
    Demo("01 не вошёл в аккаунт", UnlockUiState()),
    Demo(
        "02 вошёл, телефона нет",
        UnlockUiState(session = loggedIn, status = Bilingual("", "")),
    ),
    Demo(
        "03 устройство найдено",
        UnlockUiState(
            session = loggedIn,
            deviceLabel = "Xiaomi Redmi Note 8 Pro",
            product = "begonia",
            status = Bilingual("Устройство подключено", "Device connected"),
        ),
    ),
    Demo(
        "04 идёт: запрос nonce",
        UnlockUiState(
            session = loggedIn,
            deviceLabel = "Xiaomi Redmi Note 8 Pro",
            product = "begonia",
            running = true,
            step = UnlockStep.NONCE,
            status = Bilingual("Запрашиваю nonce", "Requesting a nonce"),
        ),
    ),
    Demo(
        "05 идёт: подпись в загрузчик",
        UnlockUiState(
            session = loggedIn,
            deviceLabel = "Xiaomi Redmi Note 8 Pro",
            product = "begonia",
            running = true,
            step = UnlockStep.STAGE,
            status = Bilingual("Передаю подпись в загрузчик", "Uploading the signature to the bootloader"),
        ),
    ),
    Demo(
        "06 подтверждение: данные сотрутся",
        UnlockUiState(
            session = loggedIn,
            deviceLabel = "Xiaomi Redmi Note 8 Pro",
            product = "begonia",
            confirm = UnlockUiState.ClearConfirm(
                cleanOrNot = true,
                notice = "После разблокировки телефон загрузится как новый: все фото, контакты и приложения будут удалены.",
            ),
        ),
    ),
    Demo(
        "07 подтверждение: данные сохранятся",
        UnlockUiState(
            session = loggedIn,
            deviceLabel = "Xiaomi Redmi Note 8 Pro",
            product = "begonia",
            confirm = UnlockUiState.ClearConfirm(cleanOrNot = false, notice = ""),
        ),
    ),
    Demo(
        "08 ждать N часов",
        UnlockUiState(
            session = loggedIn,
            deviceLabel = "Xiaomi Redmi Note 8 Pro",
            product = "begonia",
            error = "You must wait 71 hours before unlocking this device.",
            waitHours = 71,
        ),
    ),
    Demo(
        "09 ошибка с кодом",
        UnlockUiState(
            session = loggedIn,
            deviceLabel = "Xiaomi Redmi Note 8 Pro",
            product = "begonia",
            error = "Invalid nonce param",
            errorCode = 10005,
        ),
    ),
    Demo(
        "10 успех",
        UnlockUiState(
            session = loggedIn,
            deviceLabel = "Xiaomi Redmi Note 8 Pro",
            product = "begonia",
            step = UnlockStep.DONE,
            status = Bilingual("Разблокировано", "Unlocked"),
        ),
    ),
    Demo(
        "11 успех (EN)",
        UnlockUiState(
            session = loggedIn,
            deviceLabel = "Xiaomi Redmi Note 8 Pro",
            product = "begonia",
            step = UnlockStep.DONE,
            status = Bilingual("Разблокировано", "Unlocked"),
        ),
    ),
)

@Composable
private fun DemoBar(
    title: String,
    index: Int,
    total: Int,
    language: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLanguage: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xEE000000))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onPrev, shape = RoundedCornerShape(10.dp)) { Text("◀") }
        Text(
            "$title  (${index + 1}/$total)",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onLanguage(if (language == "ru") "en" else "ru") },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MiOrange),
        ) { Text(if (language == "ru") "EN" else "RU", fontSize = 12.sp) }
        Button(onClick = onNext, shape = RoundedCornerShape(10.dp)) { Text("▶") }
    }
}
