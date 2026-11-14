package com.miunlock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miunlock.app.ui.theme.MiOrange

/**
 * Третий блок главного экрана — благодарность тем, кто помогал.
 *
 * Свёрнут по умолчанию: имена нужны не каждый раз, а место на экране
 * занимают. Кнопка раскрывает список и сворачивает его обратно.
 * Порядок имён — по вкладу, места не нумеруем.
 */
@Composable
private fun ThanksCard() {
    // Переживает поворот экрана: раскрытый список не должен схлопываться.
    var open by rememberSaveable { mutableStateOf(false) }
    SketchCard {
        SectionTitle("03", tr("ОТДЕЛЬНАЯ БЛАГОДАРНОСТЬ", "SPECIAL THANKS"))
        Text(
            tr(
                "Приложению помогли эти люди.",
                "These people helped the app happen.",
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                if (open) {
                    tr("СКРЫТЬ", "HIDE")
                } else {
                    tr("ПОКАЗАТЬ СПИСОК", "SHOW THE LIST")
                },
            )
        }
        if (open) {
            Spacer(Modifier.height(12.dp))
            // Высота ограничена: список растёт вниз только до трёх строк,
            // дальше прокручивается. Так раскрытие не выдавливает низ экрана.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = THANKS_LIST_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                thanksRows().forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { name ->
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp, horizontal = 1.dp),
                            )
                        }
                        // Добиваем строку пустыми ячейками: иначе в последнем
                        // ряду имена растягиваются на всю ширину и колонки съезжают.
                        repeat(THANKS_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Имена по три в строку. Столбцы ничем не разделяем — только отступы. */
private fun thanksRows(): List<List<String>> = THANKS.chunked(THANKS_COLUMNS)

private const val THANKS_COLUMNS = 3

/** Три строки списка: больше на этом экране не нужно, остальное — прокруткой. */
private val THANKS_LIST_MAX_HEIGHT = 132.dp

/**
 * Кто помогал. Порядок — по величине вклада, он и есть единственная
 * иерархия: места не показываем, чтобы не расставлять людей по номерам.
 */
private val THANKS = listOf(
    "san4ezzer",
    "BarsOk",
    "ded181966",
    "Adizbey",
    "mrmanson",
    "Tatarin76",
)

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

            // Шапка и низ закреплены, середина занимает остаток высоты.
            // Пока карточки влезают — они по центру, как раньше; когда нет
            // (раскрытый список на маленьком экране) — прокручиваются.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val area = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = area),
                    verticalArrangement = Arrangement.Center,
                ) {
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

                    Spacer(Modifier.height(16.dp))

                    ThanksCard()
                }
            }

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
