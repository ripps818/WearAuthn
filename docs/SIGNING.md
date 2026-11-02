# Signing

Release builds are signed with a key that is **never committed**. The upstream `ci_keystore.jks` and `nightly_keystore.jks` have been removed: their passwords were public, so anyone could have signed an APK that installs as an update over a build signed with them.

## Supplying the key

Gradle reads these values from Gradle properties (`-P…` or `~/.gradle/gradle.properties`) or from environment variables of the same name:

| Name | Required | Meaning |
|---|---|---|
| `WEARAUTHN_KEYSTORE` | yes | Path to the PKCS12/JKS keystore |
| `WEARAUTHN_KEYSTORE_PASSWORD` | yes | Keystore password |
| `WEARAUTHN_KEY_ALIAS` | no | Key alias, default `wearauthn` |
| `WEARAUTHN_KEY_PASSWORD` | no | Key password, default: the keystore password |

Without `WEARAUTHN_KEYSTORE`, the `release` and `signed` build types produce unsigned APKs. Debug builds always use the local debug keystore.

```bash
WEARAUTHN_KEYSTORE=/path/to/release.jks \
WEARAUTHN_KEYSTORE_PASSWORD=... \
./gradlew :authenticator:assemblePlayStoreRelease
```

## Creating a key

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias wearauthn \
  -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 10000 \
  -dname "CN=WearAuthn revival"
```

Keep the keystore and its password outside the repository, and back them up. Android only installs an update if it's signed with the same key as the installed app; otherwise you have to uninstall first, which deletes all credentials stored by the app.

## CI

`.github/workflows/android.yml` and `nightly.yml` expect two repository secrets:

- `WEARAUTHN_KEYSTORE_BASE64`: the keystore file, base64-encoded (`base64 -w0 release.jks`)
- `WEARAUTHN_KEYSTORE_PASSWORD`: its password

With the secrets set, CI uploads a signed APK. Without them, as for pull requests from forks, it uploads an unsigned APK, which can't be installed. The nightly workflow refuses to run without them.
