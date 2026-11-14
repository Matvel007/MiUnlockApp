package com.miunlock.app.unlock

import android.content.Context
import com.miunlock.app.auth.PythonMiAuth
import com.miunlock.app.data.DebugLog
import com.miunlock.app.data.LogLevel
import com.miunlock.app.data.LogTag

/**
 * Обновление сервисной сессии перед запросами к unlock-серверам.
 *
 * Токен unlock-домена живёт около часа: с истёкшим сервер отвечает 401 на
 * первый же запрос. Обновление идёт по сохранённым cookies, поэтому пароль
 * и код подтверждения не нужны — человек ничего не вводит.
 *
 * Без этого проверка статуса, запущенная через час после входа, падала бы
 * с 401 и выглядела как «сессии нет».
 */
class SessionRefresher(context: Context, private val store: UnlockStore) {

    private val auth by lazy { PythonMiAuth(context.applicationContext) }

    /** Возвращает сессию с новыми cookies; при неудаче — исходную, без ошибки наверх. */
    suspend fun refresh(session: UnlockSession): UnlockSession {
        if (session.cookies.isEmpty() || session.sid.isBlank()) {
            DebugLog.add(
                LogLevel.TRACE, LogTag.AUTH,
                "сессия не обновляется: нет cookies или имени сервиса",
            )
            return session
        }
        val fresh = auth.refresh(session.sid, session.cookies) ?: return session
        val updated = mergeRefreshed(
            session = session,
            cookies = fresh.cookies,
            ssecurity = fresh.ssecurity,
            token = fresh.token,
            stsDomain = fresh.stsDomain,
        )
        if (updated != session) store.saveSession(updated)
        return updated
    }
}

/**
 * Собирает сессию из ответа обновления.
 *
 * Пустые поля ответа не затирают сохранённые: сервер не всегда возвращает
 * всё сразу, а без ssecurity или домена сессия перестанет работать.
 */
internal fun mergeRefreshed(
    session: UnlockSession,
    cookies: Map<String, String>,
    ssecurity: String,
    token: String,
    stsDomain: String,
): UnlockSession {
    if (cookies.isEmpty()) return session
    return session.copy(
        cookies = cookies,
        ssecurity = ssecurity.ifBlank { session.ssecurity },
        serviceToken = token.ifBlank { session.serviceToken },
        stsDomain = stsDomain.ifBlank { session.stsDomain },
    )
}
