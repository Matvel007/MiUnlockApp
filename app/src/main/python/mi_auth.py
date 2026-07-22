import base64
import hashlib
import json
from urllib.parse import quote, urlparse, parse_qs

import requests

SERVICE_LOGIN = "https://account.xiaomi.com/pass/serviceLogin"
SERVICE_LOGIN_AUTH2 = "https://account.xiaomi.com/pass/serviceLoginAuth2"
IDENTITY_LIST = "https://account.xiaomi.com/identity/list"
USER_QUOTA = "https://account.xiaomi.com/identity/pass/sms/userQuota"
SEND_EMAIL = "https://account.xiaomi.com/identity/auth/sendEmailTicket"
SEND_PHONE = "https://account.xiaomi.com/identity/auth/sendPhoneTicket"
VERIFY_EMAIL = "https://account.xiaomi.com/identity/auth/verifyEmail"
VERIFY_PHONE = "https://account.xiaomi.com/identity/auth/verifyPhone"

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


def login(user, password, sid):
    global auth_data, verification_context
    session.cookies.clear()
    verification_context = None
    initial = _json(session.get(SERVICE_LOGIN, params={"sid": sid, "_json": True}))
    auth_data = {
        "sid": sid,
        "_json": True,
        "serviceParam": initial["serviceParam"],
        "qs": initial["qs"],
        "callback": initial["callback"],
        "_sign": initial["_sign"],
        "user": user.strip(),
        "hash": hashlib.md5(password.encode()).hexdigest().upper(),
    }
    return _reply(_finish_auth(session.post(SERVICE_LOGIN_AUTH2, data=auth_data), sid))


def send_code(method):
    if not verification_context:
        return _reply({"ok": False, "error": _message("Сессия подтверждения истекла", "Verification session expired")})
    quota = _json(session.post(USER_QUOTA, data={"addressType": method, "contentType": "160040", "_json": "true"}))
    if int(quota.get("info") or 0) <= 0:
        return _reply({"ok": False, "error": _message("Лимит отправки кодов Xiaomi исчерпан", "Xiaomi verification-code limit reached")})
    endpoint = SEND_PHONE if method == "PH" else SEND_EMAIL
    result = _json(session.post(endpoint))
    if result.get("code") != 0:
        return _reply({"ok": False, "error": result.get("tips") or result.get("desc") or f"Код Xiaomi {result.get('code')}"})
    return _reply({"ok": True})


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
    service = requests.Session()
    service.headers.update(session.headers)
    for key, value in required.items():
        service.cookies.set(key, value)
    first = _json(service.get(SERVICE_LOGIN, params={"sid": sid, "_json": True}))
    nonce, ssecurity, location = first.get("nonce"), first.get("ssecurity"), first.get("location")
    if not nonce or not ssecurity or not location:
        return _reply({"ok": False, "error": _message(f"Mi Community serviceLogin не выдал service data (код {first.get('code')})", f"Mi Community serviceLogin did not provide service data (code {first.get('code')})")})
    sign = quote(base64.b64encode(hashlib.sha1(f"nonce={nonce}&{ssecurity}".encode()).digest()))
    response = service.get(f"{location}&clientSign={sign}")
    token = response.cookies.get("new_bbs_serviceToken")
    if not token:
        return _reply({"ok": False, "error": _message("Mi Community не выдал new_bbs_serviceToken", "Mi Community did not provide new_bbs_serviceToken")})
    return _reply({"ok": True, "token": token, "deviceId": required["deviceId"]})
