# WearAuthn revival — kickoff brief

You are working in a sandboxed dev container (Remote Control mode). The goal is to revive and extend **WearAuthn** (https://github.com/fmeum/WearAuthn, MIT), a Wear OS app that turns a watch into a FIDO2/U2F security key over Bluetooth HID and NFC.

## Why

Taylor wants his **OnePlus Watch 3** (Wear OS 5, Android 14 base — verify with `adb shell getprop ro.build.version.sdk`) to act as a Bluetooth security key for his Linux laptop (ASUS Zenbook S, BoppOS — a bootc/CachyOS immutable distro — Niri, Chromium-based browser). The target experience:

> The watch stays attached to the laptop in the background. When a site asks for a security key, the watch **vibrates with a notification**, Taylor taps **Approve**, and the login completes.

This is an **experiment / convenience factor only**. It will never protect root accounts (those use Nitrokey + Token2 hardware keys). Even so, never weaken user presence: every operation needs an explicit tap.

## What already exists upstream

- `authenticator/…/bthid` — Bluetooth HID device emulation (API 28+), based on WearMouse
- `authenticator/…/fido` — CTAP1/CTAP2, HID and NFC transports, Android Keystore-backed credentials
- `authenticator/…/ui` — timed confirmation dialog (this is the existing tap-to-approve)
- `authenticator/…/complication` — complication that starts the app and connects to a preset device
- `companion` — phone app that exists only for a Play Store in-app purchase
- Open upstream issues #2 (stay connected to paired computers) and #6 (auto-connect) are exactly this feature
- The project looks dormant (issues from 2020–2023). Known fork: `Flyfish233/WearAuthn`.

## Phases

Work in small, reviewable steps on branches. Commit often. Keep a running log in `docs/NOTES.md`: findings, decisions, open questions.

### Phase 0 — Survey (no code changes)
1. Clone upstream and list all forks (`gh api repos/fmeum/WearAuthn/forks --paginate`).
2. For each fork with commits ahead of upstream, summarize what it changed. Note anything worth cherry-picking, especially Gradle/SDK updates or Wear OS 3+ fixes.
3. Read the code and write a short architecture map in `docs/NOTES.md`: the HID registration lifecycle, how a CTAP request reaches the confirmation UI, and where the app is assumed to be in the foreground.
4. Flag anything likely to break on Wear OS 5 / Android 14: `BluetoothHidDevice` usage, `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` runtime permissions, foreground-service type requirements, `PendingIntent` mutability, exact alarms, full-screen intent restrictions, and NFC HCE changes.

**Stop and report** before Phase 1, with a recommendation: build on upstream or on a fork.

### Phase 1 — Build it as-is, then modernize the build
1. Get the current code building in the container (JDK 17, Android SDK command-line tools). Produce a debug APK.
2. Upgrade Gradle, AGP, Kotlin and dependencies. Raise `targetSdk` to what Wear OS 5 needs. Keep `minSdk` at 28 or higher.
3. Change `applicationId` (e.g. `me.ripps.wearauthn`) so it can sit alongside the Play Store build.
4. **Do not use the committed `ci_keystore.jks` / `nightly_keystore.jks`.** Generate a new signing key stored outside the repo, and document how CI should get it from secrets.
5. Remove or stub out the `companion` in-app-purchase gating. The complication should simply work.
6. Optional: a GitHub Actions workflow that builds and uploads the APK artifact. Taylor has a self-hosted runner on his VPS (proxima-a).

Deliver an APK plus a short **device test script for Taylor**: wireless ADB install on the watch, pair the laptop from the watch's Bluetooth settings, attach in the app, then register and log in at https://webauthn.io in Chromium. Include the Linux hidraw permission check (systemd ≥ 244 handles it; otherwise `u2f-hidraw-policy`). You cannot touch the watch, so Taylor runs the device tests. When troubleshooting with him, give **one command at a time** and wait for the result.

**Gate:** if Bluetooth HID registration fails on Wear OS 5, stop. Investigate why (logcat, API restrictions), report, and don't build the later phases on a broken foundation.

### Phase 2 — Always-attached background service
1. Move HID registration and the connection into a **foreground service** (`connectedDevice` type, with a persistent low-key ongoing notification).
2. Reconnect automatically to a chosen laptop when it comes in range or after a disconnect, with backoff. Keep a "preferred device" setting.
3. **Battery rules:** no wakelocks while idle, no polling. Let the Bluetooth stack wake the app on incoming HID reports. Allow the link to idle in sniff mode. The Watch 3 offloads to a low-power co-processor while Wear OS sleeps, so anything that keeps the main SoC awake is the real battery cost.
4. Add toggles: off, always on, or only on a chosen schedule. A quick-settings tile or the complication should switch it.

### Phase 3 — Vibrate-and-approve notification
1. When a CTAP request needs user presence, post a **high-priority notification with vibration**. Its content: relying-party ID or site name, operation (register / sign in), and the requesting device.
2. Tapping it opens the existing confirmation screen, or offer Approve / Deny actions directly. Use a full-screen intent where it's permitted; Taylor can grant that permission manually on a sideloaded build. Fall back to a heads-up notification.
3. Keep the CTAP timeout behavior correct: send keepalives while waiting, and cancel cleanly if the host gives up.
4. **Security:** require the watch to be unlocked and on-wrist to approve. Never auto-approve. Show the relying-party ID prominently so a surprise request is obvious.

### Phase 4 — Measure
Write a battery test procedure for Taylor: a baseline day vs a day with the service attached, using `adb shell dumpsys batterystats --charged` plus watch-reported drain. Record the results in `docs/NOTES.md` and tune from there.

## Ground rules
- Keep the MIT license and NOTICE attribution. Credit fmeum/WearAuthn and WearMouse.
- No telemetry, no network access added to the watch app.
- Never commit secrets, keystores or personal device identifiers.
- Prefer small PRs that explain why each change was made. Stop and ask Taylor when a decision depends on hardware behavior you can't observe.
