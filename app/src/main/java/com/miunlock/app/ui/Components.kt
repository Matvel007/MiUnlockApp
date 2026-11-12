package com.miunlock.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miunlock.app.R
import com.miunlock.app.data.DebugLog
import com.miunlock.app.ui.theme.MiOrange
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Общие элементы оформления. Вынесены из MainActivity, чтобы не дублировать в новых экранах. */

@Composable
fun PaperBackground() {
    val color = MaterialTheme.colorScheme.outline.copy(alpha = .07f)
    Canvas(Modifier.fillMaxSize()) {
        var y = 28f
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
            y += 34f
        }
    }
}

@Composable
fun SketchCard(container: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().border(
            1.4.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = .55f),
            RoundedCornerShape(22.dp),
        ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = container ?: MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(17.dp), content = content)
    }
}

@Composable
fun SectionTitle(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Box(Modifier.size(34.dp).background(MiOrange, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

/** Живая волнистая линия — разделитель. */
@Composable
fun DoodleDivider(modifier: Modifier = Modifier.width(20.dp), height: Int = 64) {
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = .55f)
    val transition = rememberInfiniteTransition(label = "divider")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing)),
        label = "wavePhase",
    )
    Canvas(modifier.height(height.dp)) {
        val x = size.width / 2
        val path = Path().apply {
            moveTo(x, 0f)
            var y = 0f
            while (y < size.height) {
                lineTo(x + kotlin.math.sin((y / 9f - phase).toDouble()).toFloat() * 2.3f, y)
                y += 2f
            }
        }
        drawPath(path, outlineColor, style = Stroke(2f))
    }
}

/** Горизонтальный вариант того же разделителя. */
@Composable
fun DoodleDividerHorizontal(height: Int = 18) {
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = .55f)
    val transition = rememberInfiniteTransition(label = "dividerH")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing)),
        label = "wavePhaseH",
    )
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        val y = size.height / 2
        val path = Path().apply {
            moveTo(0f, y)
            var x = 0f
            while (x < size.width) {
                lineTo(x, y + kotlin.math.sin((x / 9f - phase).toDouble()).toFloat() * 2.3f)
                x += 2f
            }
        }
        drawPath(path, outlineColor, style = Stroke(2f))
    }
}

@Composable
fun ConsoleIcon(color: Color) {
    Canvas(Modifier.size(27.dp)) {
        drawLine(color, Offset(size.width * .25f, size.height * .25f), Offset(size.width * .10f, size.height * .50f), 3.8f, StrokeCap.Round)
        drawLine(color, Offset(size.width * .10f, size.height * .50f), Offset(size.width * .25f, size.height * .75f), 3.8f, StrokeCap.Round)
        drawLine(color, Offset(size.width * .46f, size.height * .78f), Offset(size.width * .66f, size.height * .22f), 3.8f, StrokeCap.Round)
        drawLine(color, Offset(size.width * .76f, size.height * .76f), Offset(size.width * .91f, size.height * .76f), 3.8f, StrokeCap.Round)
    }
}

/** Значок GitHub с мягким «дыханием», как в шапке режима заявки. */
@Composable
fun GithubButton(onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "github")
    val scale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "githubScale",
    )
    IconButton(onClick = onClick) {
        Icon(
            painterResource(R.drawable.ic_github),
            contentDescription = "GitHub",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(29.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

/** Стрелка «назад» для заголовков экранов. */
@Composable
fun BackArrow() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        drawLine(color, Offset(size.width * .62f, size.height * .22f), Offset(size.width * .32f, size.height * .50f), 3.2f, StrokeCap.Round)
        drawLine(color, Offset(size.width * .32f, size.height * .50f), Offset(size.width * .62f, size.height * .78f), 3.2f, StrokeCap.Round)
    }
}

/** Шестерёнка настроек: зубцы на ободе плюс отверстие в ступице. */
@Composable
fun GearIcon() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val ring = size.width * 0.28f
        val toothW = size.width * 0.17f
        val toothH = size.height * 0.15f
        repeat(8) { i ->
            rotate(i * 45f, c) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(c.x - toothW / 2, c.y - ring - toothH / 2),
                    size = Size(toothW, toothH),
                    cornerRadius = CornerRadius(toothW / 2.6f),
                )
            }
        }
        drawCircle(color, radius = ring, center = c, style = Stroke(3.2f))
        drawCircle(color, radius = size.width * 0.11f, center = c, style = Stroke(2.4f))
    }
}

@Composable
fun ConsoleScreen(onBack: () -> Unit) {
    val events by DebugLog.events.collectAsState()
    val clipboard = LocalClipboardManager.current
    val language = LocalLanguage.current
    Box(Modifier.fillMaxSize()) {
        PaperBackground()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Консоль", "Console"), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { DebugLog.clear() }) { Text(tr("ОЧИСТИТЬ", "CLEAR")) }
                    TextButton(onClick = onBack) { Text(tr("ГОТОВО", "DONE")) }
                }
            }
            if (events.isEmpty()) {
                item { Text(tr("Событий пока нет", "No events yet"), color = MaterialTheme.colorScheme.secondary) }
            } else {
                items(events.reversed()) { event ->
                    val message = localizedDebugMessage(event.message, language)
                    SketchCard(container = Color(0xFF1E1E1E)) {
                        Text(
                            event.time.atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                            color = MiOrange,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Text(
                            message,
                            color = Color(0xFFF5F5F5),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { clipboard.setText(AnnotatedString(message)) },
                        )
                    }
                }
            }
        }
    }
}

fun localizedDebugMessage(message: String, language: String): String {
    if (language != "en") return message
    return when {
        message == "Журнал очищен" -> "Log cleared"
        message == "Запуск проверки аккаунта" -> "Account check started"
        message == "Запуск фоновой подачи заявки" -> "Background application started"
        message == "Проверка HTTP прокси" -> "HTTP proxy check started"
        message == "Прокси подключён" -> "Proxy connected"
        message == "Фоновая задача отменена" -> "Background task cancelled"
        message.startsWith("Ошибка прокси: ") -> "Proxy error: ${message.removePrefix("Ошибка прокси: ")}"
        message.startsWith("Ошибка проверки: ") -> "Check error: ${message.removePrefix("Ошибка проверки: ")}"
        message == "Xiaomi state request через HTTP proxy" -> "Xiaomi state request through HTTP proxy"
        message == "Xiaomi state request через без прокси" -> "Xiaomi state request without proxy"
        message.startsWith("Смещение: ") -> message
            .replace("Смещение", "Advance")
            .replace("быстрая половина RTT / 2 + запас", "fastest half RTT / 2 + margin")
        message.startsWith("Проверка завершена: ") -> message
            .replace("Проверка завершена", "Check complete")
            .replace("смещение", "advance")
            .replace("код", "code")
        message.startsWith("Сервис: ") -> message
            .replace("Проверяю состояние аккаунта…", "Checking account state…")
            .replace("Ожидаю окно подачи", "Waiting for application window")
            .replace("Прогреваю соединение…", "Warming up connection…")
            .replace("Отправляю заявку…", "Submitting application…")
            .replace("Сетевая попытка", "Network attempt")
            .replace("ошибка выполнения", "execution error")
            .replace("Остановлено пользователем", "Stopped by user")
            .replace("Остановлено", "Stopped")
        else -> message
    }
}
