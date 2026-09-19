# PowerWatch Android V1

PowerWatch is the Android continuation of the original Windows power-loss proof of concept in this repository.

The application is intentionally designed first for a **normal Android phone or tablet**. Android Enterprise / Device Owner is not required for this V1. The project targets Android 16 / API 36.

Opening the application does **not** silently arm monitoring. Arming remains an explicit user action. Once armed, the main screen shows that surveillance is active and since when, while the persistent notification confirms background operation. The app also queries the authenticated server status endpoint to display the currently configured remote alert channel/destination.

## Current behavior

When monitoring is armed:

1. `MonitorService` runs as a foreground service with a persistent notification.
2. It listens for Android power-connected / power-disconnected broadcasts.
3. It re-reads `ACTION_BATTERY_CHANGED / EXTRA_PLUGGED` every 30 seconds as a consistency check.
4. A change must remain stable for 15 seconds before it becomes an outage/restoration event.
5. The transition is written to the local event log.
6. If an HTTPS webhook is configured, the event is posted remotely.
7. A heartbeat is posted every 2 minutes while the service is running.
8. If Android restarts while monitoring was armed, `BootReceiver` starts the foreground service again after `BOOT_COMPLETED`.

The monitor uses Android's **external power state**. A full battery that has temporarily stopped charging should therefore remain "mains present" as long as the device is still externally powered.

## Why keep a backend if the phone can detect outages itself?

The phone and the backend cover **different failure modes**:

- the phone is the best place to detect a mains transition immediately, because its own battery keeps it alive when the site loses power;
- the backend receives events, keeps history and centralizes normal alert delivery;
- the backend also watches the **absence of heartbeats**. This catches failures the phone cannot report itself, such as the phone dying, the app being killed permanently, the SIM/data path failing, or the entire device becoming unreachable.

The VPS is therefore useful, but it must **not become a single point of failure** for outage detection.

Planned degraded-mode architecture:

1. **Normal path:** phone detects the event → HTTPS backend → configured remote alert channels.
2. **Local truth always survives:** the phone records the outage/restoration locally even when the backend is unreachable.
3. **Persistent outbox:** failed events remain queued locally and are replayed to the backend when connectivity returns.
4. **Direct critical fallback:** if a confirmed power-loss event cannot reach the backend after the normal retry window, the phone should be able to use an independent channel such as a direct SMS through its SIM.
5. **Symmetric recovery:** if a direct fallback alert was sent for an outage, restoration should also be sent through the fallback channel when the backend remains unavailable.
6. **Backend-silence warning:** loss of backend connectivity while mains is still present is a degraded state, not a power outage. The UI should show it distinctly and a remote fallback warning, if enabled, should only fire after a sustained timeout to avoid noisy alerts.

This gives the system two complementary safety nets: **the phone can still report a site power failure when the VPS is unavailable, while the VPS can still report that the phone itself has disappeared**.

## Install a prebuilt APK

For testers who receive a PowerWatch APK directly rather than through an app store:

1. Copy or download the `.apk` file to the Android device.
2. Open it from **Files / Downloads** (or from the browser that downloaded it).
3. Android may run a security scan or show a Play Protect prompt before installation. This is a normal Android security check for sideloaded applications.
4. If prompted, allow **Install unknown apps** for the specific app opening the APK (for example Files or the browser). On Android 8.0+, this permission is granted per source app rather than as one global switch.
5. Confirm **Install** or **Update**.
6. Open PowerWatch and grant the permissions it requests.

Only install APKs obtained from a trusted source. A tester may revoke the **Install unknown apps** permission again after installation if it is no longer needed.

## Build

Requirements:

- Android Studio with JDK 17;
- Android SDK 36 installed;
- a physical Android 8.0+ device is strongly recommended for testing.

Open the `android/` directory as the project in Android Studio, let Gradle sync, then build/install the `app` module.

The repository does not yet contain a generated Gradle wrapper binary. Android Studio can sync the project using the configured Android Gradle Plugin; generating and committing the wrapper after the first workstation build remains a build-hardening task.

## First test

1. Install the app on a spare Android phone with a battery.
2. Grant notification permission.
3. Open the battery-optimization settings from the app and configure the test device so PowerWatch is not aggressively restricted.
4. Enter a device/site name.
5. Enter the HTTPS endpoint and server token if remote monitoring is enabled.
6. Tap **Activer la surveillance**.
7. Verify that the persistent PowerWatch notification is visible.
8. Turn the screen off.
9. Remove mains power from the charger without touching the USB cable.
10. After 15 seconds, verify a `COUPURE SECTEUR` event.
11. Restore mains power and verify `COURANT RÉTABLI`.
12. Reboot the phone while monitoring is armed and verify that the notification returns automatically.

The current development backend endpoint is:

```text
https://gauss.kiwinokoto.com/powerwatch-api/api/v1/events
```

It requires the matching `X-PowerWatch-Token` configured on the VPS.

The same authenticated server exposes `/api/v1/status` so the phone can display the effective alert route (for example `E-mail → owner@example.test`). Destination changes remain server-admin operations in V1; a probe is deliberately not allowed to reconfigure alert recipients.

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
  "android_sdk": 36
}
```

Events currently emitted:

- `monitoring_started`
- `monitoring_stopped`
- `power_lost`
- `power_restored`
- `heartbeat`
- `test`

Power-loss/restoration and manual events retry delivery a few times so a Wi-Fi-to-mobile-data transition has time to complete. Heartbeats use a single attempt. The server also compares heartbeat state with the previous state, so a later heartbeat can recover a power transition whose explicit event could not be delivered.

## Remaining V1 work

- continue physical-device and long-duration reliability testing;
- configure and test outbound alert delivery;
- add a persistent on-device event outbox and design/test direct-SMS fallback for confirmed power loss when backend delivery fails;
- design an explicit **Désarmer et quitter** action with a danger-style confirmation; swiping the UI away must continue to leave an armed foreground monitor running;
- generate/commit the Gradle wrapper;
- decide the small set of officially supported phone models;
- assign a dedicated production hostname;
- add Device Owner only if normal-Android reliability testing shows a real need.

## Post-V1 / productisation

The current V1 is an Angela-first proof of concept. If reliability testing validates the approach, a business-ready follow-up should avoid exposing webhook URLs or shared probe tokens to ordinary users. Candidate work includes:

- simple onboarding with an account, activation code or pre-provisioned device identity;
- automatic per-device/site provisioning instead of manual endpoint/token entry;
- secure separation between probe credentials and administrator credentials;
- customer/site management with configurable alert recipients and channels;
- production signing, release distribution and update strategy;
- deciding whether this remains **PowerWatch V2** or becomes a separate product built from the validated PowerWatch core.

## Reliability test plan

Before calling this production-ready, test at least:

- screen off for multiple nights;
- battery full / adaptive charging active;
- Wi-Fi loss with mobile-data fallback;
- backend unavailable while mains remains present;
- backend unavailable during a real power loss, including direct fallback alert and later event replay;
- airplane mode then recovery;
- app process killed by Android;
- reboot while armed;
- charger unplug/replug;
- short power glitches below the 15-second debounce;
- outage lasting several hours;
- low-battery behavior during an outage;
- notification permission denied;
- battery saver / vendor-specific battery restrictions.

The goal is measured reliability, not merely "it worked once on one phone."
