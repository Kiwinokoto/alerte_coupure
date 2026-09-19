# Alerte coupure / PowerWatch

This repository started in 2024 as a tiny Windows proof of concept for detecting loss of AC power from a battery-backed computer.

The historical PoC is intentionally preserved at the repository root:

- `battery.py` reads Windows `GetSystemPowerStatus`;
- `mail.php` is the original mail-alert experiment.

## Android V1

Development now continues in `android/` as **PowerWatch**, an Android application designed to turn a normal battery-backed phone or tablet into a mains power monitor.

The V1 goal is deliberately simple:

- the user can arm or disarm monitoring;
- a foreground service keeps monitoring while the screen is off;
- power loss and restoration are detected from Android's external-power state, not from whether the battery is actively charging;
- transitions are debounced to ignore very short interruptions;
- the service periodically checks the current state in addition to listening for power broadcasts;
- monitoring can resume after a device reboot if it was armed before reboot;
- a configurable HTTPS webhook receives power events and heartbeats;
- a local event log remains available on the device.

See [android/README.md](android/README.md) for build and test instructions.

## Branches

`main` preserves the original proof of concept.

Active Android development is currently on `dev/android-v1`.

## Safety

PowerWatch is currently experimental software. It is not a certified safety, refrigeration, electrical-protection, or food-safety device and should not be treated as the sole protection for critical equipment or stock.
