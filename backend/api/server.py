#!/usr/bin/env python3
"""Local AuditLab MVP: consent-gated simulated events and role-based review."""

from __future__ import annotations

import base64
import csv
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


ROOT = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("AUDITLAB_DATA_DIR", ROOT / "data"))
DB_PATH = DATA_DIR / "auditlab.sqlite3"
TOKEN_TTL_SECONDS = 8 * 60 * 60
MAX_BODY_BYTES = 256 * 1024
ALLOWED_CATEGORIES = {"app_lifecycle", "sync", "device_status"}
ALLOWED_DIRECTIONS = {"inbound", "outbound", "occurred"}
ALLOWED_NETWORK = {"online", "offline", "unknown"}
ALLOWED_APP_EVENTS = {
    "agent_started", "sync_succeeded", "sync_failed", "consent_revoked"
}
WRITE_LOCK = threading.Lock()


class ApiError(Exception):
    def __init__(self, status: int, title: str, detail: str = ""):
        super().__init__(detail or title)
        self.status = status
        self.title = title
        self.detail = detail


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def token_secret() -> bytes:
    secret = os.environ.get("AUDITLAB_JWT_SECRET", "")
    if len(secret.encode("utf-8")) < 32:
        raise ApiError(503, "Configuration required", "Set AUDITLAB_JWT_SECRET to at least 32 bytes.")
    return secret.encode("utf-8")


def make_user_token(username: str, role: str) -> str:
    header = b64url(b'{"alg":"HS256","typ":"JWT"}')
    payload = b64url(json.dumps({
        "sub": username,
        "role": role,
        "exp": int(datetime.now(timezone.utc).timestamp()) + TOKEN_TTL_SECONDS,
    }, separators=(",", ":")).encode("utf-8"))
    unsigned = f"{header}.{payload}"
    signature = hmac.new(token_secret(), unsigned.encode("ascii"), hashlib.sha256).digest()
    return f"{unsigned}.{b64url(signature)}"


def verified_user_token(token: str) -> dict:
    try:
        header, payload, signature = token.split(".")
        unsigned = f"{header}.{payload}"
        expected = hmac.new(token_secret(), unsigned.encode("ascii"), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4))):
            raise ValueError("bad signature")
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        if claims.get("exp", 0) < datetime.now(timezone.utc).timestamp():
            raise ValueError("expired")
        if claims.get("role") not in {"admin", "auditor"}:
            raise ValueError("bad role")
        return claims
    except ApiError:
        raise
    except Exception as exc:
        raise ApiError(401, "Unauthorized", "Invalid or expired bearer token.") from exc


@contextmanager
def connect():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=10)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys = ON")
    try:
        yield db
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def retention_days() -> int:
    try:
        days = int(os.environ.get("AUDITLAB_RETENTION_DAYS", "30"))
    except ValueError as exc:
        raise ApiError(503, "Configuration required", "AUDITLAB_RETENTION_DAYS must be an integer.") from exc
    if not 1 <= days <= 3650:
        raise ApiError(503, "Configuration required", "AUDITLAB_RETENTION_DAYS must be between 1 and 3650.")
    return days


def purge_expired_events(db: sqlite3.Connection) -> None:
    cutoff = (datetime.now(timezone.utc) - timedelta(days=retention_days())).isoformat(timespec="seconds").replace("+00:00", "Z")
    db.execute("DELETE FROM events WHERE timestamp < ?", (cutoff,))


def initialize() -> None:
    with connect() as db:
        db.executescript("""
            PRAGMA journal_mode = WAL;
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                status TEXT NOT NULL CHECK(status IN ('active', 'revoked')),
                token_hash TEXT NOT NULL,
                consent_id TEXT NOT NULL,
                policy_version TEXT NOT NULL,
                consent_accepted_at TEXT NOT NULL,
                enrolled_at TEXT NOT NULL,
                last_seen_at TEXT
            );
            CREATE TABLE IF NOT EXISTS enrollment_codes (
                code_hash TEXT PRIMARY KEY,
                created_by TEXT NOT NULL,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                used_at TEXT
            );
            CREATE TABLE IF NOT EXISTS events (
                id TEXT PRIMARY KEY,
                timestamp TEXT NOT NULL,
                category TEXT NOT NULL,
                direction TEXT NOT NULL,
                source_device TEXT NOT NULL REFERENCES devices(id),
                consent_id TEXT NOT NULL,
                policy_version TEXT NOT NULL,
                consent_accepted_at TEXT NOT NULL,
                metadata_json TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS events_timestamp_idx ON events(timestamp DESC);
            CREATE INDEX IF NOT EXISTS events_device_idx ON events(source_device, timestamp DESC);
            CREATE TABLE IF NOT EXISTS audit_log (
                id TEXT PRIMARY KEY,
                actor TEXT NOT NULL,
                action TEXT NOT NULL,
                resource_id TEXT,
                occurred_at TEXT NOT NULL
            );
        """)


def parse_timestamp(value: object, field: str) -> str:
    if not isinstance(value, str):
        raise ApiError(400, "Invalid request", f"{field} must be an RFC 3339 timestamp.")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("timezone required")
        return parsed.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    except ValueError as exc:
        raise ApiError(400, "Invalid request", f"{field} must be an RFC 3339 timestamp with timezone.") from exc


def validate_consent(consent: object) -> dict:
    if not isinstance(consent, dict) or set(consent) != {"consentId", "policyVersion", "acceptedAt"}:
        raise ApiError(400, "Invalid request", "consent must include consentId, policyVersion, and acceptedAt only.")
    try:
        consent_id = str(uuid.UUID(str(consent["consentId"])))
    except (ValueError, TypeError, AttributeError) as exc:
        raise ApiError(400, "Invalid request", "consentId must be a UUID.") from exc
    policy = consent["policyVersion"]
    if not isinstance(policy, str) or not policy.strip() or len(policy) > 40:
        raise ApiError(400, "Invalid request", "policyVersion must contain 1 to 40 characters.")
    accepted_at = parse_timestamp(consent["acceptedAt"], "acceptedAt")
    if datetime.fromisoformat(accepted_at.replace("Z", "+00:00")) > datetime.now(timezone.utc) + timedelta(minutes=5):
        raise ApiError(400, "Invalid request", "acceptedAt cannot be in the future.")
    return {"consentId": consent_id, "policyVersion": policy, "acceptedAt": accepted_at}


def validate_event(event: object) -> dict:
    required = {"eventId", "timestamp", "category", "direction", "sourceDevice", "consentContext"}
    allowed = required | {"metadata"}
    if not isinstance(event, dict) or not required.issubset(event) or not set(event).issubset(allowed):
        raise ApiError(400, "Invalid event", "Event fields do not match the allowed schema.")
    try:
        event_id = str(uuid.UUID(str(event["eventId"])))
        device_id = str(uuid.UUID(str(event["sourceDevice"])))
    except (ValueError, TypeError, AttributeError) as exc:
        raise ApiError(400, "Invalid event", "eventId and sourceDevice must be UUIDs.") from exc
    category = event["category"]
    direction = event["direction"]
    if not isinstance(category, str) or not isinstance(direction, str) or category not in ALLOWED_CATEGORIES or direction not in ALLOWED_DIRECTIONS:
        raise ApiError(400, "Invalid event", "category or direction is not allowed.")
    consent = validate_consent(event["consentContext"])
    event_timestamp = parse_timestamp(event["timestamp"], "timestamp")
    event_time = datetime.fromisoformat(event_timestamp.replace("Z", "+00:00"))
    consent_time = datetime.fromisoformat(consent["acceptedAt"].replace("Z", "+00:00"))
    if event_time < consent_time:
        raise ApiError(400, "Invalid event", "Event timestamp cannot precede consent.")
    if event_time > datetime.now(timezone.utc) + timedelta(minutes=5):
        raise ApiError(400, "Invalid event", "Event timestamp cannot be in the future.")
    metadata = event.get("metadata", {})
    if not isinstance(metadata, dict) or not set(metadata).issubset({"networkState", "batteryPercent", "appEventCode"}):
        raise ApiError(400, "Invalid event", "metadata contains unsupported fields.")
    if "networkState" in metadata and (not isinstance(metadata["networkState"], str) or metadata["networkState"] not in ALLOWED_NETWORK):
        raise ApiError(400, "Invalid event", "metadata.networkState is not allowed.")
    if "batteryPercent" in metadata and (not isinstance(metadata["batteryPercent"], int) or isinstance(metadata["batteryPercent"], bool) or not 0 <= metadata["batteryPercent"] <= 100):
        raise ApiError(400, "Invalid event", "metadata.batteryPercent must be an integer from 0 to 100.")
    if "appEventCode" in metadata and (not isinstance(metadata["appEventCode"], str) or metadata["appEventCode"] not in ALLOWED_APP_EVENTS):
        raise ApiError(400, "Invalid event", "metadata.appEventCode is not allowed.")
    return {
        "eventId": event_id,
        "timestamp": event_timestamp,
        "category": category,
        "direction": direction,
        "sourceDevice": device_id,
        "consent": consent,
        "metadata": metadata,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "AuditLabMVP/0.1"

    def log_message(self, fmt, *args):
        # Avoid logging bodies, tokens, enrollment codes, or query values.
        print(f"{self.log_date_time_string()} {self.command} {self.path.split('?')[0]} {args[1] if len(args) > 1 else ''}")

    def reply(self, status: int, value: object = None, content_type: str = "application/json") -> None:
        if status == 204:
            self.send_response(status)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if content_type in {"application/json", "application/problem+json"}:
            payload = json.dumps(value if value is not None else {}, ensure_ascii=False).encode("utf-8")
        else:
            payload = str(value or "").encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def reply_csv(self, rows: list[list[str]]) -> None:
        from io import StringIO
        buffer = StringIO(newline="")
        writer = csv.writer(buffer)
        writer.writerow(["eventId", "timestamp", "category", "direction", "sourceDevice", "consentId", "policyVersion", "metadata"])
        writer.writerows(rows)
        payload = buffer.getvalue().encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/csv; charset=utf-8")
        self.send_header("Content-Disposition", 'attachment; filename="auditlab-events.csv"')
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def error(self, status: int, title: str, detail: str = "") -> None:
        self.reply(status, {"title": title, "status": status, "detail": detail}, "application/problem+json")

    def body(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise ApiError(400, "Invalid request", "Invalid Content-Length.") from exc
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ApiError(400, "Invalid request", "Request body is empty or exceeds 256 KiB.")
        try:
            value = json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise ApiError(400, "Invalid request", "Body must be valid JSON.") from exc
        if not isinstance(value, dict):
            raise ApiError(400, "Invalid request", "Body must be a JSON object.")
        return value

    def bearer(self) -> str:
        value = self.headers.get("Authorization", "")
        if not value.startswith("Bearer ") or len(value) > 4096:
            raise ApiError(401, "Unauthorized", "Bearer token required.")
        return value[7:]

    def user(self) -> dict:
        return verified_user_token(self.bearer())

    def device(self, db: sqlite3.Connection) -> sqlite3.Row:
        token = self.bearer()
        row = db.execute("SELECT * FROM devices WHERE token_hash = ?", (digest(token),)).fetchone()
        if row is None:
            raise ApiError(401, "Unauthorized", "Invalid device credential.")
        if row["status"] != "active":
            raise ApiError(409, "Device revoked", "This device has been revoked.")
        return row

    def audit(self, db: sqlite3.Connection, actor: str, action: str, resource: str | None = None) -> None:
        db.execute("INSERT INTO audit_log VALUES (?, ?, ?, ?, ?)", (str(uuid.uuid4()), actor, action, resource, now_iso()))

    def do_GET(self):
        try:
            route = urlparse(self.path)
            path = route.path.rstrip("/") or "/"
            if path == "/" or path == "/index.html":
                page = (ROOT / "static" / "index.html").read_text(encoding="utf-8")
                return self.reply(200, page, "text/html")
            if path == "/health":
                return self.reply(200, {"status": "ok", "mode": "local-demo"})
            if path == "/v1/devices":
                user = self.user()
                with connect() as db:
                    self.audit(db, user["sub"], "device_listed")
                    rows = db.execute("SELECT id, status, last_seen_at FROM devices ORDER BY enrolled_at DESC").fetchall()
                    items = [
                        {"deviceId": row["id"], "status": row["status"], "lastSeenAt": row["last_seen_at"]}
                        for row in rows
                    ]
                # Reply only after the audit row is committed.
                return self.reply(200, {"items": items})
            if path == "/v1/events/export.csv":
                user = self.user()
                with connect() as db:
                    purge_expired_events(db)
                    self.audit(db, user["sub"], "report_exported")
                    rows = db.execute("SELECT * FROM events ORDER BY timestamp DESC LIMIT 10000").fetchall()
                    csv_rows = [[
                        row["id"], row["timestamp"], row["category"], row["direction"], row["source_device"],
                        row["consent_id"], row["policy_version"], row["metadata_json"],
                    ] for row in rows]
                return self.reply_csv(csv_rows)
            if path == "/v1/events":
                user = self.user()
                query = parse_qs(route.query)
                limit = int(query.get("limit", ["50"])[0])
                if not 1 <= limit <= 100:
                    raise ApiError(400, "Invalid request", "limit must be between 1 and 100.")
                clauses, params = [], []
                for key, column in (("device", "source_device"), ("category", "category")):
                    if key in query:
                        clauses.append(f"{column} = ?")
                        params.append(query[key][0])
                for key, op in (("from", ">="), ("to", "<")):
                    if key in query:
                        clauses.append(f"timestamp {op} ?")
                        params.append(parse_timestamp(query[key][0], key))
                where = " WHERE " + " AND ".join(clauses) if clauses else ""
                with connect() as db:
                    purge_expired_events(db)
                    self.audit(db, user["sub"], "events_queried")
                    rows = db.execute(f"SELECT * FROM events{where} ORDER BY timestamp DESC LIMIT ?", (*params, limit)).fetchall()
                    items = []
                    for row in rows:
                        items.append({
                            "eventId": row["id"], "timestamp": row["timestamp"], "category": row["category"],
                            "direction": row["direction"], "sourceDevice": row["source_device"],
                            "consentContext": {"consentId": row["consent_id"], "policyVersion": row["policy_version"], "acceptedAt": row["consent_accepted_at"]},
                            "metadata": json.loads(row["metadata_json"]),
                        })
                return self.reply(200, {"items": items, "nextCursor": None})
            if path == "/v1/audit-log":
                user = self.user()
                query = parse_qs(route.query)
                clauses, params = [], []
                for key, op in (("from", ">="), ("to", "<")):
                    if key in query:
                        clauses.append(f"occurred_at {op} ?")
                        params.append(parse_timestamp(query[key][0], key))
                where = " WHERE " + " AND ".join(clauses) if clauses else ""
                with connect() as db:
                    self.audit(db, user["sub"], "audit_log_queried")
                    rows = db.execute(f"SELECT * FROM audit_log{where} ORDER BY occurred_at DESC LIMIT 100", params).fetchall()
                    audit_items = [
                        {"auditId": r["id"], "actorId": r["actor"], "action": r["action"], "resourceId": r["resource_id"], "occurredAt": r["occurred_at"]}
                        for r in rows
                    ]
                return self.reply(200, {"items": audit_items, "nextCursor": None})
            return self.error(404, "Not found", "No route matches this path.")
        except ApiError as exc:
            return self.error(exc.status, exc.title, exc.detail)
        except (ValueError, sqlite3.Error) as exc:
            return self.error(400 if isinstance(exc, ValueError) else 500, "Request failed", str(exc) if isinstance(exc, ValueError) else "Database operation failed.")

    def do_POST(self):
        try:
            path = urlparse(self.path).path.rstrip("/")
            if path == "/v1/auth/login":
                payload = self.body()
                username, password = payload.get("username"), payload.get("password")
                accounts = {
                    "admin": (os.environ.get("AUDITLAB_ADMIN_PASSWORD", ""), "admin"),
                    "auditor": (os.environ.get("AUDITLAB_AUDITOR_PASSWORD", ""), "auditor"),
                }
                if not isinstance(username, str) or username not in accounts:
                    raise ApiError(401, "Unauthorized", "Invalid username or password.")
                expected, role = accounts.get(username, ("", "")) if isinstance(username, str) else ("", "")
                if not expected:
                    raise ApiError(503, "Configuration required", f"Set the password environment variable for {username or 'the requested user'}.")
                if not isinstance(password, str) or not hmac.compare_digest(password.encode('utf-8'), expected.encode('utf-8')):
                    raise ApiError(401, "Unauthorized", "Invalid username or password.")
                return self.reply(200, {"accessToken": make_user_token(username, role), "tokenType": "Bearer", "expiresIn": TOKEN_TTL_SECONDS, "role": role})

            if path == "/v1/devices/enrollment-code":
                user = self.user()
                if user["role"] != "admin":
                    raise ApiError(403, "Forbidden", "Only admins can create enrollment codes.")
                code = secrets.token_urlsafe(24)
                created = datetime.now(timezone.utc)
                with connect() as db:
                    db.execute("INSERT INTO enrollment_codes VALUES (?, ?, ?, ?, NULL)", (
                        digest(code), user["sub"], created.isoformat().replace("+00:00", "Z"),
                        (created + timedelta(minutes=10)).isoformat().replace("+00:00", "Z"),
                    ))
                    self.audit(db, user["sub"], "enrollment_code_created")
                return self.reply(201, {"code": code, "expiresIn": 600})

            if path == "/v1/devices/enroll":
                payload = self.body()
                if payload.get("consentAcknowledged") is not True:
                    raise ApiError(400, "Consent required", "Explicit consent acknowledgment is required before enrollment.")
                consent = validate_consent(payload.get("consent"))
                code = self.headers.get("X-Enrollment-Code", "")
                if not code or len(code) > 256:
                    raise ApiError(401, "Unauthorized", "Enrollment code required.")
                device_id, raw_token = str(uuid.uuid4()), secrets.token_urlsafe(32)
                with connect() as db:
                    with WRITE_LOCK:
                        db.execute("BEGIN IMMEDIATE")
                        row = db.execute("SELECT * FROM enrollment_codes WHERE code_hash = ?", (digest(code),)).fetchone()
                        if row is None or row["used_at"] is not None or row["expires_at"] < now_iso():
                            raise ApiError(401, "Unauthorized", "Enrollment code is invalid, expired, or already used.")
                        db.execute("UPDATE enrollment_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL", (now_iso(), digest(code)))
                        db.execute("INSERT INTO devices VALUES (?, 'active', ?, ?, ?, ?, ?, NULL)", (
                            device_id, digest(raw_token), consent["consentId"], consent["policyVersion"], consent["acceptedAt"], now_iso(),
                        ))
                return self.reply(201, {"deviceId": device_id, "deviceToken": raw_token, "status": "active"})

            if path == "/v1/events":
                payload = self.body()
                if set(payload) != {"events"} or not isinstance(payload["events"], list) or not 1 <= len(payload["events"]) <= 100:
                    raise ApiError(400, "Invalid request", "events must contain between 1 and 100 entries.")
                events = [validate_event(item) for item in payload["events"]]
                with connect() as db:
                    purge_expired_events(db)
                    device = self.device(db)
                    accepted = duplicates = 0
                    for event in events:
                        if event["sourceDevice"] != device["id"]:
                            raise ApiError(400, "Invalid event", "sourceDevice must match the authenticated device.")
                        if (event["consent"]["consentId"] != device["consent_id"]
                                or event["consent"]["policyVersion"] != device["policy_version"]
                                or event["consent"]["acceptedAt"] != device["consent_accepted_at"]):
                            raise ApiError(409, "Consent mismatch", "Event consent does not match the device's active consent.")
                        try:
                            db.execute("INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", (
                                event["eventId"], event["timestamp"], event["category"], event["direction"], event["sourceDevice"],
                                event["consent"]["consentId"], event["consent"]["policyVersion"], event["consent"]["acceptedAt"],
                                json.dumps(event["metadata"], separators=(",", ":")),
                            ))
                            accepted += 1
                        except sqlite3.IntegrityError:
                            existing = db.execute("SELECT * FROM events WHERE id = ?", (event["eventId"],)).fetchone()
                            if existing:
                                stored = (existing['timestamp'], existing['category'], existing['direction'],
                                          existing['source_device'], existing['consent_id'], existing['policy_version'],
                                          existing['consent_accepted_at'], json.loads(existing['metadata_json']))
                                incoming = (event['timestamp'], event['category'], event['direction'],
                                            event['sourceDevice'], event['consent']['consentId'], event['consent']['policyVersion'],
                                            event['consent']['acceptedAt'], event['metadata'])
                                if stored != incoming:
                                    raise ApiError(409, "Event conflict", "This eventId already exists with different content.")
                                duplicates += 1
                            else:
                                raise ApiError(400, "Invalid event", "Event refers to an unknown device.")
                    db.execute("UPDATE devices SET last_seen_at = ? WHERE id = ?", (now_iso(), device["id"]))
                return self.reply(202, {"accepted": accepted, "duplicates": duplicates})

            match = re.fullmatch(r"/v1/devices/([0-9a-fA-F-]{36})/revoke", path)
            if match:
                user = self.user()
                if user["role"] != "admin":
                    raise ApiError(403, "Forbidden", "Only admins can revoke devices.")
                device_id = str(uuid.UUID(match.group(1)))
                with connect() as db:
                    cursor = db.execute("UPDATE devices SET status = 'revoked', token_hash = ? WHERE id = ? AND status = 'active'", (digest(secrets.token_urlsafe(32)), device_id))
                    if cursor.rowcount == 0:
                        if db.execute("SELECT 1 FROM devices WHERE id = ?", (device_id,)).fetchone() is None:
                            raise ApiError(404, "Not found", "Device not found.")
                    self.audit(db, user["sub"], "device_revoked", device_id)
                return self.reply(204, None)
            match = re.fullmatch(r"/v1/devices/([0-9a-fA-F-]{36})/consent-revoke", path)
            if match:
                device_id = str(uuid.UUID(match.group(1)))
                with connect() as db:
                    device = self.device(db)
                    if device["id"] != device_id:
                        raise ApiError(403, "Forbidden", "A device can only revoke its own consent.")
                    db.execute("UPDATE devices SET status = 'revoked', token_hash = ? WHERE id = ?", (digest(secrets.token_urlsafe(32)), device_id))
                    self.audit(db, device_id, "consent_revoked", device_id)
                return self.reply(204, None)
            return self.error(404, "Not found", "No route matches this path.")
        except ApiError as exc:
            return self.error(exc.status, exc.title, exc.detail)
        except (ValueError, sqlite3.Error) as exc:
            return self.error(400 if isinstance(exc, ValueError) else 500, "Request failed", str(exc) if isinstance(exc, ValueError) else "Database operation failed.")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Allow", "GET, POST, OPTIONS")
        self.send_header("Content-Length", "0")
        self.end_headers()


def main() -> None:
    initialize()
    host = os.environ.get("AUDITLAB_BIND", "127.0.0.1")
    port = int(os.environ.get("AUDITLAB_PORT", "8080"))
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("Local MVP only: bind to loopback. Put a production deployment behind HTTPS and a hardened server.")
    print(f"AuditLab local MVP listening on http://{host}:{port}")
    ThreadingHTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
