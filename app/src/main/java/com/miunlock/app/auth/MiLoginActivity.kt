package com.miunlock.app.auth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.miunlock.app.MiUnlockApp
import com.miunlock.app.data.DebugLog
import com.miunlock.app.domain.Credentials
import com.miunlock.app.service.QrLoginService
import com.miunlock.app.ui.theme.MiOrange
import com.miunlock.app.ui.theme.MiUnlockTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class MiLoginActivity : ComponentActivity() {
    private val auth by lazy { PythonMiAuth(this) }
    private var account by mutableStateOf("")
    private var password by mutableStateOf("")
    private var verificationCode by mutableStateOf("")
    private var status by mutableStateOf("Введите Xiaomi ID, email или телефон и пароль.")
    private var working by mutableStateOf(false)
    private var verificationMethods by mutableStateOf(emptyList<String>())
    private var selectedMethod by mutableStateOf<String?>(null)
    private var codeSent by mutableStateOf(false)
    private var language = "ru"
    private var qrMode by mutableStateOf(false)
    private var qrBitmap by mutableStateOf<Bitmap?>(null)
    private var qrStatus by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideStatusBars()
        language = savedInstanceState?.getString("language") ?: intent.getStringExtra("language") ?: "ru"
        account = savedInstanceState?.getString("account").orEmpty()
        status = savedInstanceState?.getString("status") ?: t("Введите Xiaomi ID, email или телефон и пароль.", "Enter Xiaomi ID, email or phone and password.")
        verificationMethods = savedInstanceState?.getStringArrayList("verificationMethods")?.toList().orEmpty()
        selectedMethod = savedInstanceState?.getString("selectedMethod")
        codeSent = savedInstanceState?.getBoolean("codeSent") ?: false
        setContent {
            MiUnlockTheme {
                BackHandler(enabled = qrMode) { cancelQr() }
                if (qrMode) {
                    QrLoginScreen(
                        bitmap = qrBitmap,
                        status = qrStatus,
                        working = working,
                        language = language,
                        onCancel = ::cancelQr,
                    )
                } else {
                    LoginScreen(
                        account = account,
                        password = password,
                        code = verificationCode,
                        status = status,
                        working = working,
                        codeSent = codeSent,
                        language = language,
                        onAccountChange = { account = it },
                        onPasswordChange = { password = it },
                        onCodeChange = { verificationCode = it },
                        onLogin = ::login,
                        onQr = ::startQrLogin,
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("account", account)
        outState.putString("status", status)
        outState.putStringArrayList("verificationMethods", ArrayList(verificationMethods))
        outState.putString("selectedMethod", selectedMethod)
        outState.putBoolean("codeSent", codeSent)
        outState.putString("language", language)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBars()
    }

    private fun hideStatusBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun login() {
        if (codeSent) {
            verifyCode()
            return
        }
        if (account.isBlank() || password.isBlank()) {
            status = t("Введите Xiaomi ID, email или телефон и пароль.", "Enter Xiaomi ID, email or phone and password.")
            return
        }
        lifecycleScope.launch {
            try {
                working = true
                status = t("Проверяю Xiaomi Account...", "Checking Xiaomi Account...")
                configureAuthProxy()
                clearSavedCredentials()
                val result = auth.login(account, password, SID, language)
                if (result.needsVerification) {
                    verificationMethods = result.verificationMethods
                    val method = result.verificationMethods.firstOrNull { it == "EM" }
                        ?: result.verificationMethods.first()
                    sendCode(method)
                } else {
                    finishLogin()
                }
            } catch (e: Exception) {
                status = friendlyError(t("Ошибка входа", "Sign in error"), e)
            } finally {
                working = false
            }
        }
    }

    private suspend fun sendCode(method: String) {
        selectedMethod = method
        status = t("Запрашиваю код Xiaomi...", "Requesting Xiaomi code...")
        auth.sendCode(method)
        codeSent = true
        val channel = if (method == "PH") t("по SMS", "by SMS") else t("на email", "by email")
        status = t("Код отправлен $channel. Введите его в поле под паролем и нажмите «Проверить код».", "Code sent $channel. Enter it below the password and tap Verify code.")
    }

    private fun verifyCode() {
        val method = selectedMethod ?: verificationMethods.singleOrNull()
        if (method == null || verificationCode.isBlank()) {
            status = t("Сначала введите код подтверждения.", "Enter the verification code first.")
            return
        }
        lifecycleScope.launch {
            try {
                working = true
                status = t("Подтверждаю код Xiaomi...", "Verifying Xiaomi code...")
                val result = auth.verifyCode(method, verificationCode, SID)
                if (result.needsVerification) {
                    verificationMethods = result.verificationMethods
                    codeSent = false
                    val nextMethod = result.verificationMethods.firstOrNull { it == "EM" }
                        ?: result.verificationMethods.first()
                    sendCode(nextMethod)
                } else {
                    finishLogin()
                }
            } catch (e: Exception) {
                status = friendlyError(t("Код не принят", "Code was rejected"), e)
            } finally {
                working = false
            }
        }
    }

    private suspend fun finishLogin() {
        status = t("Получаю Mi Community token...", "Getting Mi Community token...")
        val (token, deviceId) = auth.exchange(SID)
        DebugLog.add("Mi Community session exchanged")
        val store = (application as MiUnlockApp).container.settingsStore
        val current = store.current()
        store.save(current.copy(credentials = Credentials(
            serviceToken = token,
            deviceId = deviceId.ifBlank { "wb_" + UUID.randomUUID().toString().replace("-", "") },
            versionName = current.credentials.versionName,
            versionCode = current.credentials.versionCode,
        )))
        DebugLog.add("Mi Community session saved")
        password = ""
        Toast.makeText(this, t("Mi Community сессия получена", "Mi Community session obtained"), Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }

    private suspend fun clearSavedCredentials() {
        val store = (application as MiUnlockApp).container.settingsStore
        val current = store.current()
        store.save(current.copy(credentials = current.credentials.copy(serviceToken = "", deviceId = "")))
    }

    private suspend fun configureAuthProxy(): com.miunlock.app.domain.ProxySettings {
        val proxy = (application as MiUnlockApp).container.settingsStore.current().proxy
        auth.setProxy(proxy)
        DebugLog.add(if (proxy.isEnabled) "Авторизация Xiaomi через HTTP-прокси" else "Авторизация Xiaomi без прокси")
        return proxy
    }

    private fun startQrLogin() {
        lifecycleScope.launch {
            try {
                qrMode = true
                QrLoginService.start(
                    this@MiLoginActivity,
                    t("Вход по QR-коду", "QR sign-in"),
                    t("Ожидание входа Xiaomi по QR-коду", "Waiting for Xiaomi QR sign-in"),
                )
                val proxy = configureAuthProxy()
                val authData = auth.qrAuthData(QR_SID)
                DebugLog.add("QR serviceLogin cookies: ${authData.cookieNames.joinToString().ifBlank { "none" }}")
                while (qrMode) {
                    try {
                        working = true
                        qrStatus = t("Запрашиваю QR-код Xiaomi...", "Requesting Xiaomi QR code...")
                        val qrData = auth.qrLogin(authData.value)
                        val png = auth.qrPng(qrData.loginUrl)
                        qrBitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                        val timeoutSeconds = qrData.timeoutMs / 1_000
                        qrStatus = t(
                            "Отсканируйте QR-код через Xiaomi Account. Ожидаю подтверждение...",
                            "Scan the QR code with Xiaomi Account. Waiting for confirmation...",
                        ).trim()
                        DebugLog.add("QR-код Xiaomi получен; long-poll timeout $timeoutSeconds с")
                        DebugLog.add("QR session cookies: ${qrData.cookieNames.joinToString().ifBlank { "none" }}")
                        if (qrData.tips.isNotBlank()) DebugLog.add("QR tips: ${qrData.tips.take(120)}")
                        working = false
                        DebugLog.add("QR long-poll запущен")
                        val poll = auth.qrPoll(qrData.lp, qrData.timeoutMs, qrData.cookieHeader, proxy)
                        if (poll.scanned) {
                            if (!qrMode) return@launch
                            working = true
                            DebugLog.add("QR-вход подтвержден Xiaomi")
                            qrStatus = t("QR отсканирован! Получаю токен...", "QR scanned! Getting token...")
                            qrMode = false
                            DebugLog.add("QR: получаю Mi Community session")
                            finishLogin()
                            return@launch
                        }
                        if (poll.expired) {
                            DebugLog.add("QR long-poll timeout; запрашиваю новый QR")
                            continue
                        }
                    } catch (e: Exception) {
                        if (!qrMode) return@launch
                        DebugLog.add("QR long-poll ошибка: ${e.message ?: e::class.simpleName ?: "ошибка"}")
                        qrStatus = friendlyError(t("Ошибка QR", "QR error"), e)
                        qrMode = false
                        return@launch
                    }
                }
            } catch (e: Exception) {
                qrMode = false
                qrStatus = friendlyError(t("Ошибка QR", "QR error"), e)
            } finally {
                working = false
                if (!qrMode) QrLoginService.stop(this@MiLoginActivity)
            }
        }
    }

    private fun cancelQr() {
        qrMode = false
        qrBitmap = null
        qrStatus = ""
        QrLoginService.stop(this)
    }

    private fun friendlyError(prefix: String, error: Exception): String {
        val message = error.message.orEmpty()
        return if (message.contains("Unable to resolve host", ignoreCase = true)) {
            if (language == "en") "$prefix: no DNS connection to account.xiaomi.com. Check the internet and retry."
            else "$prefix: нет DNS-соединения с account.xiaomi.com. Проверьте интернет и повторите."
        } else "$prefix: $message"
    }

    private fun t(ru: String, en: String) = if (language == "en") en else ru

    private companion object {
        const val SID = "18n_bbs_global"
        const val QR_SID = "passport"
    }
}

@Composable
private fun LoginScreen(
    account: String,
    password: String,
    code: String,
    status: String,
    working: Boolean,
    codeSent: Boolean,
    language: String,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onLogin: () -> Unit,
    onQr: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PaperBackground()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 48.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                LoginCard {
                    SectionTitle("01", if (language == "en") "ACCOUNT DETAILS" else "ДАННЫЕ АККАУНТА")
                    Text(if (language == "en") "Use Xiaomi ID, email or phone. The password stays in memory only until this window is closed." else "Используйте Xiaomi ID, email или номер телефона. Пароль хранится только в памяти до закрытия окна.", color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(account, onAccountChange, label = { Text(if (language == "en") "Xiaomi ID / email / phone" else "Xiaomi ID / email / телефон") }, singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(password, onPasswordChange, label = { Text(if (language == "en") "Password" else "Пароль") }, singleLine = true, enabled = !working, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    if (codeSent) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(code, onCodeChange, label = { Text(if (language == "en") "Verification code" else "Код подтверждения") }, singleLine = true, enabled = !working, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onLogin, Modifier.fillMaxWidth(), enabled = !working, colors = ButtonDefaults.buttonColors(containerColor = MiOrange), shape = RoundedCornerShape(14.dp)) {
                        Text(
                            if (working) {
                                if (language == "en") "PROCESSING..." else "ОБРАБОТКА..."
                            } else if (codeSent) {
                                if (language == "en") "VERIFY CODE" else "ПРОВЕРИТЬ КОД"
                            } else if (language == "en") "SIGN IN" else "ВОЙТИ"
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onQr, Modifier.fillMaxWidth(), enabled = !working, shape = RoundedCornerShape(14.dp)) {
                        Text(if (language == "en") "SIGN IN VIA QR" else "ВОЙТИ ЧЕРЕЗ QR")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { OutlinedButton(onClose, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(if (language == "en") "CLOSE" else "ЗАКРЫТЬ") } }
        }
    }
}

@Composable
private fun QrLoginScreen(
    bitmap: Bitmap?,
    status: String,
    working: Boolean,
    language: String,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PaperBackground()
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            LoginCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    SectionTitle("01", if (language == "en") "QR LOGIN" else "QR-ВХОД")
                    bitmap?.let {
                        Image(it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(280.dp))
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onCancel, Modifier.fillMaxWidth(), enabled = !working, shape = RoundedCornerShape(14.dp)) {
                Text(if (language == "en") "CANCEL" else "ОТМЕНА")
            }
        }
    }
}

@Composable
private fun LoginCard(container: Color? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.4.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f), RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = container ?: MaterialTheme.colorScheme.surface),
    ) { Column(Modifier.padding(17.dp), content = { content() }) }
}

@Composable
private fun SectionTitle(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Box(Modifier.size(34.dp).background(MiOrange, CircleShape), contentAlignment = Alignment.Center) { Text(number, color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp) }
        Spacer(Modifier.width(10.dp))
        Text(title, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun PaperBackground() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}
