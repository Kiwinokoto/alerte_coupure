# PowerWatch Android V1

PowerWatch is the Android continuation of the original Windows power-loss proof of concept in this repository.

The application is intentionally designed first for a **normal Android phone or tablet**. Android Enterprise / Device Owner is not required for this V1.

## Current behavior

When monitoring is armed:

1. `MonitorService` runs as a foreground service with a persistent notification.
2. It listens for Android power-connected / power-disconnected broadcasts.
3. It also re-reads `ACTION_BATTERY_CHANGED / EXTRA_PLUGGED` every 30 seconds as a consistency check.
4. A change must remain stable for 15 seconds before it becomes an outage/restoration event.
5. The transition is written to the local event log.
6. If an HTTPS webhook is configured, the event is posted remotely.
7. A heartbeat is posted every 2 minutes while the service is running.
8. If Android restarts while monitoring was armed, `BootReceiver` starts the foreground service again after `BOOT_COMPLETED`.

The monitor uses Android's **external power state**. A full battery that has temporarily stopped charging should therefore remain "mains present" as long as the device is still externally powered.

## Build

Requirements:

- Android Studio with JDK 17;
- Android SDK 35 installed;
- a physical Android 8.0+ device is strongly recommended for testing.

Open the `android/` directory as the project in Android Studio, let Gradle sync, then build/install the `app` module.

This first commit intentionally does not include a generated Gradle wrapper binary. Android Studio can sync the project using the configured Android Gradle Plugin; adding a wrapper is the next build-hardening step once the project has been built on the development machine.

## First test

1. Install the app on a spare Android phone with a battery.
2. Grant notification permission.
3. Open the battery-optimization settings from the app and configure the test device so PowerWatch is not aggressively restricted.
4. Enter a device/site name.
5. Optionally enter an HTTPS webhook.
6. Tap **Activer la surveillance**.
7. Verify that the persistent PowerWatch notification is visible.
8. Turn the screen off.
9. Remove mains power from the charger without touching the USB cable.
10. After 15 seconds, reopen the app and verify a `COUPURE SECTEUR` event.
11. Restore mains power and verify `COURANT RÉTABLI`.
12. Reboot the phone while monitoring is armed and verify that the notification returns automatically.

## Webhook payload

Example:

```json
{
  "schema_version": 1,
  "event": "power_lost",
  "timestamp_utc": "2026-09-19T14:00:00Z",
  "device_name": "Restaurant République",
  "installation_id": "generated-on-device",
  "external_power": false,
  "battery_percent": 96,
  "reason": "broadcast:android.intent.action.ACTION_POWER_DISCONNECTED",
  "android_sdk": 35
}
```

Events currently emitted:

- `monitoring_started`
- `monitoring_stopped`
- `power_lost`
- `power_restored`
- `heartbeat`
- `test`

Power-loss/restoration and manual events retry delivery a few times so a Wi-Fi-to-mobile-data transition has time to complete. Heartbeats use a single attempt.

## What V1 does not yet solve

- no production alert backend is included yet;
- no direct SMS sending (avoids Google Play SMS-permission constraints);
- no signed release APK/AAB pipeline yet;
- no Device Owner / Android Enterprise mode;
- no OEM-specific hardening for Samsung/Xiaomi/etc.;
- no server-side liveness timeout yet;
- no long-duration device qualification tests yet.

Those are deliberate next steps. The first objective is to establish whether a normal Android foreground service is reliable enough on a small set of supported phones before adding enterprise-device management.

## Reliability test plan

Before calling this production-ready, test at least:

- screen off for multiple nights;
- battery full / adaptive charging active;
- Wi-Fi loss with mobile-data fallback;
- airplane mode then recovery;
- app process killed by Android;
- reboot while armed;
- charger unplug/replug;
- short power glitches below the 15-second debounce;
- outage lasting several hours;
- low-battery behavior during an outage;
- notification permission denied;
- battery saver / vendor-specific battery restrictions.

The long-term goal is measured reliability, not merely "it worked once on one phone."
