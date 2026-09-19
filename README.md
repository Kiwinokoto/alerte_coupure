# Alerte coupure / PowerWatch

This repository started in 2024 as a tiny Windows proof of concept for detecting loss of AC power from a battery-backed computer.

The historical PoC is intentionally preserved at the repository root:

- `battery.py` reads Windows `GetSystemPowerStatus`;
- `mail.php` is the original mail-alert experiment.

## PowerWatch V1

Development now continues as **PowerWatch**, an Android-first mains power monitor backed by a small optional server.

```text
mains outlet
    │
Android phone + battery + mobile data
    │
    ├── local power event detection
    └── HTTPS events + heartbeats
              │
              ▼
        PowerWatch server
        SQLite + watchdog
              │
              ▼
       e-mail / later SMS
```

The Android app can be armed or disarmed by the user and is designed first for an ordinary phone or tablet, without Device Owner.

The server is deliberately small. It persists events, detects a silent probe and can dispatch alerts without putting messaging credentials on the phone.

See:

- [android/README.md](android/README.md)
- [server/README.md](server/README.md)

## Branches

`main` preserves the original proof of concept.

Active Android/server V1 development is currently on `dev/android-v1`.

## Safety

PowerWatch is experimental software. It is not a certified safety, refrigeration, electrical-protection, or food-safety device and should not be treated as the sole protection for critical equipment or stock until it has completed long-duration qualification testing.


## License

PowerWatch is released under the [MIT License](LICENSE).

You may use, study, modify, redistribute and commercially reuse the code, provided the copyright and license notice are retained. Contributions and sponsorship are welcome.
