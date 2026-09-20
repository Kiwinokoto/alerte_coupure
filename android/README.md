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

The repository includes a Gradle 8.13 wrapper. Use `./gradlew` for reproducible command-line builds; Android builds still require JDK 17 and Android SDK 36.

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
- validate the persistent on-device event outbox/replay on the physical OnePlus and design/test direct-SMS fallback for confirmed power loss when backend delivery fails;
- design an explicit **Désarmer et quitter** action with a danger-style confirmation; swiping the UI away must continue to leave an armed foreground monitor running;
- decide the small set of officially supported phone models;
- assign a dedicated production hostname;
- add Device Owner only if normal-Android reliability testing shows a real need.

## Post-V1 / productisation

The current V1 is an Angela-first proof of concept. If reliability testing validates the approach, a business-ready follow-up should avoid exposing webhook URLs or shared probe tokens to ordinary users. Candidate work includes:

- simple onboarding with an account, activation code or pre-provisioned device identity;
- automatic per-device/site provisioning instead of manual endpoint/token entry;
- secure separation between probe credentials and administrator credentials;
- customer/site management with configurable alert recipients and channels;
- an authenticated **customer monitoring dashboard** where a restaurant/user can quickly confirm that monitoring is armed, mains is present, the probe has contacted the service recently, backend connectivity is healthy, and recent incidents/alerts are visible;
- a later **administrator fleet dashboard** showing all customers/sites/probes with health state, last heartbeat/contact, current app version, recent incidents and provisioning/credential status, so operational problems can be spotted without inspecting each device manually;
- keeping these dashboards observational rather than safety-critical: the phone must continue to detect locally and use its independent degraded/fallback paths even if the web interface or central backend is unavailable;
- production signing, release distribution and update strategy;
- deciding whether this remains **PowerWatch V2** or becomes a separate product built from the validated PowerWatch core.

The exact V2/V3 sequencing is deliberately open. The customer-facing status view is likely useful early in productisation; the multi-customer administrator console can come later once accounts, sites and per-installation identities exist. Neither should delay the restaurant-focused V1 reliability work.

## Overnight handoff (2026-09-19 → 2026-09-20)

- **Reference:** `dev/android-v1` code at `f81c135` (`Prioritize critical events during replay`). GitHub and workstation were aligned before commits. VPS `/opt/powerwatch` remains intentionally at `b9df88e`; `powerwatch-api` is healthy and no server deploy/restart was performed.
- **Completed:** retained and finished the useful work left by the long-running final overnight task. Critical `power_lost` / `power_restored` delivery now uses targeted retries under a bounded partial wake-lock, while ordinary non-critical events use one immediate attempt and remain persisted for later replay. A persistent `FallbackTracker` records critical events that exhaust normal delivery and clears them once normal replay succeeds. The UI exposes the number of critical fallback candidates without sending any SMS or other paid/external fallback.
- **Physical fallback validation:** current code was built and installed on the OnePlus 7T Android 11. With the real backend endpoint but an intentionally invalid token, a simulated confirmed power loss held `PowerWatch:critical-delivery` during retries; after retries, exactly one critical event became fallback-eligible and the outbox retained `power_lost`. A second scenario crashed the process while `monitoring_started` was queued ahead of `power_lost`; after Android restarted the sticky service, replay prioritized the critical event, marked it fallback-eligible despite the older non-critical backlog, and preserved both events. Restoring the original configuration then replayed successfully, leaving backend health `OK`, outbox count 0 and fallback count 0.
- **OxygenOS hardening:** PowerWatch now requests its own battery-optimization exemption through Android's dedicated settings intent, with fallback to the generic settings page. The OnePlus is currently already whitelisted for PowerWatch. Existing package-replacement auto-resume, foreground monitoring, reboot receiver and explicit **Désarmer et quitter** behavior remain intact.
- **Degraded-state/UI status:** Android independently displays monitoring, mains, battery optimization, network, backend/VPS health, last backend contact and fallback state using the pale green / amber / red / grey treatment. `BackendHealth` remains `UNKNOWN`, `OK`, HTTP-level `ERROR`, or `UNREACHABLE`.
- **Tests:** containerized JDK 17 + cached Android SDK 36 + persistent `~/.android/debug.keystore`: debug build/tests are **BUILD SUCCESSFUL** with **14/14 Android unit tests green**, covering outbox persistence/recovery/retention, backend-state preferences, fallback eligibility/idempotence/clearing, critical retry policy and critical-first replay ordering. Server suite is **9/9 green**; the migration regression creates a legacy `events` table without `event_id`, runs `init_db()` twice, verifies historical data is preserved, and verifies the new column/index exist. `assembleRelease` also succeeds (46 tasks) and currently produces the expected unsigned release APK; production signing remains intentionally unconfigured. `git diff --check` passed before commits.
- **Device now:** OnePlus is back to `armed=true`, mains present, original endpoint/token restored, backend health `OK`, foreground `MonitorService` running, outbox empty and no fallback event pending. Removing the PowerWatch activity/task from the Android task stack leaves `MonitorService` running, `armed=true`, and foreground notification id `1001` present. A forced app crash changed the PID and Android recreated the sticky service automatically with monitoring and notification intact. Unchanged foreground-notification state is now de-duplicated: after install, the notification post counter remained unchanged for 70 seconds across multiple 30-second state polls.
- **Next:** (1) controlled VPS/backend rollout of the already-tested server idempotence migration only when deployment is explicitly authorized, then verify duplicate replay against the migrated live DB; (2) decide and implement the actual direct fallback transport (SMS is the leading V1 option) while keeping paid sending disabled until an explicit test; (3) finish remaining Android/OxygenOS reliability checks, especially reboot/recovery and Wi-Fi/mobile-data transitions; (4) release/signing/configuration hardening for restaurant handoff.
- **Risk/debt:** the live container still runs the pre-idempotence schema: live SQLite has no `events.event_id` column or unique event-id index. The direct fallback transport itself is still absent: critical events are now persisted/marked robustly, including after crash/replay, but no SMS is sent yet. Production release signing is also not configured; creating and safeguarding the long-lived release key is a human/security decision. SharedPreferences remains a deliberately small V1 persistence layer. Gradle 9 deprecation warnings remain non-blocking.
- **Migration/deploy note:** server idempotence adds `events.event_id` plus a unique partial index through startup `init_db()`. This additive/backward-compatible change remains **not applied to the live DB** because no server deploy/restart has been authorized.

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
