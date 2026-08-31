# Physical Android acceptance

`run-adb-acceptance.ps1` is the evidence-producing device gate for Self-Nudge. CI, signed APKs, Tier-2 loader acceptance, and the Linux Tier-3 script oracle do not substitute for this receipt.

## Safety and authority boundaries

- ADB defaults to `E:\android\platform-tools\adb.exe`; if absent, the harness uses `adb` from `PATH`.
- Exactly one authorized ADB device must be visible.
- The APK SHA-256 is checked before installation when `-ExpectedSha256` is supplied.
- No APK installation occurs without `-ConfirmInstall`.
- Accessibility secure settings are never changed unless `-EnableAccessibilityViaAdb` is separately supplied. Without that flag the harness opens Android Accessibility Settings, writes a BLOCKED receipt, and stops.
- Cross-app ChatGPT automation is not run unless `-RunChatGptProbe` is supplied.
- The target-interruption/UNKNOWN_OUTCOME experiment is not run unless `-RunUnknownOutcomeCase` is supplied.

## Typical invocation

```powershell
.\ci\device-acceptance\run-adb-acceptance.ps1 `
  -ApkPath <exact-accepted-apk> `
  -ExpectedSha256 <accepted-sha256> `
  -ConfirmInstall
```

On an owner/developer test device where changing the secure Accessibility setting through ADB is deliberately desired:

```powershell
.\ci\device-acceptance\run-adb-acceptance.ps1 `
  -ApkPath <exact-accepted-apk> `
  -ExpectedSha256 <accepted-sha256> `
  -ConfirmInstall `
  -EnableAccessibilityViaAdb
```

Add real ChatGPT Android automation acceptance:

```powershell
-RunChatGptProbe
```

Add the forced target-app interruption case:

```powershell
-RunChatGptProbe -RunUnknownOutcomeCase
```

## Mechanical gates

The harness installs only the selected/hash-verified APK, launches `science.transductive.nudge/.MainActivity`, then proves:

1. the configured Self-Nudge AccessibilityService is enabled;
2. `Accessibility tree probe` returns `TREE_PROBE APPLIED`;
3. `Accessibility screenshot probe` returns `SCREENSHOT_PROBE APPLIED` and a 64-character SHA-256;
4. `Signed behavior fixture probe` executes a real ephemeral P-256-signed behavior through production `BehaviorRuntime` and returns `SIGNED_BEHAVIOR_DEVICE_PROBE APPLIED`;
5. a fixture is pushed to Downloads and imported through the actual `ACTION_OPEN_DOCUMENT` flow, with SHA-256 and `sourceUri=content://...` provenance visible in the app;
6. when requested, `com.openai.chatgpt` exists and the ChatGPT automation probe returns `APPLIED` plus `ACCESSIBILITY AUTOMATION WORKS`;
7. when requested, the interruption fixture is accepted only if the app actually records `UNKNOWN_OUTCOME`;
8. force-stop/relaunch does not automatically replay the prior ChatGPT operation.

## Evidence

All UIAutomator XML dumps, textual probe results, `adb devices -l`, threadtime logcat, restart evidence, and the final JSON receipt are written under `ci/device-acceptance/evidence/`.

The final receipt schema is `SELF_NUDGE_ADB_DEVICE_ACCEPTANCE/1`. A physical-device claim is valid only when this receipt reports `status=PASS`; BLOCKED and FAIL receipts remain evidence of the exact unresolved gate.

File-picker layouts vary across Android builds. If the fixture is not directly visible after `ACTION_OPEN_DOCUMENT`, the harness records `CONTEXT_FILE_PICKER_VARIANT_REQUIRES_MANUAL_SELECTION` rather than faking success.
