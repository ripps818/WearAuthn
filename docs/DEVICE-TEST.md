# Device test script

How to install a build on the watch and check it end to end against a Linux computer. Tested with a OnePlus Watch 3 (Wear OS 5) and a Linux desktop running Chromium. Run every `adb` command in the same environment, e.g. one distrobox.

## 1. Connect ADB to the watch (wireless)

1. On the watch, go to **Settings → System → About → Versions** and tap **Build number** 7 times.
2. Connect the watch to the same Wi-Fi network as the computer.
3. Go to **Developer options** and turn on **ADB debugging** and **Wireless debugging**.
4. The first time only: under **Wireless debugging → Pair new device**, run `adb pair <ip>:<pairing-port> <code>`.
5. Then connect: `adb connect <ip>:<port>`. Use the port shown on the main **Wireless debugging** screen; it changes whenever wireless debugging restarts.
6. If `adb devices` lists the watch twice (IP plus `adb-…._adb-tls-connect`), pass `-s <ip>:<port>` to every command below.

Check that the watch is suitable:

```bash
adb shell getprop ro.build.version.sdk                          # 34 on Wear OS 5
adb shell dumpsys bluetooth_manager | grep HidDeviceService     # must be present
```

## 2. Install

Download the APK from the CI run's **WearAuthn-apk** artifact (`gh run download <run-id> --repo ripps818/WearAuthn --name WearAuthn-apk`), then:

```bash
adb install -r authenticator-playStore-release.apk
```

CI builds are signed with the project key, so `-r` updates an existing install and keeps its credentials. An unsigned APK, such as one from a fork's pull request, won't install.

## 3. Pair the computer

Pair from inside WearAuthn, so the computer reads WearAuthn's HID record:

1. In WearAuthn, tap **Discoverable**.
2. On the computer, run `bluetoothctl`, then `scan on`, then `pair <watch-address>`. Confirm the passkey on both sides, then run `trust <watch-address>` and `exit`.

**OnePlus/OPPO watches:** the watch also advertises an "OPPO HIDDCR" media-remote HID record, and BlueZ may pick it instead. Remove it from BlueZ's cache after pairing (see the README's Linux section), then reconnect.

## 4. Attach and check the host side

1. In WearAuthn, tap the computer and wait for **Attached**. The connection currently only lasts while WearAuthn is on screen, so set the watch's screen timeout to the maximum.
2. On the computer, outside any container:

```bash
for f in $(grep -l 'OnePlus Watch' /sys/class/hidraw/*/device/uevent); do
  n=$(echo $f | cut -d/ -f5)
  echo "== $n $(ls -l /dev/$n | cut -c1-11)"
  od -An -tx1 -N6 /sys/class/hidraw/$n/device/report_descriptor
  /usr/lib/udev/fido_id /sys/class/hidraw/$n
done
```

Expected: a descriptor starting with `06 d0 f1`, `ID_SECURITY_TOKEN=1`, and an ACL marker (`+` or `@`) on the device node. systemd ≥ 244 sets the ACL automatically for security keys. Without systemd's `fido_id`, install `u2f-hidraw-policy`.

## 5. Register and sign in

1. Open https://webauthn.io in Chromium. If a password manager extension (e.g. Bitwarden) intercepts passkey prompts, choose its hardware-key option or use an incognito window.
2. Under **Advanced Settings**, set **User Verification** and **Discoverable Credential** to **Discouraged**. With the defaults, Chromium reports "Your device can't be used with this site" unless WearAuthn's one-way **Go passwordless** mode is on.
3. Click **Register**. The watch vibrates; check that the prompt names `webauthn.io`, then tap **Accept**.
4. Click **Authenticate** and accept again. Expected: "You're logged in!"

## Troubleshooting

- **"Starting security key…" never finishes:** current builds time out and show "Security key unavailable / Tap to retry". If retrying doesn't help, toggle the watch's Bluetooth off and on.
- **Logs:** in WearAuthn, go to **Advanced → Log level → Debug** (needs developer options), then run `adb logcat -v time | grep -E 'HidDataSender|BTIF_HD|BTA_Hd'`.
- **Device name greyed out with "Use via NFC instead":** that device is a phone. Bluetooth is only offered for computers.
