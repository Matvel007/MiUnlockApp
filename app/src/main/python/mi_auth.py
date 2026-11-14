import base64
import hashlib
import json
import re
from http.cookies import SimpleCookie
from urllib.parse import quote, urlparse, parse_qs, parse_qsl

import requests

SERVICE_LOGIN = "https://account.xiaomi.com/pass/serviceLogin"
SERVICE_LOGIN_AUTH2 = "https://account.xiaomi.com/pass/serviceLoginAuth2"
IDENTITY_LIST = "https://account.xiaomi.com/identity/list"
USER_QUOTA = "https://account.xiaomi.com/identity/pass/sms/userQuota"
REGION_URL = "https://account.xiaomi.com/pass/user/login/region"
REGION_CONFIG = "https://account.xiaomi.com/pass2/config"
SEND_EMAIL = "https://account.xiaomi.com/identity/auth/sendEmailTicket"
SEND_PHONE = "https://account.xiaomi.com/identity/auth/sendPhoneTicket"
VERIFY_EMAIL = "https://account.xiaomi.com/identity/auth/verifyEmail"
VERIFY_PHONE = "https://account.xiaomi.com/identity/auth/verifyPhone"
LONGPOLLING_URL = "https://account.xiaomi.com/longPolling/loginUrl"

# STS unlock-домена отдаёт cookies сессии сервиса только браузерному клиенту.
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

session = requests.Session()
session.headers.update({
    "User-Agent": "offici5l/migate",
    "Content-Type": "application/x-www-form-urlencoded",
    "Accept": "application/json;charset=UTF-8",
    "Accept-Language": "en-US,en;q=0.9",
})
auth_data = None
verification_context = None
language = "ru"


def _json(response):
    response.raise_for_status()
    text = response.text
    return json.loads(text[11:] if text.startswith("&&&START&&&") else text)


def _reply(data):
    return json.dumps(data, ensure_ascii=False)


def _message(ru, en):
    return en if language == "en" else ru


def set_language(value):
    global language
    language = "en" if value == "en" else "ru"
    return _reply({"ok": True})


def set_proxy(host, port, username, password):
    session.proxies.clear()
    if host and port:
        credentials = ""
        if username:
            credentials = "{}:{}@".format(quote(username, safe=""), quote(password, safe=""))
        proxy_url = "http://{}{}:{}".format(credentials, host, port)
        session.proxies.update({"http": proxy_url, "https": proxy_url})
    return _reply({"ok": True})


def _finish_auth(response, sid):
    global verification_context
    result = _json(response)
    code = result.get("code", -1)
    if code == 70016:
        return {"ok": False, "error": _message("Неверный Xiaomi ID, email, телефон или пароль", "Incorrect Xiaomi ID, email, phone or password")}
    notification = result.get("notificationUrl")
    if notification:
        context = parse_qs(urlparse(notification).query).get("context", [None])[0]
        if not context:
            return {"ok": False, "error": _message("Xiaomi не передал контекст подтверждения", "Xiaomi did not provide a verification context")}
        verification_context = context
        options = _json(session.get(IDENTITY_LIST, params={"sid": sid, "supportedMask": "0", "context": context})).get("options", [])
        methods = (["EM"] if 8 in options else []) + (["PH"] if 4 in options else [])
        return {"ok": True, "verification": methods}
    if code != 0:
        return {"ok": False, "error": result.get("desc", _message(f"Ошибка Xiaomi ({code})", f"Xiaomi error ({code})"))}
    return {"ok": True, "verification": []}


def login(user, password, sid, safe_address="false"):
    global auth_data, verification_context
    session.cookies.clear()
    verification_context = None
    # checkSafeAddress меняет serviceParam и _sign: подпись входа считается
    # от этих параметров. Сервис разблокировки требует его включённым,
    # иначе код подтверждения не принимается.
    params = {"sid": sid, "_json": True}
    if safe_address == "true":
        params["checkSafeAddress"] = "true"
    initial = _json(session.get(SERVICE_LOGIN, params=params))
    auth_data = {
        "sid": sid,
        "_json": True,
        "checkSafeAddress": "true" if safe_address == "true" else "false",
        "serviceParam": initial["serviceParam"],
        "qs": initial["qs"],
        "callback": initial["callback"],
        "_sign": initial["_sign"],
        "user": user.strip(),
        "hash": hashlib.md5(password.encode()).hexdigest().upper(),
    }
    return _reply(_finish_auth(session.post(SERVICE_LOGIN_AUTH2, data=auth_data), sid))


def quotas(*methods):
    """Остаток попыток по каждому способу подтверждения.

    Сервер считает почту и SMS отдельно: у одного и того же аккаунта на почте
    может быть ноль, а на SMS — пять. Выбирать способ вслепую нельзя.
    """
    result = {}
    for method in methods:
        result[method] = _json(
            session.post(USER_QUOTA, data={"addressType": method, "contentType": "160040", "_json": "true"})
        )
    return _reply({"ok": True, "quotas": result})


def send_code(method):
    if not verification_context:
        return _reply({"ok": False, "error": _message("Сессия подтверждения истекла", "Verification session expired")})
    quota = _json(session.post(USER_QUOTA, data={"addressType": method, "contentType": "160040", "_json": "true"}))
    # Сервер называет остаток попыток в поле `info`. Ноль — настоящий лимит,
    # он подтверждается и входом через сайт. Если числа нет вовсе, это не лимит,
    # а непонятный ответ: так и говорим, а не выдаём догадку за факт.
    info = quota.get("info")
    if info is None:
        return _reply({
            "ok": False,
            "error": _message(
                "Сервер не назвал остаток попыток. Попробуйте позже.",
                "The server did not report the remaining attempts. Try again later.",
            ),
            "quota": quota,
        })
    if int(info) <= 0:
        return _reply({
            "ok": False,
            "error": _message("Лимит отправки кодов Xiaomi исчерпан", "Xiaomi verification-code limit reached"),
            "quota": quota,
        })
    endpoint = SEND_PHONE if method == "PH" else SEND_EMAIL
    result = _json(session.post(endpoint))
    code = result.get("code")
    # 20024 — не дневной лимит, а запрет частых попыток: сервер просто не
    # отправляет SMS и называет в `wt`, сколько секунд ждать.
    if code == 20024:
        wait = int((result.get("data") or {}).get("wt") or 60)
        return _reply({
            "ok": False,
            "error": _message(
                f"Xiaomi просит подождать {wait} с перед новой отправкой",
                f"Xiaomi asks to wait {wait}s before resending",
            ),
            "retryAfter": wait,
            "quota": quota,
        })
    if code != 0:
        return _reply({
            "ok": False,
            "error": result.get("tips") or result.get("desc") or result.get("reason") or f"Код Xiaomi {code}",
            "quota": quota,
        })
    return _reply({"ok": True, "quota": quota})


def verify_code(method, code, sid):
    if not auth_data:
        return _reply({"ok": False, "error": _message("Сессия входа истекла", "Sign-in session expired")})
    endpoint = VERIFY_PHONE if method == "PH" else VERIFY_EMAIL
    result = _json(session.post(endpoint, data={"ticket": code.strip(), "trust": "true", "_json": "true"}))
    if result.get("code") != 0:
        return _reply({"ok": False, "error": result.get("desc", _message("Неверный код", "Incorrect code"))})
    first = session.get(result["location"], allow_redirects=False)
    follow_up = first.headers.get("Location")
    if not follow_up:
        return _reply({"ok": False, "error": _message("Xiaomi не передал redirect после кода", "Xiaomi did not provide a redirect after code verification")})
    session.get(follow_up, allow_redirects=False)
    return _reply(_finish_auth(session.post(SERVICE_LOGIN_AUTH2, data=auth_data), sid))


def _service_login(cookies, sid):
    """Вход в сервис по уже имеющимся cookies. Возвращает (ответ, сессия, вариант, попытки).

    Проверено на живом устройстве: работает первый вариант — три ключевые
    cookie (deviceId, passToken, userId) без домена и с checkSafeAddress.
    С полным набором сервис отвечает без ключа. Остальные варианты оставлены
    на случай других аккаунтов и прошивок; перебор останавливается на первом
    успешном, поэтому лишних запросов нет.
    """
    required = {key: cookies.get(key) for key in ("deviceId", "passToken", "userId")}

    def attempt(cookie_set, safe):
        client = requests.Session()
        client.headers.update(session.headers)
        # Без домена — так cookie уходит на любой хост Xiaomi. Проверенная
        # реализация делает именно так: домены после цепочки редиректов
        # могут не совпасть, и тогда сессия «не узнаётся».
        for key, value in cookie_set.items():
            client.cookies.set(key, value)
        params = {"sid": sid, "_json": True}
        if safe:
            params["checkSafeAddress"] = "true"
        return _json(client.get(SERVICE_LOGIN, params=params)), client

    variants = [
        ("3+safe", required, True),
        ("all+safe", cookies, True),
        ("3", required, False),
        ("all", cookies, False),
    ]
    attempts, first, found, key_source = [], None, None, sid
    for name, cookie_set, safe in variants:
        if not all(cookie_set.values()):
            continue
        answer, probe = attempt(cookie_set, safe)
        attempts.append({
            "variant": name,
            "code": answer.get("code"),
            "nonce": bool(answer.get("nonce")),
            "ssecurity": bool(answer.get("ssecurity")),
            "location": bool(answer.get("location")),
            "result": answer.get("result"),
        })
        if answer.get("nonce") and answer.get("ssecurity"):
            found = (answer, probe)
            key_source = f"{sid}:{name}"
            break
        if first is None:
            first = answer
    answer, client = found if found else (first, None)
    return answer, client, key_source, attempts


def _sts_summary(results):
    """Короткая строка о попытках STS для сообщения об ошибке."""
    parts = []
    for entry in results:
        if "error" in entry:
            parts.append(f"{entry['variant']}: сбой сети")
            continue
        detail = f"{entry['variant']}: {entry['status']}"
        if entry.get("wwwAuth"):
            detail += f" [{entry['wwwAuth']}]"
        if entry.get("finalHost"):
            detail += f" → {entry['finalHost']}"
        parts.append(detail)
    return "; ".join(parts) or "нет ответа"


def _sts_obtain(location, nonce, ssecurity, cookies):
    """Достаёт cookies сервиса через STS. Возвращает (cookies, попытки).

    STS — единственное место, где сервис выдаёт свой токен. Он отвечает 401
    и на браузерный UA, поэтому перебираем варианты и возвращаем всё, что
    сервер сказал: статус, WWW-Authenticate, Set-Cookie. Без этих фактов
    дальше — гадание.

    Отдельно пробуем парный хост: Xiaomi держит китайский unlock.update.miui.com
    и международный unlock.update.intl.miui.com, и сессия международного
    аккаунта на китайском хосте не признаётся.
    """
    sign = quote(base64.b64encode(hashlib.sha1(f"nonce={nonce}&{ssecurity}".encode()).digest()))
    parsed = urlparse(location)
    hosts = [parsed.netloc]
    if parsed.netloc.endswith(".intl.miui.com"):
        hosts.append(parsed.netloc[: -len(".intl.miui.com")] + ".miui.com")
    elif parsed.netloc.endswith(".miui.com"):
        hosts.append(parsed.netloc[: -len(".miui.com")] + ".intl.miui.com")
    path = parsed.path + ("?" + parsed.query if parsed.query else "")
    cookie_sets = [("3", {k: cookies.get(k) for k in ("deviceId", "passToken", "userId")}),
                   ("all", dict(cookies))]
    results, found = [], {}
    for host in hosts:
        base = f"{parsed.scheme}://{host}{path}"
        for name, jar in cookie_sets:
            if not all(jar.values()):
                continue
            for mode, url in (("sign", f"{base}&clientSign={sign}"), ("plain", base)):
                probe = requests.Session()
                probe.headers.update(session.headers)
                for key, value in jar.items():
                    probe.cookies.set(key, value)
                try:
                    answer = probe.get(
                        url,
                        headers={"User-Agent": BROWSER_UA,
                                 "Accept": "text/html,application/json;q=0.9,*/*;q=0.8"},
                        allow_redirects=True,
                    )
                except Exception as error:
                    results.append({"variant": f"{host}/{name}/{mode}", "error": str(error)[:120]})
                    continue
                fresh = {k: v for k, v in probe.cookies.get_dict().items() if jar.get(k) != v}
                results.append({
                    "variant": f"{host}/{name}/{mode}",
                    "status": answer.status_code,
                    "finalHost": urlparse(answer.url).netloc,
                    "finalUrl": answer.url[:200],
                    # Какие cookie реально ушли: имена и длины, без значений.
                    # Значения — это доступ к аккаунту, в журнал им нельзя.
                    "sent": sorted(jar),
                    "sentMasked": sorted(f"{k}:{len(v or '')}" for k, v in jar.items()),
                    "requestCookieLen": len(answer.request.headers.get("Cookie", "")),
                    "wwwAuth": answer.headers.get("WWW-Authenticate", ""),
                    "location": answer.headers.get("Location", ""),
                    "contentType": answer.headers.get("Content-Type", ""),
                    "setCookie": sorted(fresh),
                    "len": len(answer.content),
                    # Тело 0 байт ничего не говорит; если что-то есть — показываем начало.
                    "body": answer.text[:200] if answer.content else "",
                    # Причина 401 бывает в заголовке, а не в теле.
                    "headers": {
                        key: value[:100]
                        for key, value in answer.headers.items()
                        if key.lower() != "set-cookie"
                    },
                })
                if any(key.lower().endswith("servicetoken") for key in fresh):
                    return probe.cookies.get_dict(), results
    return found, results


def exchange(sid):
    cookies = session.cookies.get_dict()
    if not all(cookies.get(key) for key in ("deviceId", "passToken", "userId")):
        return _reply({"ok": False, "error": _message("Xiaomi не выдал passToken, userId или deviceId", "Xiaomi did not provide passToken, userId or deviceId")})

    first, client, key_source, attempts = _service_login(cookies, sid)
    nonce = (first or {}).get("nonce")
    ssecurity = (first or {}).get("ssecurity")
    location = (first or {}).get("location")

    exchange_debug = {
        "code": first.get("code") if first else None,
        "keys": sorted(first.keys()) if first else [],
        "keySource": key_source,
        "hasNonce": bool(nonce),
        "hasSsecurity": bool(ssecurity),
        "hasLocation": bool(location),
        "securityStatus": first.get("securityStatus") if first else None,
        "notification": (first.get("notificationUrl") or "")[:80] if first else "",
        "cookies": len(cookies),
        "attempts": attempts,
    }
    if not nonce or not ssecurity or not location:
        return _reply({"ok": False, "debug": exchange_debug, "error": _message(
            f"Сервис {sid} не выдал данные входа (код {first.get('code') if first else '?'})",
            f"Service {sid} did not provide sign-in data (code {first.get('code') if first else '?'})")})

    parsed_location = urlparse(location)
    exchange_debug["locationHost"] = parsed_location.netloc
    exchange_debug["locationPath"] = parsed_location.path
    exchange_debug["locationParams"] = sorted(
        key for key, _ in parse_qsl(parsed_location.query) if key not in ("nonce", "ssecurity")
    )
    service_cookies, sts_results = _sts_obtain(location, nonce, ssecurity, cookies)
    exchange_debug["sts"] = sts_results
    token = ""
    for key in sorted(service_cookies):
        if key.lower().endswith("servicetoken"):
            token = service_cookies[key]
            break
    # Для unlock.update.* нужны cookies и ssecurity: ими подписывается каждый
    # запрос. Токен — только признак входа, в подписи он не участвует.
    exchange_debug.update({
        "stsStatus": sts_results[0]["status"] if sts_results and "status" in sts_results[0] else 0,
        "stsCookies": sorted(service_cookies),
        "serviceToken": bool(token),
    })
    if not service_cookies:
        return _reply({"ok": False, "debug": exchange_debug, "error": _message(
            f"Сервис {sid} не выдал cookies: {_sts_summary(sts_results)}",
            f"Service {sid} did not return cookies: {_sts_summary(sts_results)}")})
    # Хост, на который ведёт STS, — это и есть домен сервиса. Токен привязан
    # к нему, поэтому клиент пробует его первым, а зону оставляет запасным.
    sts_domain = f"{parsed_location.scheme}://{parsed_location.netloc}" if parsed_location.netloc else ""
    return _reply({
        "ok": True,
        "token": token,
        "deviceId": cookies["deviceId"],
        "userId": cookies["userId"],
        "ssecurity": ssecurity,
        "cookies": service_cookies,
        "stsDomain": sts_domain,
        "debug": exchange_debug,
    })


def refresh(sid, cookies_json):
    """Обновляет cookies сервиса по сохранённой сессии, без нового входа.

    Токен сервиса unlock-домена живёт около часа. Когда он истёк, сервер
    отвечает 401 на первый же запрос. Здесь повторяется только STS-обмен
    на уже имеющихся cookies — пароль и код не нужны.
    """
    saved = json.loads(cookies_json)
    if not all(saved.get(k) for k in ("deviceId", "passToken", "userId")):
        return _reply({"ok": False, "error": _message("Нет сохранённой сессии", "No saved session")})
    # Тот же перебор вариантов, что и при входе: с полным набором cookies
    # сервис отвечает без ключа, рабочий вариант — три ключевые cookie.
    answer, client, key_source, attempts = _service_login(saved, sid)
    answer = answer or {}
    if client is None:
        return _reply({"ok": False, "attempts": attempts, "error": _message(
            f"Сессия не обновляется (код {answer.get('code')})",
            f"Session cannot be refreshed (code {answer.get('code')})")})
    nonce, ssecurity, location = answer.get("nonce"), answer.get("ssecurity"), answer.get("location")
    if not nonce or not ssecurity or not location:
        return _reply({"ok": False, "attempts": attempts, "error": _message(
            f"Сессия не обновляется (код {answer.get('code')})",
            f"Session cannot be refreshed (code {answer.get('code')})")})
    service_cookies, sts_results = _sts_obtain(location, nonce, ssecurity, saved)
    token = next((v for k, v in sorted(service_cookies.items()) if k.lower().endswith("servicetoken")), "")
    parsed = urlparse(location)
    if not token:
        # Без токена сессия не станет рабочей: возвращаем причину, а не успех.
        return _reply({
            "ok": False,
            "attempts": attempts,
            "sts": sts_results,
            "error": _message(
                f"Сервис не выдал токен: {_sts_summary(sts_results)}",
                f"Service did not return a token: {_sts_summary(sts_results)}"),
        })
    return _reply({
        "ok": True,
        "cookies": service_cookies,
        "ssecurity": ssecurity,
        "token": token,
        "stsDomain": f"{parsed.scheme}://{parsed.netloc}" if parsed.netloc else "",
        "sts": sts_results,
    })


def apply_session(cookies_json):
    """Подставляет cookies сохранённой сессии: зону можно уточнить без нового входа."""
    cookies = json.loads(cookies_json)
    for key, value in cookies.items():
        session.cookies.set(key, value)
    return _reply({"ok": True, "cookies": len(cookies)})


def account_zone():
    """Зона данных аккаунта — по официальному ответу Xiaomi, а не по догадке.

    `region` — страна аккаунта, `zone` — дата-центр (Singapore/China/Russia/
    India/Europe). Домен unlock.update.* зависит от дата-центра: у российского
    аккаунта он свой, и запрос в чужой дата-центр сервер отклоняет.
    """
    if not session.cookies.get_dict():
        return _reply({"ok": False, "error": _message("Нет сессии Xiaomi", "No Xiaomi session")})
    try:
        region = (_json(session.get(REGION_URL)).get("data") or {}).get("region") or ""
    except Exception as error:
        return _reply({"ok": False, "error": "region: {}".format(error)[:120]})

    zone = ""
    try:
        config = _json(session.get(REGION_CONFIG, params={"key": "regionConfig"})).get("regionConfig") or {}
        for name, info in config.items():
            if region and region in (info.get("region.codes") or []):
                zone = name
                break
    except Exception:
        zone = ""
    # get_dict(), а не cookies.get(): после QR-входа cookie userId приходит
    # с нескольких доменов, и cookies.get() на дублях бросает CookieConflictError.
    return _reply({"ok": True, "region": region, "zone": zone, "userId": session.cookies.get_dict().get("userId") or ""})


def qr_request(auth_data_json):
    global auth_data
    auth_data = json.loads(auth_data_json)
    response = _json(session.get(LONGPOLLING_URL, params=auth_data))
    lp = response["lp"]
    return _reply({
        "ok": True,
        "loginUrl": response["loginUrl"],
        "lp": lp,
        "timeout": response["timeout"],
        "tips": response.get("qrTips", ""),
        "cookieNames": sorted(session.cookies.get_dict().keys()),
        "cookieHeader": "; ".join("{}={}".format(key, value) for key, value in session.cookies.get_dict().items()),
    })


def qr_auth_data(sid):
    """Готовит параметры QR-входа. Вход идёт сразу под нужный сервис.

    passToken привязан к сервису, под который выдан. Если войти под passport
    (Mi Community), то unlock.update.* на свой STS отвечает 401 — сессия
    чужого сервиса. Поэтому sid передаётся тот, под который нужен токен,
    и для unlockApi запрашивается проверка безопасного адреса.

    `checkSafeAddress` входит в `serviceParam`, от которого считается `_sign`.
    Если запросить параметры с ним, а в сам запрос не передать, подпись
    расходится с данными: вход проходит, но passToken выходит без отметки
    о безопасном адресе, и unlock STS отвечает 401. Поэтому флаг передаётся
    и здесь — так же, как во входе по коду.
    """
    session.cookies.clear()
    params = {"sid": sid, "_json": True}
    safe = sid == "unlockApi"
    if safe:
        params["checkSafeAddress"] = "true"
    initial = _json(session.get(SERVICE_LOGIN, params=params))
    data = {
        "sid": sid,
        "_json": False,
        "serviceParam": initial["serviceParam"],
        "qs": initial["qs"],
        "callback": initial["callback"],
        "_sign": initial["_sign"],
    }
    if safe:
        data["checkSafeAddress"] = "true"
    return _reply({
        "ok": True,
        "authData": json.dumps(data),
        "cookieNames": sorted(session.cookies.get_dict().keys()),
    })


def qr_complete(payload_json, sid=""):
    """Доводит QR-сессию до конца после подтверждения скана.

    Вызывается из Kotlin после long-poll: сам long-poll идёт через OkHttp,
    поэтому шаги сессии выполняются здесь, в общей сессии Python.

    Доводка нужна только сервису разблокировки: Mi Community (`18n_bbs_global`)
    и без этих шагов выдаёт рабочий токен, и трогать проверенный путь незачем.
    """
    if sid and sid != "unlockApi":
        return _reply({"ok": True, "steps": [], "skipped": True})
    try:
        payload = json.loads(payload_json)
    except Exception as error:
        return _reply({"ok": False, "error": "ответ long-poll не разобран: {}".format(error)[:120]})
    steps = _qr_complete(payload)
    return _reply({"ok": True, "steps": steps, "cookies": len(session.cookies.get_dict())})


def _qr_complete(payload):
    """Доводит QR-вход до конца: location, его редирект, обмен под сервис.

    Возвращает список шагов для журнала: по нему видно, где именно вход
    остановился, если сессия снова окажется неполной.

    Каждый шаг в своём `try`: один неудачный переход не должен обрывать
    весь вход. Смысл — добрать cookies, а не отчитаться об успехе.
    """
    global auth_data
    steps = []
    location = payload.get("location", "")
    if not location:
        # Без location видно только то, что сервер прислал: по ключам понятно,
        # ждать ли другого ответа или искать причину в другом месте.
        steps.append("location: нет в ответе, поля {}".format(sorted(payload)))
        return steps
    before = set(session.cookies.get_dict())
    try:
        first = session.get(location, allow_redirects=False, timeout=30)
        steps.append("location: {}".format(first.status_code))
        follow_up = first.headers.get("Location")
        if follow_up:
            session.get(follow_up, allow_redirects=False, timeout=30)
            steps.append("redirect: пройден")
        else:
            steps.append("redirect: нет")
    except Exception as error:
        steps.append("переход: сбой {}".format(_safe_qr_error(error)))
    if auth_data:
        # В QR-параметрах `_json` выключен: так требует long-poll. Для обмена
        # он обязателен — иначе сервер отвечает HTML и разбор падает.
        exchange_data = dict(auth_data)
        exchange_data["_json"] = True
        try:
            answer = _json(session.post(SERVICE_LOGIN_AUTH2, data=exchange_data, timeout=30))
            steps.append("auth2: код {}".format(answer.get("code")))
        except Exception as error:
            steps.append("auth2: сбой {}".format(_safe_qr_error(error)))
    gained = sorted(set(session.cookies.get_dict()) - before)
    steps.append("новые cookies: {}".format(", ".join(gained) if gained else "нет"))
    steps.append("всего cookies: {}".format(len(session.cookies.get_dict())))
    return steps


def qr_apply_cookies(set_cookies_json):
    for header in json.loads(set_cookies_json):
        parsed = SimpleCookie()
        parsed.load(header)
        for key, morsel in parsed.items():
            session.cookies.set(key, morsel.value)
    return _reply({"ok": True})


def _safe_qr_error(error):
    detail = re.sub(r"https?://[^\s']+", "<url>", str(error))
    detail = re.sub(r"(/lp/[^?\s)]+)\?[^\s)]+", r"\1?<redacted>", detail)
    return detail[:160]


def qr_png(url):
    import qrcode
    import io
    qr = qrcode.QRCode(version=1, box_size=14, border=2)
    qr.add_data(url)
    img = qr.make_image()
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return _reply({"ok": True, "pngBase64": base64.b64encode(buf.getvalue()).decode()})
