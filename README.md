# Fast Share v2

LAN-first Android prototype for WhatsApp-like nearby file sharing.

## What it does
- Nearby discovery using Android NSD/DNS-SD.
- Direct TCP transfer on the local network; the Internet/router bandwidth is not the transfer protocol.
- Multi-file picker.
- 64 KiB streaming buffers.
- Progress + instantaneous average speed.
- SHA-256 end-to-end file integrity check.
- Duplicate filename handling.
- Foreground-service scaffold.

## Build
Open this folder in Android Studio with JDK 17. Let Gradle sync, then run on two Android devices connected to the same Wi-Fi network. Open the app on both devices; each should discover the other.

## Important scope
This is a runnable LAN-first v2 foundation, not yet a production replacement for Quick Share. Android does not allow an ordinary app to silently toggle every Wi-Fi/Bluetooth system setting. Wi-Fi Direct/Bluetooth-assisted discovery should be added as separate transports after LAN reliability is validated.

The receiver currently auto-accepts transfers so the two-device test is immediate. Production should add an incoming-transfer confirmation notification/screen, persistent trusted-device identity, resumable chunks, encryption/authentication, and a real transfer history database.
