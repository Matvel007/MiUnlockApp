package com.miunlock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miunlock.app.ui.theme.MiOrange

/**
 * Стартовый экран: выбор режима. Открывается всегда при запуске.
 * Оформление то же, что в меню подачи заявки: те же карточки, кнопки и отступы.
 */
@Composable
fun HomeScreen(
    version: String,
    onApply: () -> Unit,
    onUnlock: () -> Unit,
    onGitHub: () -> Unit,
    onForum: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        PaperBackground()
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MiUnlockApp", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.weight(1f))
                ForumButton(onForum)
                GithubButton(onGitHub)
            }
            Spacer(Modifier.weight(0.22f))

            SketchCard {
                SectionTitle("01", tr("ПОДАЧА ЗАЯВКИ", "UNLOCK APPLICATION"))
                Text(
                    tr(
                        "Вход в Mi Account, проверка аккаунта и отправка заявки на разблокировку.",
                        "Mi Account sign-in, account check and sending the unlock application.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onApply,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MiOrange),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(tr("ПОДАТЬ ЗАЯВКУ", "SUBMIT APPLICATION")) }
            }

            Spacer(Modifier.height(16.dp))

            SketchCard {
                SectionTitle("02", tr("РАЗБЛОКИРОВКА ЗАГРУЗЧИКА", "BOOTLOADER UNLOCK"))
                Text(
                    tr(
                        "Разблокируйте телефон прямо с этого телефона: подключите его по OTG-кабелю. Компьютер не нужен.",
                        "Unlock a phone right from this phone: connect it with an OTG cable. No computer needed.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onUnlock,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(tr("ПЕРЕЙТИ К РАЗБЛОКИРОВКЕ", "GO TO UNLOCK")) }
            }

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
            Spacer(Modifier.height(6.dp))
        }
    }
}
