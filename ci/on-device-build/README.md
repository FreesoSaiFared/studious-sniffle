# Self-Nudge Tier-3 on-device build

This profile reproduces the proven CI APK stages directly on Android/Termux without Gradle.

Required Termux tools: OpenJDK 17+ (21 recommended), `aapt2`, `zipalign`, `apksigner`, `zip`, `curl`, and an Android 35 `android.jar`. The script downloads modern R8 8.13.19 when it is absent.

Build only:

```bash
bash ci/on-device-build/build-self-nudge.sh
```

A successful build **does not install anything**. It preserves the previous build and writes `~/.local/share/self-nudge-build/last-build-receipt.json`.

User-confirmed package-installer flow:

```bash
bash ci/on-device-build/install-self-nudge.sh --confirm-install
```

Rollback to the previous locally built APK:

```bash
bash ci/on-device-build/install-self-nudge.sh --rollback --confirm-install
```

An ADB reinstall is available only behind the additional explicit `--adb --confirm-install` flags.

The CI build remains the current build oracle until this script is mechanically executed on an ADB-visible Android device and its APK/signature/runtime receipt is compared.
