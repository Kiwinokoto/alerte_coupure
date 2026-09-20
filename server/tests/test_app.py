import sqlite3
import tempfile
import unittest
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

import app as powerwatch


class PowerWatchServerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.original_db = powerwatch.DB_PATH
        powerwatch.DB_PATH = Path(self.temp.name) / "powerwatch.db"
        powerwatch.init_db()

    def tearDown(self):
        powerwatch.DB_PATH = self.original_db
        self.temp.cleanup()

    def payload(self, event, external_power=True):
        return {
            "schema_version": 1,
            "event": event,
            "timestamp_utc": "2026-09-19T14:30:00Z",
            "device_name": "Restaurant test",
            "installation_id": "test-device",
            "external_power": external_power,
            "battery_percent": 88,
            "reason": "unit_test",
            "android_sdk": 36,
        }

    def test_alert_summary_exposes_configured_email_destination(self):
        with (
            mock.patch.object(powerwatch, "SMTP_HOST", "smtp.example.test"),
            mock.patch.object(powerwatch, "SMTP_FROM", "powerwatch@example.test"),
            mock.patch.object(powerwatch, "ALERT_TO", ["owner@example.test"]),
        ):
            self.assertEqual(
                powerwatch.alert_summary(),
                "E-mail → owner@example.test",
            )

    def test_alert_summary_is_explicit_when_unconfigured(self):
        with (
            mock.patch.object(powerwatch, "SMTP_HOST", ""),
            mock.patch.object(powerwatch, "SMTP_FROM", ""),
            mock.patch.object(powerwatch, "ALERT_TO", []),
        ):
            self.assertEqual(
                powerwatch.alert_summary(),
                "Aucune alerte distante configurée",
            )

    def test_monitoring_started_arms_device(self):
        previous, recovered, duplicate = powerwatch.record_event(
            self.payload("monitoring_started", True)
        )

        self.assertIsNone(previous)
        self.assertFalse(recovered)

        with closing(powerwatch.connect()) as db, db:
            row = db.execute(
                "SELECT armed, external_power FROM devices WHERE installation_id = ?",
                ("test-device",),
            ).fetchone()

        self.assertEqual(row["armed"], 1)
        self.assertEqual(row["external_power"], 1)

    def test_heartbeat_can_recover_missed_power_transition(self):
        powerwatch.record_event(self.payload("monitoring_started", True))
        previous, recovered, duplicate = powerwatch.record_event(
            self.payload("heartbeat", False)
        )

        with mock.patch.object(powerwatch, "send_alert") as alert:
            powerwatch.handle_alerts(
                self.payload("heartbeat", False),
                previous,
                recovered,
            )

        alert.assert_called_once()
        self.assertEqual(alert.call_args.args[0], "power_lost")

    def test_watchdog_marks_only_armed_stale_device_offline(self):
        powerwatch.record_event(self.payload("monitoring_started", True))
        old = datetime(2020, 1, 1, tzinfo=timezone.utc).isoformat()

        with closing(powerwatch.connect()) as db, db:
            db.execute(
                "UPDATE devices SET last_seen = ? WHERE installation_id = ?",
                (old, "test-device"),
            )

        with mock.patch.object(powerwatch, "send_alert") as alert:
            stale = powerwatch.watchdog_once(
                now_timestamp=datetime(2026, 9, 19, tzinfo=timezone.utc).timestamp()
            )

        self.assertEqual(stale, ["test-device"])
        self.assertEqual(alert.call_args.args[0], "probe_offline")

        with closing(powerwatch.connect()) as db, db:
            row = db.execute(
                "SELECT offline_alerted FROM devices WHERE installation_id = ?",
                ("test-device",),
            ).fetchone()
        self.assertEqual(row["offline_alerted"], 1)

    def test_stopped_monitor_is_not_marked_offline(self):
        powerwatch.record_event(self.payload("monitoring_started", True))
        powerwatch.record_event(self.payload("monitoring_stopped", True))
        old = datetime(2020, 1, 1, tzinfo=timezone.utc).isoformat()

        with closing(powerwatch.connect()) as db, db:
            db.execute(
                "UPDATE devices SET last_seen = ? WHERE installation_id = ?",
                (old, "test-device"),
            )

        with mock.patch.object(powerwatch, "send_alert") as alert:
            stale = powerwatch.watchdog_once(
                now_timestamp=datetime(2026, 9, 19, tzinfo=timezone.utc).timestamp()
            )

        self.assertEqual(stale, [])
        alert.assert_not_called()

    def test_first_event_after_offline_is_recovery(self):
        powerwatch.record_event(self.payload("monitoring_started", True))
        with closing(powerwatch.connect()) as db, db:
            db.execute(
                "UPDATE devices SET offline_alerted = 1 WHERE installation_id = ?",
                ("test-device",),
            )

        previous, recovered, duplicate = powerwatch.record_event(
            self.payload("heartbeat", True)
        )

        self.assertTrue(recovered)
        with mock.patch.object(powerwatch, "send_alert") as alert:
            powerwatch.handle_alerts(
                self.payload("heartbeat", True),
                previous,
                recovered,
            )

        self.assertEqual(alert.call_args.args[0], "probe_online")


    def test_init_db_migrates_legacy_events_table_without_losing_history(self):
        powerwatch.DB_PATH.unlink()

        with sqlite3.connect(powerwatch.DB_PATH) as db:
            db.executescript(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    received_at TEXT NOT NULL,
                    event_timestamp TEXT,
                    installation_id TEXT NOT NULL,
                    device_name TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    external_power INTEGER,
                    battery_percent INTEGER,
                    reason TEXT,
                    payload_json TEXT NOT NULL
                );
                INSERT INTO events (
                    received_at, event_timestamp, installation_id, device_name,
                    event_type, external_power, battery_percent, reason, payload_json
                ) VALUES (
                    '2026-09-19T14:30:00Z', '2026-09-19T14:30:00Z',
                    'legacy-device', 'Legacy restaurant', 'heartbeat',
                    1, 88, 'legacy', '{"legacy": true}'
                );
                INSERT INTO events (
                    received_at, event_timestamp, installation_id, device_name,
                    event_type, external_power, battery_percent, reason, payload_json
                ) VALUES (
                    '2026-09-19T14:31:00Z', '2026-09-19T14:31:00Z',
                    'legacy-device', 'Legacy restaurant', 'power_lost',
                    0, 87, 'legacy', '{"event_id": "historic-event-id"}'
                );
                INSERT INTO events (
                    received_at, event_timestamp, installation_id, device_name,
                    event_type, external_power, battery_percent, reason, payload_json
                ) VALUES (
                    '2026-09-19T14:32:00Z', '2026-09-19T14:32:00Z',
                    'legacy-device', 'Legacy restaurant', 'power_lost',
                    0, 86, 'legacy duplicate', '{"event_id": "historic-event-id"}'
                );
                """
            )

        powerwatch.init_db()
        powerwatch.init_db()

        with closing(powerwatch.connect()) as db:
            columns = {row["name"] for row in db.execute("PRAGMA table_info(events)")}
            indexes = {row["name"] for row in db.execute("PRAGMA index_list(events)")}
            legacy = db.execute(
                "SELECT installation_id, payload_json, event_id FROM events WHERE id = 1"
            ).fetchone()
            event_count = db.execute("SELECT COUNT(*) FROM events").fetchone()[0]
            backfilled_count = db.execute(
                "SELECT COUNT(*) FROM events WHERE event_id = ?",
                ("historic-event-id",),
            ).fetchone()[0]

        self.assertIn("event_id", columns)
        self.assertIn("idx_events_event_id", indexes)
        self.assertEqual(event_count, 3)
        self.assertEqual(legacy["installation_id"], "legacy-device")
        self.assertEqual(legacy["payload_json"], '{"legacy": true}')
        self.assertIsNone(legacy["event_id"])
        self.assertEqual(backfilled_count, 1)

    def test_duplicate_event_id_is_recorded_once(self):
        payload = self.payload("power_lost", False)
        payload["event_id"] = "stable-event-id"

        previous, recovered, duplicate = powerwatch.record_event(payload)
        self.assertFalse(duplicate)
        with mock.patch.object(powerwatch, "send_alert") as alert:
            powerwatch.handle_alerts(payload, previous, recovered)
        alert.assert_called_once()

        _, _, duplicate = powerwatch.record_event(payload)
        self.assertTrue(duplicate)
        with closing(powerwatch.connect()) as db:
            count = db.execute(
                "SELECT COUNT(*) FROM events WHERE event_id = ?",
                ("stable-event-id",),
            ).fetchone()[0]
        self.assertEqual(count, 1)


if __name__ == "__main__":
    unittest.main()
