# Ninja Ball – Android (Local Host multiplayer)

The game is `app/src/main/assets/index.html` (same file as the web version).
The Android app wraps it in a WebView and adds a **native local server** so one phone can host a room.

## Build
1. Open this folder in **Android Studio** (Hedgehog or newer) → it syncs Gradle automatically.
2. Run on a phone, or **Build > Build APK(s)**. (Command line: `gradle assembleDebug` with Gradle 8.7+.)
Only the Android framework is used – no third-party libraries.

## How to play (two phones, no internet)
1. Phone A: turn on the **hotspot** (or both phones join the same Wi-Fi).
2. Phone B: connect to that hotspot.
3. Phone A: Play → Multiplayer · Local Host → **CREATE ROOM** (shows its IP).
4. Phone B: Multiplayer · Local Host → **JOIN ROOM** → FIND ROOMS (or type the IP).
5. Both choose a character, press READY, host presses START BATTLE.

## Files
- `WsServer.java` – pure-Java WebSocket server (1 guest max, reconnect by session id)
- `LanDiscovery.java` – UDP room beacon/finder + local IP list
- `Bridge.java` – `window.NinjaNative` JavaScript interface
- `MainActivity.java` – WebView host

## Notes
- Host is authoritative: HP, damage, cooldowns, hit detection and victory are computed only on the host. The guest sends only aim direction, ready and character choice.
- The Android permission `INTERNET` is needed for any socket, including LAN. No internet connection is used.
- If a hotspot has "client isolation" enabled, phones cannot see each other; turn it off.
