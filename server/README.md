# PowerWatch server

Small companion backend for the Android monitor.

It is intentionally separate from the phone-side detection logic. The phone remains responsible for observing external power; the server provides persistence, remote liveness detection and alert dispatch.

## Responsibilities

- accept authenticated Android events over HTTPS;
- store events and current device state in SQLite;
- detect a probe that was armed but stopped sending heartbeats;
- send alerts for power loss/restoration and probe offline/online transitions;
- infer a missed power transition from a later heartbeat;
- expose a minimal health endpoint.

The default Android heartbeat is every 2 minutes. The server marks an armed probe offline after 420 seconds by default, leaving margin for short mobile-network interruptions.

## API

### `GET /healthz`

Returns backend health and whether outbound alert delivery is configured.

### `GET /api/v1/status`

Requires the same `X-PowerWatch-Token` as event ingestion.

Returns whether alert delivery is configured and a human-readable summary such as `E-mail → owner@example.test`. This endpoint is intended for the phone UI so the operator can immediately see how alerts will be delivered.

### `POST /api/v1/events`

Requires:

```text
X-PowerWatch-Token: <shared secret>
Content-Type: application/json
```

The token is configured only through the server environment and must not be committed.

Alert destinations remain server-admin configuration in V1. The phone can read the effective channel/destination but cannot change it; configuration writes should get a separate admin authorization model before they are exposed remotely.

## Alert delivery

SMTP is optional. If SMTP is not configured, events and liveness state are still persisted and alert attempts are logged.

Supported alert conditions:

- explicit power loss;
- explicit power restoration;
- power transition inferred from a heartbeat;
- monitor started while already on battery;
- probe silent beyond the liveness threshold;
- probe recovered after being marked offline.

## VPS development deployment

`docker-compose.vps.yml` is currently designed for the personal VPS Traefik network.

For the development phase it mounts the API temporarily at:

```text
https://gauss.kiwinokoto.com/powerwatch-api/
```

This does not modify Gaussian Walk. Traefik routes only the `/powerwatch-api` prefix to the separate PowerWatch container and strips the prefix before forwarding.

Before a real release, PowerWatch should get its own hostname.

## Secrets

Copy `.env.example` to `.env` on the server and populate a long random `POWERWATCH_INGEST_TOKEN`. SMTP credentials, if used, belong only in that server-side file.
