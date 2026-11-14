package com.miunlock.app.unlock

import com.miunlock.app.data.DebugLog
import com.miunlock.app.data.LogLevel
import com.miunlock.app.data.LogTag
import com.miunlock.app.data.XiaomiApi
import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.StateResult

/**
 * Итог проверки аккаунта.
 *
 * Порядок источников важен: Mi Community (`bbs`) отвечает про заявку, но
 * бывает, что при выданном разрешении он показывает «заявки нет». Поэтому
 * сначала спрашивается unlock-сервер по токену телефона — он и разрешение
 * подтверждает, и остаток ожидания называет, — а `bbs` остаётся запасным.
 */
data class AccountStatus(
    /** Разрешение есть: заявка одобрена. */
    val granted: Boolean = false,
    /** Остаток обязательного ожидания в часах, если сервер его назвал. */
    val waitHours: Int? = null,
    /** Срок из ответа Mi Community, если он есть. */
    val deadline: String = "",
    /** Откуда взят итог: `token` или `bbs`. Нужно для журнала. */
    val source: String = "",
    /** Заявку можно подавать: разрешения нет, но и запрета тоже. */
    val canApply: Boolean = false,
    /** Готовый текст для экрана. */
    val message: String = "",
    /** Проверка не удалась: сеть, сессия, сервер. Это не «нет разрешения». */
    val failed: Boolean = false,
)

/**
 * Проверка заявки по всем доступным источникам.
 *
 * Телефон нужен только чтобы один раз прочитать токен; дальше проверка
 * работает по сохранённым данным, без fastboot.
 *
 * Источники передаются функциями: так порядок и правила можно проверить
 * тестом, не поднимая сеть.
 */
class AccountStatusChecker(
    private val bbsState: suspend (Credentials, ProxySettings) -> StateResult,
    private val byToken: suspend (UnlockSession, SavedDeviceToken) -> UnlockOutcome,
) {

    companion object {
        fun create(api: XiaomiApi, unlockApi: UnlockApi): AccountStatusChecker {
            val statusCheck = StatusCheck(unlockApi)
            return AccountStatusChecker(
                bbsState = { credentials, proxy -> api.checkState(credentials, proxy) },
                byToken = { session, saved -> statusCheck.byToken(session, saved) },
            )
        }
    }

    suspend fun check(
        credentials: Credentials,
        proxy: ProxySettings,
        session: UnlockSession?,
        saved: SavedDeviceToken?,
    ): AccountStatus {
        var transportFailed = false
        // 1. Unlock-сервер по токену: единственный источник, который знает часы.
        if (session != null && session.isValid && saved != null) {
            val attempt = tokenStatus(session, saved)
            if (attempt.status != null) return attempt.status
            transportFailed = attempt.transportFailed
        } else {
            DebugLog.add(
                LogLevel.INFO, LogTag.API,
                "проверка по токену пропущена: " +
                    when {
                        session == null || !session.isValid -> "нет сессии разблокировки"
                        else -> "телефон ещё не подключали"
                    },
            )
        }

        // 2. Mi Community: запасной источник, если токена нет или он не ответил.
        return bbsStatus(credentials, proxy, transportFailed)
    }

    /** Итог попытки по токену: либо ответ, либо причина, почему ответа нет. */
    private class TokenAttempt(
        val status: AccountStatus?,
        /** Сервер не ответил вовсе: сеть, DNS, таймаут. Это не вердикт аккаунта. */
        val transportFailed: Boolean = false,
    )

    /**
     * Статус по токену телефона. `status = null` — источник ничего не сказал,
     * и решать должен следующий.
     */
    private suspend fun tokenStatus(session: UnlockSession, saved: SavedDeviceToken): TokenAttempt {
        val outcome = byToken(session, saved)
        return when (outcome) {
            is UnlockOutcome.Success -> TokenAttempt(
                AccountStatus(
                    granted = true,
                    source = "token",
                    message = "🎉 РАЗРЕШЕНИЕ ЕСТЬ — можно разблокировать",
                ),
            )
            is UnlockOutcome.Failure -> when {
                // 20036: разрешение есть, идёт обязательное ожидание.
                outcome.waitHours != null -> TokenAttempt(
                    AccountStatus(
                        granted = true,
                        waitHours = outcome.waitHours,
                        source = "token",
                        message = "⏳ Разрешение есть, осталось ${outcome.waitHours} ч",
                    ),
                )
                // «Не привязано» / «не тот дата-центр» не отменяют одобрения:
                // решает Mi Community.
                outcome.code in UnlockWorkflow.ZONE_CODES -> TokenAttempt(null)
                // Кода нет — сервер не ответил вовсе. Это не «нет разрешения».
                outcome.code == null -> {
                    DebugLog.add(
                        LogLevel.INFO, LogTag.API,
                        "проверка по токену без ответа сервера: ${outcome.message}",
                    )
                    TokenAttempt(null, transportFailed = true)
                }
                else -> {
                    DebugLog.add(
                        LogLevel.INFO, LogTag.API,
                        "проверка по токену без итога: ${outcome.code} ${outcome.message}",
                    )
                    TokenAttempt(null)
                }
            }
            else -> TokenAttempt(null)
        }
    }

    /** Статус из Mi Community: разрешение, срок или «можно подавать». */
    private suspend fun bbsStatus(
        credentials: Credentials,
        proxy: ProxySettings,
        transportFailed: Boolean,
    ): AccountStatus {
        // Без serviceToken и deviceId Mi Community не спросить. Это не «нет
        // разрешения», а отсутствие источника: так и говорим.
        if (!credentials.isValid) {
            return AccountStatus(
                source = "нет",
                message = if (transportFailed) {
                    "Нет связи с сервером Xiaomi — проверьте интернет и повторите"
                } else {
                    "Статус неизвестен: укажите данные аккаунта или подключите телефон один раз"
                },
                failed = true,
            )
        }
        val state = bbsState(credentials, proxy)
        return when (state.code) {
            1 -> AccountStatus(
                granted = true,
                deadline = state.deadline,
                source = "bbs",
                message = if (state.deadline.isNotBlank()) {
                    "🎉 РАЗРЕШЕНИЕ ЕСТЬ! Доступ выдан до ${state.deadline}"
                } else {
                    "🎉 РАЗРЕШЕНИЕ ЕСТЬ — можно разблокировать"
                },
            )
            2 -> AccountStatus(
                canApply = true,
                source = "bbs",
                message = "📋 Заявка не подана — можно подавать",
            )
            3 -> AccountStatus(
                source = "bbs",
                message = "⛔ Ошибка аккаунта. Повторите после ${state.deadline}",
            )
            4 -> AccountStatus(
                source = "bbs",
                message = "⛔ Аккаунт создан менее 30 дней назад",
            )
            else -> AccountStatus(
                source = "bbs",
                message = state.message,
                failed = true,
            )
        }
    }
}
