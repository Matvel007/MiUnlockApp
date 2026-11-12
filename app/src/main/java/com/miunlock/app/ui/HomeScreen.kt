package com.miunlock.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miunlock.app.ui.theme.MiOrange
import com.miunlock.app.ui.theme.MiOrangeSoft

/**
 * Стартовый экран: выбор режима. Открывается всегда при запуске.
 * Состояние режимов живёт в своих хранилищах, поэтому переход сюда ничего не сбрасывает.
 */
@Composable
fun HomeScreen(
    version: String,
    onApply: () -> Unit,
    onUnlock: () -> Unit,
    onGitHub: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PaperBackground()
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("MiUnlockApp", "MiUnlockApp"), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.weight(1f))
                GithubButton(onGitHub)
            }

            Spacer(Modifier.weight(0.45f))
            Text(
                tr("ЧТО ДЕЛАЕМ", "WHAT TO DO"),
                color = MiOrange,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(12.dp))

            ModeCard(
                title = tr("Подача заявки", "Submit application"),
                subtitle = tr(
                    "Вход в Mi Account, проверка аккаунта и отправка заявки на разблокировку",
                    "Mi Account sign-in, account check and application submission",
                ),
                icon = ModeIcon.Pencil,
                onClick = onApply,
            )
            Spacer(Modifier.height(14.dp))
            ModeCard(
                title = tr("Разблокировка загрузчика", "Bootloader unlock"),
                subtitle = tr(
                    "Подключите телефон по OTG: чтение токена, подпись Xiaomi и разблокировка",
                    "Connect a phone over OTG: read token, get Xiaomi signature, unlock",
                ),
                icon = ModeIcon.Key,
                onClick = onUnlock,
            )

            Spacer(Modifier.weight(1f))
            DoodleDividerHorizontal(height = 16)
            Spacer(Modifier.height(6.dp))
            Text(
                tr("версия $version", "version $version"),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private enum class ModeIcon { Pencil, Key }

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    icon: ModeIcon,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(54.dp).background(MiOrangeSoft, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when (icon) {
                    ModeIcon.Pencil -> PencilGlyph()
                    ModeIcon.Key -> KeyGlyph()
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

@Composable
private fun PencilGlyph() {
    Canvas(Modifier.size(30.dp)) {
        val ink = MiOrange
        drawLine(ink, Offset(size.width * .22f, size.height * .80f), Offset(size.width * .78f, size.height * .24f), 5.5f, StrokeCap.Round)
        val tip = Path().apply {
            moveTo(size.width * .14f, size.height * .90f)
            lineTo(size.width * .26f, size.height * .66f)
            lineTo(size.width * .38f, size.height * .78f)
            close()
        }
        drawPath(tip, ink)
        drawLine(Color(0xFFFFD3B5), Offset(size.width * .70f, size.height * .17f), Offset(size.width * .85f, size.height * .32f), 6.5f, StrokeCap.Round)
    }
}

@Composable
private fun KeyGlyph() {
    Canvas(Modifier.size(30.dp)) {
        val ink = MiOrange
        drawCircle(ink, radius = size.width * .17f, center = Offset(size.width * .27f, size.height * .50f), style = Stroke(4.2f))
        drawLine(ink, Offset(size.width * .44f, size.height * .50f), Offset(size.width * .88f, size.height * .50f), 4.2f, StrokeCap.Round)
        drawLine(ink, Offset(size.width * .70f, size.height * .50f), Offset(size.width * .70f, size.height * .70f), 4.2f, StrokeCap.Round)
        drawLine(ink, Offset(size.width * .84f, size.height * .50f), Offset(size.width * .84f, size.height * .66f), 4.2f, StrokeCap.Round)
    }
}

@Composable
private fun Chevron() {
    val color = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(22.dp)) {
        drawLine(color, Offset(size.width * .35f, size.height * .22f), Offset(size.width * .68f, size.height * .50f), 3.2f, StrokeCap.Round)
        drawLine(color, Offset(size.width * .68f, size.height * .50f), Offset(size.width * .35f, size.height * .78f), 3.2f, StrokeCap.Round)
    }
}
