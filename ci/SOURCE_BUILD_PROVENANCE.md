SELF-NUDGE DEX COMPOSITION — BUILD PROVENANCE
Recorded: 2026-08-30
Branch: self-nudge-dex-build-20260830

Runtime composition:
- Self-Nudge protocol/domain capsule: reconstruction/core (retained because no adequate external source implements the combined NUDGE_PROTOCOL/1 contract).
- Alarm/snooze/dismiss process-death model: AOSP/GrapheneOS DeskClock AlarmStateManager ecology.
- Foreground Service lifecycle/visible notification: android/codelab-while-in-use-location, commit 1a91373eb792f85e5ad1bc1c19fc434a6a1fc749.
- Android Keystore AES/GCM semantics: tink-crypto/tink-java AndroidKeystore.java, inspected at commit 3d5396ffd8fc3b8e6384b83137dd8b50a8d10e80.
- AccessibilityService registration/lifecycle: android/codelab-android-accessibility, commit 0e9851180ab8384a2aabf0301b3f9be3de0e9ff9.
- SDK-free/native lineage remains a separate frozen APK and is not overwritten by this Dex build.

Build composition:
- GitHub-hosted Ubuntu runner.
- Android SDK platform 35 + Build Tools 35.0.0 from sdkmanager.
- AAPT2 resource/manifest link.
- javac Java 8 bytecode target against android-35/android.jar.
- D8 dexing.
- zipalign.
- apksigner development signature with v1/v2/v3 verification output captured.

Acceptance invariant:
An APK artifact is publishable from this workflow only if core tests, transport tests, Java compile, D8, AAPT2 link, zipalign and apksigner verification all exit successfully.
