"""Valida respuestas reales de la API contra `contracts/openapi.yaml`.

A diferencia de las pruebas de contrato (que validan fixtures), este script arranca contra un
servidor en marcha, ejecuta el recorrido completo y comprueba que cada cuerpo y cada cabecera
declarada coinciden con el contrato OpenAPI 3.1.

Uso:
    AUDITLAB_LIVE_URL=http://127.0.0.1:18082 \
    AUDITLAB_LIVE_USER=... AUDITLAB_LIVE_PASSWORD=... \
    python3 scripts/check_live_contract.py

Nunca imprime tokens, contraseñas ni códigos de inscripción.
"""
from __future__ import annotations

import json
import os
import time
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse

import requests
import yaml
from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text())
RESOURCE = Resource(contents=CONTRACT, specification=DRAFT202012)
REGISTRY = Registry().with_resource("urn:auditlab:contract", RESOURCE)

failures: list[str] = []
checked = 0


def pointer(*parts: str) -> str:
    return "urn:auditlab:contract#/" + "/".join(p.replace("~", "~0").replace("/", "~1") for p in parts)


def response_spec(path: str, method: str, status: int) -> tuple[dict, list[str]]:
    """Devuelve el objeto de respuesta resuelto y su ruta real dentro del documento.

    Las respuestas de error del contrato son `$ref` a `components/responses/*`, así que el
    esquema no vive en `paths/.../responses/<status>`: hay que seguir la cadena de referencias
    para construir un puntero que exista de verdad.
    """
    spec = CONTRACT["paths"][path][method]["responses"].get(str(status))
    if spec is None:
        raise KeyError(f"{method.upper()} {path} no declara {status}")
    parts = ["paths", path, method, "responses", str(status)]
    seen: set[str] = set()
    while "$ref" in spec:
        ref = spec["$ref"]
        if ref in seen:
            raise ValueError(f"referencia circular en {method.upper()} {path} {status}: {ref}")
        seen.add(ref)
        parts = unescape_pointer(ref)
        spec = resolve(ref)
    return spec, parts


def unescape_pointer(ref: str) -> list[str]:
    if not ref.startswith("#/"):
        raise ValueError(f"solo se admiten referencias internas: {ref}")
    return [part.replace("~1", "/").replace("~0", "~") for part in ref[2:].split("/")]


def resolve(ref: str) -> dict:
    node = CONTRACT
    for part in unescape_pointer(ref):
        node = node[part]
    return node


def fail(message: str) -> None:
    failures.append(message)
    print(f"  FALLO  {message}")


def validate(label: str, path: str, method: str, status: int, response: requests.Response) -> dict | None:
    """Valida estado, cabeceras declaradas y cuerpo JSON/CSV contra el contrato."""
    global checked
    checked += 1
    if response.status_code != status:
        fail(f"{label}: estado {response.status_code}, esperado {status} — {response.text[:160]}")
        return None
    spec, parts = response_spec(path, method, status)

    for header, definition in (spec.get("headers") or {}).items():
        if "$ref" in definition:
            definition = resolve(definition["$ref"])
        value = response.headers.get(header)
        if value is None:
            fail(f"{label}: falta la cabecera {header}")
            continue
        if header == "Cache-Control" and value != "no-store":
            fail(f"{label}: Cache-Control={value!r}, esperado 'no-store'")
        if header == "X-Correlation-ID":
            try:
                uuid.UUID(value)
            except ValueError:
                fail(f"{label}: X-Correlation-ID no es UUID ({value!r})")

    content = spec.get("content") or {}
    media = "application/json" if "application/json" in content else (
        "application/problem+json" if "application/problem+json" in content else None)
    if media:
        try:
            body = response.json()
        except ValueError:
            fail(f"{label}: el cuerpo no es JSON válido")
            return None
        schema_path = pointer(*parts, "content", media, "schema")
        validator = Draft202012Validator({"$ref": schema_path}, registry=REGISTRY)
        errors = sorted(validator.iter_errors(body), key=lambda e: list(e.absolute_path))
        if errors:
            for error in errors[:4]:
                where = "/".join(str(p) for p in error.absolute_path) or "(raíz)"
                fail(f"{label}: {where}: {error.message}")
            return body
        print(f"  ok     {label} · {status} · esquema y cabeceras")
        return body
    if "text/csv" in content:
        if not response.headers.get("Content-Type", "").startswith("text/csv"):
            fail(f"{label}: Content-Type no es text/csv")
        else:
            print(f"  ok     {label} · {status} · text/csv")
        return None
    print(f"  ok     {label} · {status} · sin cuerpo")
    return None


def main() -> int:
    base = (os.environ.get("AUDITLAB_LIVE_URL") or "").rstrip("/")
    user = os.environ.get("AUDITLAB_LIVE_USER") or ""
    password = os.environ.get("AUDITLAB_LIVE_PASSWORD") or ""
    if not base or not user or not password:
        print("Faltan AUDITLAB_LIVE_URL, AUDITLAB_LIVE_USER o AUDITLAB_LIVE_PASSWORD")
        return 2
    if urlparse(base).scheme == "http" and urlparse(base).hostname not in {"127.0.0.1", "localhost"}:
        print("HTTP en claro solo se admite en loopback")
        return 2

    session = requests.Session()
    session.headers["User-Agent"] = "AuditLab-contract-check/1"
    urls: list[str] = []
    ca = os.environ.get("AUDITLAB_LIVE_CA")
    if ca:
        session.verify = ca
    secrets: list[str] = []

    def call(method: str, path: str, *, token: str | None = None, json_body=None, params=None, headers=None) -> requests.Response:
        request_headers = {"Accept": "application/json"}
        if token:
            request_headers["Authorization"] = f"Bearer {token}"
        request_headers.update(headers or {})
        response = session.request(method, base + path, json=json_body, params=params,
                                   headers=request_headers, timeout=20)
        urls.append(response.request.url)
        # La credencial nunca debe viajar en la URL.
        if token and token in response.request.url:
            fail(f"la credencial apareció en la URL de {method.upper()} {path}")
        return response

    print("Recorrido en vivo contra", base)
    now = datetime.now(timezone.utc)

    # Salud y disponibilidad no forman parte del contrato de datos; se comprueban aparte.
    health = session.get(base + "/health", timeout=10)
    if health.status_code == 200 and health.json().get("status") == "ok":
        print("  ok     GET /health · 200 · status ok")
    else:
        fail(f"GET /health devolvió {health.status_code}")
    ready = session.get(base + "/ready", timeout=10)
    if ready.status_code == 200:
        print("  ok     GET /ready · 200")
    else:
        fail(f"GET /ready devolvió {ready.status_code}")

    # 1. Autenticación.
    login = validate("POST /auth/login", "/auth/login", "post", 200, call(
        "POST", "/v1/auth/login", json_body={"username": user, "password": password}))
    if not login:
        print("\nSin sesión no se puede continuar.")
        return 1
    access, refresh = login["accessToken"], login["refreshToken"]
    secrets.extend([access, refresh])
    user_id = login["user"]["userId"]

    # El login fallido usa una cuenta inexistente: consume el cupo del cliente, no el de `user`,
    # así repetir la comprobación en el mismo minuto no agota el límite por cuenta.
    bad_login = call("POST", "/v1/auth/login",
                     json_body={"username": "no_such_account", "password": password + "-mal"})
    if bad_login.status_code == 429:
        # Límite de intentos del cliente alcanzado por ejecuciones seguidas: esperar lo indicado.
        retry_after = int(bad_login.headers.get("Retry-After", "60"))
        print(f"  nota   límite de login alcanzado; reintento en {retry_after}s")
        time.sleep(min(retry_after, 60))
        bad_login = call("POST", "/v1/auth/login",
                         json_body={"username": "no_such_account", "password": password + "-mal"})
    validate("POST /auth/login inválido", "/auth/login", "post", 401, bad_login)

    # 2. Inscripción con consentimiento.
    created = validate("POST /devices/enrollment-code", "/devices/enrollment-code", "post", 201,
                       call("POST", "/v1/devices/enrollment-code", token=access))
    if not created:
        return 1
    code = created["code"]
    secrets.append(code)
    consent = {"consentId": str(uuid.uuid4()), "policyVersion": "live-check",
               "acceptedAt": (now - timedelta(seconds=2)).isoformat().replace("+00:00", "Z")}
    enrolled = validate("POST /devices/enroll", "/devices/enroll", "post", 201, call(
        "POST", "/v1/devices/enroll", headers={"X-Enrollment-Code": code},
        json_body={"consentAcknowledged": True, "consent": consent, "appVersion": "0.1.0-live"}))
    if not enrolled:
        return 1
    device_id, device_token = enrolled["deviceId"], enrolled["deviceToken"]
    secrets.append(device_token)

    reused = call("POST", "/v1/devices/enroll", headers={"X-Enrollment-Code": code},
                  json_body={"consentAcknowledged": True, "consent": consent})
    validate("POST /devices/enroll con código usado", "/devices/enroll", "post", 401, reused)

    # 3. Ingesta: alta, duplicado idempotente y conflicto de contenido.
    event_id = str(uuid.uuid4())
    event = {
        "eventId": event_id,
        "timestamp": now.isoformat().replace("+00:00", "Z"),
        "category": "app_lifecycle",
        "direction": "occurred",
        "sourceDevice": device_id,
        "consentContext": enrolled["consentContext"],
        "isSimulated": True,
        "metadata": {"appEventCode": "agent_started"},
    }
    validate("POST /events", "/events", "post", 202, call(
        "POST", "/v1/events", token=device_token, json_body={"events": [event]}))
    duplicate = validate("POST /events duplicado", "/events", "post", 202, call(
        "POST", "/v1/events", token=device_token, json_body={"events": [event]}))
    if duplicate and duplicate.get("duplicates") != 1:
        fail(f"el reintento debía marcar 1 duplicado, devolvió {duplicate}")
    # Mismo eventId con contenido normalizado distinto y un código válido: debe ser 409.
    conflict = dict(event, metadata={"appEventCode": "sync_succeeded"})
    validate("POST /events conflicto", "/events", "post", 409, call(
        "POST", "/v1/events", token=device_token, json_body={"events": [conflict]}))

    simulated_false = dict(event, eventId=str(uuid.uuid4()), isSimulated=False)
    validate("POST /events sin isSimulated=true", "/events", "post", 400, call(
        "POST", "/v1/events", token=device_token, json_body={"events": [simulated_false]}))

    # 4. Consultas del centro.
    devices = validate("GET /devices", "/devices", "get", 200, call("GET", "/v1/devices", token=access,
                                                                    params={"limit": 10}))
    if devices and not any(item["deviceId"] == device_id for item in devices["items"]):
        fail("el dispositivo recién inscrito no aparece en el listado")
    validate("GET /devices/{deviceId}", "/devices/{deviceId}", "get", 200,
             call("GET", f"/v1/devices/{device_id}", token=access))
    page = validate("GET /events", "/events", "get", 200,
                    call("GET", "/v1/events", token=access, params={"device": device_id, "limit": 10}))
    if page and not any(item["eventId"] == event_id for item in page["items"]):
        fail("el evento enviado no aparece en la consulta del centro")
    validate("GET /devices/{deviceId}/events/{eventId}", "/devices/{deviceId}/events/{eventId}", "get", 200,
             call("GET", f"/v1/devices/{device_id}/events/{event_id}", token=access))
    validate("GET /events filtro q", "/events", "get", 200,
             call("GET", "/v1/events", token=access, params={"q": event_id, "limit": 5}))

    csv = call("GET", "/v1/events/export.csv", token=access, params={"device": device_id})
    validate("GET /events/export.csv", "/events/export.csv", "get", 200, csv)
    if not csv.text.startswith("eventId,timestamp,category,isSimulated"):
        fail(f"cabecera CSV inesperada: {csv.text[:80]!r}")

    validate("GET /audit-log", "/audit-log", "get", 200,
             call("GET", "/v1/audit-log", token=access, params={"limit": 10}))

    # 5. Errores del contrato.
    validate("GET /devices sin sesión", "/devices", "get", 401, call("GET", "/v1/devices"))
    validate("GET /devices cursor inválido", "/devices", "get", 400,
             call("GET", "/v1/devices", token=access, params={"cursor": "x" * 40}))

    # 6. Revocación y bloqueo posterior.
    validate("POST /devices/{deviceId}/revoke", "/devices/{deviceId}/revoke", "post", 204,
             call("POST", f"/v1/devices/{device_id}/revoke", token=access))
    validate("POST /events tras revocación", "/events", "post", 401, call(
        "POST", "/v1/events", token=device_token, json_body={"events": [dict(event, eventId=str(uuid.uuid4()))]}))

    # 7. Refresh y logout.
    renewed = validate("POST /auth/refresh", "/auth/refresh", "post", 200,
                       call("POST", "/v1/auth/refresh", json_body={"refreshToken": refresh}))
    if renewed:
        secrets.append(renewed["refreshToken"])
        access = renewed["accessToken"]
        secrets.append(access)
    validate("POST /auth/logout", "/auth/logout", "post", 204, call("POST", "/v1/auth/logout", token=access))
    validate("POST /auth/refresh tras logout", "/auth/refresh", "post", 401,
             call("POST", "/v1/auth/refresh", json_body={"refreshToken": (renewed or {}).get("refreshToken", refresh)}))

    # 8. Ningún secreto debe aparecer en una URL ni en los cuerpos impresos.
    for secret in secrets:
        if secret and any(secret in url for url in urls):
            fail("un secreto apareció en una URL")

    print(f"\n{checked} respuestas validadas contra el contrato; {len(failures)} fallo(s).")
    if failures:
        for failure in failures:
            print(f"- {failure}")
        return 1
    print("Contrato en vivo correcto.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
