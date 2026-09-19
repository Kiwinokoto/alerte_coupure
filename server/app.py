import hmac
import json
import os
import smtplib
import sqlite3
import threading
import time
from contextlib import asynccontextmanager, closing
from datetime import datetime, timezone
from email.message import EmailMessage
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

DB_PATH = Path(os.getenv("POWERWATCH_DB", "/data/powerwatch.db"))
INGEST_TOKEN = os.getenv("POWERWATCH_INGEST_TOKEN", "")
OFFLINE_SECONDS = max(180, int(os.getenv("POWERWATCH_OFFLINE_SECONDS", "420")))
WATCHDOG_SECONDS = max(30, int(os.getenv("POWERWATCH_WATCHDOG_SECONDS", "60")))

SMTP_HOST = os.getenv("POWERWATCH_SMTP_HOST", "")
SMTP_PORT = int(os.getenv("POWERWATCH_SMTP_PORT", "587"))
SMTP_USER = os.getenv("POWERWATCH_SMTP_USER", "")
SMTP_PASSWORD = os.getenv("POWERWATCH_SMTP_PASSWORD", "")
SMTP_FROM = os.getenv("POWERWATCH_SMTP_FROM", "")
ALERT_TO = [
    value.strip()
    for value in os.getenv("POWERWATCH_ALERT_TO", "").split(",")
    if value.strip()
]
SMTP_STARTTLS = os.getenv("POWERWATCH_SMTP_STARTTLS", "1") == "1"

ALLOWED_EVENTS = {
    "monitoring_started",
    "monitoring_stopped",
    "power_lost",
    "power_restored",
    "heartbeat",
    "test",
}

_stop_event = threading.Event()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def init_db() -> None:
    with closing(connect()) as db, db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS devices (
                installation_id TEXT PRIMARY KEY,
                device_name TEXT NOT NULL,
                last_seen TEXT NOT NULL,
                external_power INTEGER,
                battery_percent INTEGER,
                armed INTEGER NOT NULL DEFAULT 0,
                offline_alerted INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                received_at TEXT NOT NULL,
                event_timestamp TEXT,
                installation_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                event_type TEXT NOT NULL,
                external_power INTEGER,
                battery_percent INTEGER,
                reason TEXT,
                payload_json TEXT NOT NULL,
                event_id TEXT
            );

            CREATE INDEX IF NOT EXISTS idx_events_installation_received
                ON events (installation_id, received_at DESC);
            """
        )
        columns = {row["name"] for row in db.execute("PRAGMA table_info(events)")}
        if "event_id" not in columns:
            db.execute("ALTER TABLE events ADD COLUMN event_id TEXT")
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_events_event_id ON events (event_id) WHERE event_id IS NOT NULL")


def bool_to_db(value: Any) -> int | None:
    if value is None:
        return None
    return 1 if bool(value) else 0


def db_to_bool(value: Any) -> bool | None:
    if value is None:
        return None
    return bool(value)


def alert_configured() -> bool:
    return bool(SMTP_HOST and SMTP_FROM and ALERT_TO)


def alert_summary() -> str:
    if alert_configured():
        return "E-mail → " + ", ".join(ALERT_TO)
    if ALERT_TO:
        return "E-mail prévu → " + ", ".join(ALERT_TO) + " (SMTP non configuré)"
    return "Aucune alerte distante configurée"


def require_ingest_token(token: str | None) -> None:
    if not INGEST_TOKEN:
        raise HTTPException(status_code=503, detail="Ingest token is not configured.")
    if not token or not hmac.compare_digest(token, INGEST_TOKEN):
        raise HTTPException(status_code=401, detail="Invalid token.")


def send_alert(
    kind: str,
    *,
    device_name: str,
    installation_id: str,
    external_power: bool | None,
    battery_percent: int | None,
    detail: str,
) -> None:
    prefix = {
        "power_lost": "COUPURE SECTEUR",
        "power_restored": "COURANT RÉTABLI",
        "probe_offline": "SONDE INJOIGNABLE",
        "probe_online": "SONDE DE NOUVEAU JOIGNABLE",
        "power_absent_on_start": "SURVEILLANCE DÉMARRÉE SANS SECTEUR",
    }.get(kind, kind.upper())

    if not alert_configured():
        print(
            f"[powerwatch] alert not configured: {prefix} / {device_name} / {detail}",
            flush=True,
        )
        return

    message = EmailMessage()
    message["Subject"] = f"[PowerWatch] {prefix} — {device_name}"
    message["From"] = SMTP_FROM
    message["To"] = ", ".join(ALERT_TO)
    message.set_content(
        "\n".join(
            [
                prefix,
                "",
                f"Site : {device_name}",
                f"Installation : {installation_id}",
                f"Secteur : {external_power}",
                f"Batterie : {battery_percent if battery_percent is not None else 'inconnue'} %",
                f"Heure serveur UTC : {utc_now()}",
                f"Détail : {detail}",
            ]
        )
    )

    try:
        if SMTP_PORT == 465:
            smtp: smtplib.SMTP = smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=15)
        else:
            smtp = smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=15)
        with smtp:
            if SMTP_PORT != 465 and SMTP_STARTTLS:
                smtp.starttls()
            if SMTP_USER:
                smtp.login(SMTP_USER, SMTP_PASSWORD)
            smtp.send_message(message)
        print(f"[powerwatch] alert sent: {prefix} / {device_name}", flush=True)
    except Exception as error:
        print(
            f"[powerwatch] alert delivery failed: {prefix} / {device_name}: {error}",
            flush=True,
        )


def validate_payload(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="JSON object required.")

    event_type = str(payload.get("event", "")).strip()
    installation_id = str(payload.get("installation_id", "")).strip()
    device_name = str(payload.get("device_name", "")).strip()

    if event_type not in ALLOWED_EVENTS:
        raise HTTPException(status_code=400, detail="Unsupported event.")
    if not installation_id or len(installation_id) > 128:
        raise HTTPException(status_code=400, detail="Invalid installation_id.")
    if not device_name or len(device_name) > 200:
        raise HTTPException(status_code=400, detail="Invalid device_name.")

    external_power = payload.get("external_power")
    if external_power is not None and not isinstance(external_power, bool):
        raise HTTPException(status_code=400, detail="external_power must be boolean or null.")

    battery = payload.get("battery_percent")
    if battery is not None:
        if not isinstance(battery, int) or isinstance(battery, bool) or not 0 <= battery <= 100:
            raise HTTPException(status_code=400, detail="Invalid battery_percent.")

    return payload


def record_event(payload: dict[str, Any]) -> tuple[sqlite3.Row | None, bool, bool]:
    received_at = utc_now()
    installation_id = str(payload["installation_id"])
    device_name = str(payload["device_name"])
    event_type = str(payload["event"])
    external_power = payload.get("external_power")
    battery_percent = payload.get("battery_percent")
    reason = str(payload.get("reason", ""))[:500]
    event_timestamp = str(payload.get("timestamp_utc", ""))[:100]
    event_id = str(payload.get("event_id", "")).strip()[:128] or None

    with closing(connect()) as db, db:
        previous = db.execute(
            "SELECT * FROM devices WHERE installation_id = ?",
            (installation_id,),
        ).fetchone()

        if event_type == "monitoring_stopped":
            armed = 0
        elif event_type in {"monitoring_started", "power_lost", "power_restored", "heartbeat"}:
            armed = 1
        else:
            armed = int(previous["armed"]) if previous is not None else 0

        event_insert = db.execute(
            """
            INSERT OR IGNORE INTO events (
                received_at, event_timestamp, installation_id, device_name,
                event_type, external_power, battery_percent, reason, payload_json, event_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                received_at,
                event_timestamp,
                installation_id,
                device_name,
                event_type,
                bool_to_db(external_power),
                battery_percent,
                reason,
                json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
                event_id,
            ),
        )

        if event_id is not None and event_insert.rowcount == 0:
            return None, False, True

        db.execute(
            """
            INSERT INTO devices (
                installation_id, device_name, last_seen, external_power,
                battery_percent, armed, offline_alerted
            ) VALUES (?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(installation_id) DO UPDATE SET
                device_name = excluded.device_name,
                last_seen = excluded.last_seen,
                external_power = excluded.external_power,
                battery_percent = excluded.battery_percent,
                armed = excluded.armed,
                offline_alerted = 0
            """,
            (
                installation_id,
                device_name,
                received_at,
                bool_to_db(external_power),
                battery_percent,
                armed,
            ),
        )

    recovered = previous is not None and bool(previous["offline_alerted"])
    return previous, recovered, False


def handle_alerts(payload: dict[str, Any], previous: sqlite3.Row | None, recovered: bool) -> None:
    event_type = str(payload["event"])
    device_name = str(payload["device_name"])
    installation_id = str(payload["installation_id"])
    external_power = payload.get("external_power")
    battery_percent = payload.get("battery_percent")
    reason = str(payload.get("reason", ""))

    if recovered:
        send_alert(
            "probe_online",
            device_name=device_name,
            installation_id=installation_id,
            external_power=external_power,
            battery_percent=battery_percent,
            detail="Un heartbeat ou événement a été reçu après une période hors ligne.",
        )

    if event_type in {"power_lost", "power_restored"}:
        send_alert(
            event_type,
            device_name=device_name,
            installation_id=installation_id,
            external_power=external_power,
            battery_percent=battery_percent,
            detail=reason or event_type,
        )
        return

    previous_power = db_to_bool(previous["external_power"]) if previous is not None else None
    previous_armed = bool(previous["armed"]) if previous is not None else False

    if (
        event_type == "heartbeat"
        and previous is not None
        and previous_armed
        and external_power is not None
        and previous_power is not None
        and external_power != previous_power
    ):
        inferred = "power_restored" if external_power else "power_lost"
        send_alert(
            inferred,
            device_name=device_name,
            installation_id=installation_id,
            external_power=external_power,
            battery_percent=battery_percent,
            detail="Transition inférée depuis un heartbeat après perte de l'événement explicite.",
        )
    elif event_type == "monitoring_started" and external_power is False:
        send_alert(
            "power_absent_on_start",
            device_name=device_name,
            installation_id=installation_id,
            external_power=external_power,
            battery_percent=battery_percent,
            detail="PowerWatch a été armé alors que le téléphone était déjà sur batterie.",
        )


def watchdog_once(now_timestamp: float | None = None) -> list[str]:
    cutoff = (time.time() if now_timestamp is None else now_timestamp) - OFFLINE_SECONDS
    stale: list[sqlite3.Row] = []

    with closing(connect()) as db, db:
        rows = db.execute(
            """
            SELECT * FROM devices
            WHERE armed = 1 AND offline_alerted = 0
            """
        ).fetchall()

        for row in rows:
            try:
                last_seen = datetime.fromisoformat(str(row["last_seen"])).timestamp()
            except ValueError:
                continue
            if last_seen < cutoff:
                db.execute(
                    "UPDATE devices SET offline_alerted = 1 WHERE installation_id = ?",
                    (row["installation_id"],),
                )
                stale.append(row)

    for row in stale:
        send_alert(
            "probe_offline",
            device_name=str(row["device_name"]),
            installation_id=str(row["installation_id"]),
            external_power=db_to_bool(row["external_power"]),
            battery_percent=row["battery_percent"],
            detail=f"Aucun heartbeat depuis plus de {OFFLINE_SECONDS} secondes.",
        )

    return [str(row["installation_id"]) for row in stale]


def watchdog_loop() -> None:
    while not _stop_event.wait(WATCHDOG_SECONDS):
        watchdog_once()


@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    _stop_event.clear()
    thread = threading.Thread(target=watchdog_loop, name="powerwatch-watchdog", daemon=True)
    thread.start()
    try:
        yield
    finally:
        _stop_event.set()
        thread.join(timeout=2)


app = FastAPI(title="PowerWatch API", version="0.1.0", lifespan=lifespan)


@app.get("/healthz")
async def healthz() -> JSONResponse:
    return JSONResponse(
        {
            "status": "ok",
            "alert_delivery_configured": alert_configured(),
            "offline_after_seconds": OFFLINE_SECONDS,
        }
    )


@app.get("/api/v1/status")
async def remote_status(
    x_powerwatch_token: str | None = Header(default=None),
) -> JSONResponse:
    require_ingest_token(x_powerwatch_token)
    return JSONResponse(
        {
            "status": "ok",
            "alert_delivery_configured": alert_configured(),
            "alert_summary": alert_summary(),
            "offline_after_seconds": OFFLINE_SECONDS,
        }
    )


@app.post("/api/v1/events")
async def ingest_event(
    request: Request,
    x_powerwatch_token: str | None = Header(default=None),
) -> JSONResponse:
    require_ingest_token(x_powerwatch_token)

    try:
        payload = validate_payload(await request.json())
    except json.JSONDecodeError as error:
        raise HTTPException(status_code=400, detail="Invalid JSON.") from error

    previous, recovered, duplicate = record_event(payload)
    if not duplicate:
        handle_alerts(payload, previous, recovered)

    return JSONResponse({"status": "ok", "duplicate": duplicate, "server_time": utc_now()})
