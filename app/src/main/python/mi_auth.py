import base64
import hashlib
import json
import re
from http.cookies import SimpleCookie
from urllib.parse import quote, urlparse, parse_qs

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


def exchange(sid):
    cookies = session.cookies.get_dict()
    required = {key: cookies.get(key) for key in ("deviceId", "passToken", "userId")}
    if not all(required.values()):
        return _reply({"ok": False, "error": _message("Xiaomi не выдал passToken, userId или deviceId", "Xiaomi did not provide passToken, userId or deviceId")})

    def attempt(cookie_set, safe):
        """Один запрос serviceLogin. Возвращает (ответ, сессия)."""
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

    # Проверено на живом устройстве: работает первый вариант — три ключевые
    # cookie без домена и с checkSafeAddress. С полным набором из девяти
    # сервис отвечает result: error и ключа не даёт. Остальные варианты
    # оставлены на случай других аккаунтов и прошивок; перебор
    # останавливается на первом успешном, поэтому лишних запросов нет.
    variants = [
        ("3+safe", required, True),
        ("all+safe", cookies, True),
        ("3", required, False),
        ("all", cookies, False),
    ]
    attempts = []
    first, client, key_source = None, None, sid
    nonce = ssecurity = location = None
    for name, cookie_set, safe in variants:
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
            first, client = answer, probe
            nonce, ssecurity, location = answer["nonce"], answer["ssecurity"], answer.get("location")
            key_source = f"{sid}:{name}"
            break
        if first is None:
            first = answer

    exchange_debug = {
        "code": first.get("code") if first else None,
        "keys": sorted(first.keys()) if first else [],
        "keySource": key_source,
        "hasNonce": bool(nonce),
        "hasSsecurity": bool(ssecurity),
        "hasLocation": bool(location),
        "cookies": len(cookies),
        "attempts": attempts,
    }
    if not nonce or not ssecurity or not location:
        return _reply({"ok": False, "debug": exchange_debug, "error": _message(
            f"Сервис {sid} не выдал данные входа (код {first.get('code') if first else '?'})",
            f"Service {sid} did not provide sign-in data (code {first.get('code') if first else '?'})")})

    sign = quote(base64.b64encode(hashlib.sha1(f"nonce={nonce}&{ssecurity}".encode()).digest()))
    client.get(f"{location}&clientSign={sign}")
    # Имя cookie с токеном зависит от сервиса: у Mi Community это
    # new_bbs_serviceToken, у unlockApi — своё. Ищем по суффиксу.
    service_cookies = client.cookies.get_dict()
    token = ""
    for key in sorted(service_cookies):
        if key.lower().endswith("servicetoken"):
            token = service_cookies[key]
            break
    # Для unlock.update.* нужны cookies и ssecurity: ими подписывается каждый
    # запрос. Токен — только признак входа, в подписи он не участвует.
    if not service_cookies:
        return _reply({"ok": False, "debug": exchange_debug, "error": _message(
            f"Сервис {sid} не выдал cookies (код {first.get('code')})",
            f"Service {sid} did not return cookies (code {first.get('code')})")})
    return _reply({
        "ok": True,
        "token": token,
        "deviceId": required["deviceId"],
        "userId": required["userId"],
        "ssecurity": ssecurity,
        "cookies": service_cookies,
        "debug": exchange_debug,
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
    return _reply({"ok": True, "region": region, "zone": zone})


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
    session.cookies.clear()
    initial = _json(session.get(SERVICE_LOGIN, params={"sid": sid, "_json": True}))
    data = {
        "sid": sid,
        "_json": False,
        "serviceParam": initial["serviceParam"],
        "qs": initial["qs"],
        "callback": initial["callback"],
        "_sign": initial["_sign"],
    }
    return _reply({
        "ok": True,
        "authData": json.dumps(data),
        "cookieNames": sorted(session.cookies.get_dict().keys()),
    })


def qr_poll(lp, timeout_ms):
    for attempt in range(3):
        try:
            response = session.get(lp, timeout=int(timeout_ms) / 1000 + 2)
            _json(response)
            return _reply({"ok": True, "scanned": True, "expired": False})
        except requests.exceptions.Timeout:
            return _reply({"ok": True, "scanned": False, "expired": True})
        except requests.exceptions.ConnectionError as error:
            if attempt < 2:
                continue
            detail = _safe_qr_error(error)
            return _reply({"ok": False, "error": "QR long-poll ConnectionError: {}".format(detail)})
        except Exception as error:
            detail = _safe_qr_error(error)
            return _reply({"ok": False, "error": "QR long-poll {}: {}".format(type(error).__name__, detail)})


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
