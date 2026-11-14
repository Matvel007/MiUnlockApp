package com.miunlock.app.unlock

import com.miunlock.app.data.DebugLog
import com.miunlock.app.data.LogLevel
import com.miunlock.app.data.LogTag

/**
 * Проверка статуса заявки без телефона.
 *
 * `ahaUnlock` — серверный вызов: ему нужны только токен устройства,
 * codename, uid и подпись сессии. Телефон требуется ровно один раз —
 * прочитать токен из fastboot. Дальше статус можно спрашивать сколько
 * угодно, не подключая аппарат.
 *
 * Именно этот вызов называет остаток ожидания (`data.waitHour`), поэтому
 * только он и даёт честный ответ «одобрено / ещё N часов / не привязано».
 */
class StatusCheck(private val api: UnlockApi) {

    suspend fun byToken(session: UnlockSession, saved: SavedDeviceToken): UnlockOutcome {
        if (!session.isValid) {
            return UnlockOutcome.Failure(
                UnlockStep.AHA,
                "нет сессии Mi Account — войдите в режиме разблокировки",
            )
        }
        val domains = domainsFor(session)
        DebugLog.add(
            LogLevel.INFO,
            LogTag.API,
            "проверка по токену: ${saved.product}, uid ${session.userId}, домены ${domains.joinToString(" → ")}",
        )
        var last: UnlockApiResult? = null
        for (domain in domains) {
            val nonce = try {
                api.nonce(domain, session.ssecurity, session.cookies)
            } catch (error: Throwable) {
                DebugLog.warn(LogTag.API, "nonce на $domain: ${error.message}")
                continue
            }
            if (!nonce.isOk) {
                DebugLog.warn(LogTag.API, "nonce на $domain: код ${nonce.code} ${nonce.desc}")
                continue
            }
            val result = try {
                api.ahaUnlock(
                    domain = domain,
                    product = saved.product,
                    deviceToken = saved.token,
                    nonce = nonce.json.optString("nonce"),
                    ssecurity = session.ssecurity,
                    deviceId = session.deviceId,
                    userId = session.userId,
                    cookies = session.cookies,
                )
            } catch (error: Throwable) {
                DebugLog.warn(LogTag.API, "ahaUnlock на $domain: ${error.message}")
                continue
            }
            DebugLog.add(
                LogLevel.INFO,
                LogTag.API,
                "проверка по токену на $domain: код ${result.code} ${result.desc}",
            )
            if (result.isOk) {
                // Сервер подписал токен: разрешение есть, срок не назван.
                return UnlockOutcome.Success("Разрешение есть — можно разблокировать")
            }
            last = result
            // Другая зона может ответить иначе — перебираем, как в разблокировке.
            if (result.code !in UnlockWorkflow.ZONE_CODES) break
        }
        val answer = last ?: return UnlockOutcome.Failure(
            UnlockStep.AHA,
            "сервер не ответил ни в одной зоне",
        )
        val waitHours = answer.json.optJSONObject("data")
            ?.optInt("waitHour", 0)
            ?.takeIf { it > 0 }
        if (waitHours != null) {
            DebugLog.add(LogLevel.INFO, LogTag.API, "проверка по токену: осталось $waitHours ч")
        }
        return UnlockOutcome.Failure(UnlockStep.AHA, answer.desc, answer.code, waitHours)
    }

    /** Домены: дата-центр по uid, затем STS и зона из сессии. */
    private suspend fun domainsFor(session: UnlockSession): List<String> = buildList {
        DataCenter.zoneForUid(session.userId)?.let { zone ->
            val byUid = UnlockApi.domainForZone(zone)
            if (byUid !in this) add(byUid)
        }
        if (session.stsDomain.isNotBlank()) add(session.stsDomain)
        val zoned = UnlockApi.domainForZone(session.zone)
        if (zoned !in this) add(zoned)
    }
}
