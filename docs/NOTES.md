# WearAuthn revival: working notes

Running log for the revival described in [KICKOFF.md](KICKOFF.md): findings, decisions and open questions.

## Phase 0: Survey (2026-11-01)

Baseline: upstream `fmeum/WearAuthn` master at `e9a3100` (2025-01-04, "Fix release name and missing authenticator APK (#47)"). Upstream has had no activity since then. Its build is already fairly recent: Gradle 8.9, AGP 8.7.3, Kotlin 1.9.22, compileSdk 34, **targetSdk 33**, minSdk 28, view binding. The UI still uses the deprecated `android.support.wearable` library.

### Forks

`gh api repos/fmeum/WearAuthn/forks --paginate` lists 19 forks. Only four have commits ahead of upstream master; the rest are plain mirrors (0 ahead) or stale copies of 2020–2022 upstream (0 ahead, 38–39 behind).

| Fork | Ahead / behind | Last push | Summary | Verdict |
|---|---|---|---|---|
| **ray-powered/WearAuthn** | 13 / 0 | 2026-09-06 | Modernization and Compose rewrite on top of `e9a3100`. Details below. | **Most relevant.** Its CI is green on HEAD `64ebcd5`. |
| Crayonic/WearAuthn | 11 / 0 | 2025-07-31 | Ports the FIDO core to a **phone** app: removes the Wear UI, the companion app and the Wear dependencies; adds a new UI, "fake credentials" and BLE advertising. Other branches are `phone-port-latest-android-api` and `phone_ui_and_fake_credentials`. | Different product direction. Nothing to cherry-pick for the watch. Possibly worth reading later as a reference for a BLE (GATT) FIDO transport. |
| Flyfish233/WearAuthn | 5 / 38 | 2023-04-20 | Kotlin/Gradle bumps and Chinese localization, based on 2020 upstream. | Superseded by upstream's 2024 build updates. At most, the `values-zh` strings could be taken. |
| SteveZMTstudios/WearAuthn | 1 / 0 | 2026-03-30 | Adds `workflow_dispatch` to `nightly.yml`. | Trivial. Could be a one-line convenience. |

Full diffs against each fork's merge base were generated locally during the survey and are not committed.

#### ray-powered in detail

Its 54 changed files (+2195 / −2580) cover:

- **Toolchain:** Gradle 9.5, AGP 9.3.2 (with `android.builtInKotlin=false` and `android.newDsl=false`), Kotlin 2.4.10 with the Compose compiler plugin, JVM target 17, compileSdk `android-37.2`, **targetSdk 37**, Compose BOM 2026.08, and Wear Compose Material3 1.6.2.
- **UI:** every XML layout and the preference screens are replaced with Wear Compose (`WearComponents.kt`, `TransformingLazyColumn`). The system `AmbientLifecycleObserver` replaces `WearableActivity` ambient mode.
- **Companion and billing removed:** `BillingManager` and `UnlockComplicationListenerService` are deleted, and the complication provider is `enabled="true"`. This covers KICKOFF Phase 1 step 5.
- **HID registration (`c7c9932`):** `HidDataSender` gains an explicit `AppRegistrationState` (WAITING_FOR_SERVICE / REGISTERING / READY / FAILED), propagates the `registerApp()` boolean, adds `ensureAppRegistered()` to retry after pairing, permission or lock-screen UI, re-connects to the previous host after re-registration, and adds locking. The SDP subclass changes from `SUBCLASS1_COMBO` to `SUBCLASS1_NONE`. This is the closest existing work to an "always attached" connection.
- **Consent and security fixes (`fa96b36`, `dc81cd0`)** are worth keeping regardless of base:
  - The user-presence timeout lived in a `LaunchedEffect` inside a list item. If that item was never composed (long attacker-controlled RP/user text), the 60 s timeout never started while the keepalive loop sent UPNEEDED forever. It is now an `elapsedRealtime` deadline, with a guard against double resume.
  - `ConfirmDeviceCredentialActivity` was `singleTop` and broadcast one result to every queued `ResultReceiver`, so **one screen-lock confirmation could authorize a second requester**. It now serves one requester per instance and answers on destroy, which also fixes a mutex that could stay held forever.
  - The credential-chooser timeout restarted from full on every screen off/on.
  - Note: these fixes are written against the new Compose code, so they won't cherry-pick onto upstream's view-based UI.
- **CI:** an Android SDK setup step, signed-APK builds, and resource shrinking.

### Architecture map (upstream `e9a3100`)

Module `authenticator`, package `me.henneke.wearauthn`:

- `bthid/`: Bluetooth HID device emulation, derived from WearMouse.
  - `HidDataSender` (a process-wide singleton) owns the profile proxy and the registered HID app, and fans out callbacks to `ProfileListener`s.
  - `HidDeviceProfile28` wraps `BluetoothHidDevice` (connect/disconnect/state).
  - `HidDeviceApp28` and `InputHostWrapper28` hold the SDP record, QoS and `registerApp()` with the FIDO report descriptor.
- `fido/hid/Framing.kt`: CTAPHID framing. `TransactionManager.handleReport()` reassembles packets, dispatches to CTAP1/CTAP2, and runs the keepalive loop (75 ms, `PROCESSING` / `UPNEEDED`).
- `fido/ctap2`, `fido/u2f`: the authenticators. `fido/context/AuthenticatorContext` is the abstract per-transport context (status, user confirmation, Keystore access). `Keystore.kt` holds the Android Keystore-backed credentials and the user-verification "fuse" key (`setUserAuthenticationValidWhileOnBody`).
- `fido/nfc/NfcAuthenticatorService`: the HCE `HostApduService`, with `requireDeviceUnlock="true"`. User presence is implied by the tap.
- `ui/main/`:
  - `AuthenticatorActivity` and `AuthenticatorMainMenu` (preferences: pairing, discoverable, permissions, bonded hosts).
  - `AuthenticatorAttachedActivity`: the "attached" screen.
  - `HidAuthenticatorContext`: the HID-transport context that shows the confirmation dialog.
- `ui/TimedAcceptDenyDialog`: the 60 s accept/deny dialog. It vibrates and takes a `FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`.
- `complication/`: `ShortcutComplicationProviderService` is an old-style `android.support.wearable` provider whose tap launches `AuthenticatorAttachedActivity` with a preset device. It is shipped disabled and only enabled by `sync/UnlockComplicationListenerService` after the companion phone app reports an in-app purchase over the Wearable data layer.
- `companion` module: the phone app; exists only for the purchase.

#### HID registration lifecycle

1. A UI component calls `HidDataSender.register(context, profileListener, ioListener)`. On the first listener, it calls `BluetoothAdapter.getProfileProxy(HID_DEVICE)`.
2. `onServiceConnected` leads to `hidDeviceApp.registerApp(proxy)`, which calls `BluetoothHidDevice.registerApp(sdp, null, qos, executor, callback)`.
3. `onAppStatusChanged(registered=true)` fulfils a postponed `requestConnect(device)`, then `BluetoothHidDevice.connect(device)`.
4. `unregister()` of the **last** listener calls `unregisterApp()` and drops the proxy, so the HID app stays registered only while some UI holds a listener.
   - `AuthenticatorMainMenu` registers in `onCreate`/`onCreateView` and unregisters on destroy.
   - `AuthenticatorAttachedActivity` registers in `onCreate` and unregisters in `onDestroy`.

#### How a CTAP request reaches the confirmation UI

1. The host sends an interrupt report.
2. `HidDeviceApp28.callback.onInterruptData` calls `HidIntrDataListener.onIntrData`. The only implementation is in **`AuthenticatorAttachedActivity`**.
3. That calls `TransactionManager.handleReport()`, which runs a coroutine through `fido/ctap2/Authenticator` (`makeCredential` / `getAssertion`).
4. That calls `context.confirmRequestWithUser(info)` on `HidAuthenticatorContext`. It sets `status = WAITING_FOR_UP` so the keepalive switches to UPNEEDED, then shows a `TimedAcceptDenyDialog(activity)` on the main thread and suspends until accept, deny or timeout.
5. Responses go back via `InputHostWrapper.sendReport()`.

#### Where the app assumes it is in the foreground

- `AuthenticatorAttachedActivity.onStop()` calls `HidDataSender.requestConnect(null)`, which **disconnects the host whenever the screen goes off or the user navigates away**, unless a request is in flight (`status != IDLE`). This is the core of upstream issues #2 and #6.
- The HID app is only registered while an Activity/Fragment holds a listener, and `onDestroy` unregisters it.
- The report listener and the `TransactionManager` live in the Activity. `transactionManager` is created in `onStart` and nulled in `onStop`.
- `HidAuthenticatorContext` takes an `Activity` and shows a `Dialog` with that window, so it can't run from a service.
- Ambient mode keeps the attached Activity on screen as the "attached" indicator.
- Runtime permission requests (`BLUETOOTH_CONNECT/SCAN/ADVERTISE`) and the discoverable request only happen from `AuthenticatorMainMenu`.
- `ConfirmDeviceCredentialActivity` (screen-lock confirmation for UV) is started via `startActivityForResult`.
- `AuthenticatorActivity` uses `clearTaskOnLaunch`, so relaunching from the launcher tears down pending prompts. ray-powered documents the resulting hang.

### Wear OS 5 / Android 14 (API 34) risks

Ordered by how likely they are to block us.

1. **The `BluetoothHidDevice` profile may not exist on the Watch 3.** The HID Device role is optional and OEM-configured (`config_bluetooth_hid_device_supported` / `bluetooth.profile.hid.device.enabled`). If it's absent, `getProfileProxy` never connects, or `registerApp` returns false, and nothing else matters. **This is the Phase 1 gate.** Check it on the device first (see "Next" below).
2. **No foreground service exists yet.** Phase 2 needs:
   - `<service android:foregroundServiceType="connectedDevice">`
   - `FOREGROUND_SERVICE` plus `FOREGROUND_SERVICE_CONNECTED_DEVICE`
   - one of the type's prerequisites at start time, e.g. `BLUETOOTH_CONNECT` granted (on targetSdk 34 a `SecurityException` is thrown otherwise).

   Android 12+ background-start limits also apply. Starting from a complication or tile tap, or from a notification action, counts as user-initiated. A `BOOT_COMPLETED` start of a `connectedDevice` FGS is still allowed on 14, but this needs verifying on Wear. Wear OS also expects an **Ongoing Activity** (`androidx.wear:wear-ongoing`) attached to the FGS notification.
3. **`POST_NOTIFICATIONS` (API 33 runtime permission) is not declared.** It's needed for the visible FGS notification and for the Phase 3 approve notification. It must be requested from UI before the service runs.
4. **Bluetooth runtime permissions.** `BLUETOOTH_CONNECT/SCAN/ADVERTISE` are declared and requested, but only from the main menu. A service or complication start must check them and fall back to opening the app. Legacy `BLUETOOTH` is declared without `maxSdkVersion` on purpose (it works around the discoverable intent on Wear; see the manifest comment). Re-test that on Wear OS 5.
5. **Full-screen intents (Phase 3).** On API 34, `USE_FULL_SCREEN_INTENT` is only auto-granted to calling and alarm apps. A sideloaded build can be granted it via `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. Check `NotificationManager.canUseFullScreenIntent()` and fall back to a heads-up notification. Activity starts from a background service are blocked (API 29+), so the approval path must go through a notification.
6. **Wake locks.** `TimedAcceptDenyDialog` uses `FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`, both deprecated. Replace with `setTurnScreenOn` / `setShowWhenLocked` on the approval activity, or rely on the notification waking the screen. The service must hold **no** wake lock while idle (battery rule in KICKOFF Phase 2).
7. **PendingIntent mutability.** The existing complication `PendingIntent` already uses `FLAG_IMMUTABLE`. New notification actions must also be immutable with explicit intents (API 34 rejects mutable implicit ones).
8. **Exact alarms.** None are used today. `SCHEDULE_EXACT_ALARM` is denied by default on 14, so the Phase 2 "only on a schedule" mode should use inexact alarms or WorkManager windows, not exact alarms.
9. **Deprecated Wear support library.** `android.support.wearable` provides `WearableActivity`, `WearablePreferenceActivity`, `AcceptDenyDialog` and the old `ComplicationProviderService`, and `com.google.android.wearable` is a required `uses-library`. The old complication provider API should move to `androidx.wear.watchface.complications.datasource`. Whether Wear OS 5 still binds the legacy provider needs testing; ray-powered still uses the legacy one.
10. **Companion gating needs Play Services and the phone app.** Without the purchase message, the complication stays disabled. Remove it in Phase 1; ray-powered already did.
11. **NFC HCE.** `requireDeviceUnlock="true"` is already set; upstream #40/#41/#44 went back and forth on it. There are no API 34 HCE changes we depend on. Low priority, since NFC on the laptop isn't the goal. Still to check: whether the Watch 3 exposes HCE to third-party apps at all.
12. **On-body detection.** The UV fuse key uses `setUserAuthenticationValidWhileOnBody(true)`, and Phase 3 wants "unlocked and on-wrist". Both depend on the OEM wiring the off-body sensor into Keystore and Keyguard. Verify on the Watch 3.
13. **Silent assertions (`up=false`).** `getAssertion` honours `options.up == false` without prompting, as CTAP2 allows (Chrome uses it to probe for credentials). With an always-attached service, any bonded host could silently learn which credentials exist. The response has no UP flag, so it can't complete a login. Decide in Phase 3 whether to keep this.
14. **Minor items:**
    - `getParcelableExtra(String)` is deprecated (API 33) but works.
    - `registerReceiver` for system Bluetooth broadcasts needs no export flag.
    - Raising `targetSdk` to 34+ triggers edge-to-edge and predictive-back changes, which are irrelevant on Wear.

### Recommendation

**Build on ray-powered/WearAuthn, not upstream.** Reasons:

- It's the only maintained line, it's a strict superset of upstream (`e9a3100` is its base, 0 behind), and its CI builds signed APKs green.
- It already does much of Phase 1: modern toolchain, Wear Compose UI, companion/billing removed, complication enabled.
- It contains real consent bugfixes, including one lock-screen confirmation authorizing two requesters. These are entangled with the Compose rewrite and can't be cherry-picked onto upstream.
- Its `HidDataSender` registration-state work is the right starting point for the Phase 2 service.

Caveats and how to handle them:

- It's a large single-author rewrite on a bleeding-edge toolchain (AGP 9.3, compileSdk `android-37.2`). Phase 1 step 1 must confirm it builds in our container with JDK 17. If it doesn't, fall back to upstream `e9a3100` and re-implement the consent fixes.
- Review its diff as if it were our own code before building on it, especially the `SUBCLASS1_COMBO` → `SUBCLASS1_NONE` SDP change and anything in `fido/`.
- Credit both fmeum/WearAuthn and ray-powered in the README/NOTICE. Both are MIT.

Mechanically: add `ray-powered` as a remote, open a PR that fast-forwards our `master` to `ray-powered/master` (`64ebcd5`), then start Phase 1 on top of it.

### Next (before Phase 1 code)

The gate question (risk 1) can be answered without building anything: check whether the Watch 3 exposes the HID Device profile, then optionally sideload upstream's official nightly (`nightly-e9a3100`) to see if it can attach to the laptop.

### Open questions

- Does the Watch 3 support the Bluetooth HID Device role and HCE?
- Does Wear OS 5 still bind legacy `android.support.wearable` complication providers?
- Should `up=false` silent probes be allowed while running in the background?

#### Device check results (2026-11-01)

- `getprop ro.build.version.sdk` on the Watch 3 returns **34** (Android 14), as assumed above.
- `dumpsys bluetooth_manager` lists **`Profile: HidDeviceService`**, so the HID Device role is present (risk 1). Whether `registerApp()` and attaching to the laptop actually work is still untested.

## Phase 1: Build (started 2026-11-01)

Decision: build on ray-powered/WearAuthn (`64ebcd5`), merged into our master.

Building locally:

- JDK 17+ (21 works).
- Android SDK command-line tools. Recent releases replace `sdkmanager` with the `android` CLI, and SDK licenses no longer need to be accepted separately. Required packages:
  `android --sdk=$ANDROID_HOME sdk install platforms/android-37.2 build-tools/37.0.0 platform-tools`
- `ANDROID_HOME=<sdk> ./gradlew :authenticator:assemblePlayStoreDebug` builds successfully on Gradle 9.5 / AGP 9.3.2. The debug build is signed with the per-user debug keystore, not the committed `*.jks` files.
