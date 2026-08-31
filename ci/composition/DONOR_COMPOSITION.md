# Self-Nudge Android — Source Quarry Composition

Baseline: `FreesoSaiFared/studious-sniffle@31fbc88bccfcf9142fda04f3d7735f0546b609df`  
Composition branch: `self-nudge-sqc-20260831`  
Lock: `ci/composition/COMPOSITION.lock.json`

## Operating result

The existing NUDGE_PROTOCOL/1, durable delivery, alarm/notification/audio path, token storage, and replay semantics remain the authority. The composition does **not** re-platform the app. It replaces the empty accessibility hook, augments presentation, adds a package-bounded ChatGPT automation engine, defines calibrated contextual observations, and introduces a signed live-code boundary.

The first executable transplant deliberately uses only Android framework APIs already available to the direct android.jar + javac + D8 build. MediaPipe, Health Connect, Lottie and Zipline are retained as pinned second-lane donors because pulling their dependency graphs into the current hand-built APK before the framework-only lane passes would enlarge the closure residual.

## Whole-source matrix

| Source | Decision | Exact role / next mutation |
|---|---|---|
| `.github/workflows/self-nudge-dex-build.yml` | AUGMENT | Trigger composition branch; add automation-core tests, composition-lock verification and artifact receipts. |
| `ci/SOURCE_BUILD_PROVENANCE.md` | KEEP | Preserve build lineage; append composition commit only after green acceptance. |
| `AndroidManifest.xml` | AUGMENT | Keep current services/permissions; add ChatGPT package visibility and only capabilities actually implemented. |
| `AlarmScheduler.java` | KEEP | Already correct exact/inexact downgrade seam. |
| `AttentionController.java` | AUGMENT-LATER | Preserve attention semantics; visual polish must not change DND/FSI capability resolution. |
| `CapabilityProbe.java` | AUGMENT | Report accessibility runtime, screenshot API tier, target-app launchability, optional context lanes. |
| `ControllerLinkService.java` | AUGMENT-LATER | Add controller-issued automation/code jobs only after local automation state machine passes. |
| `DeviceIdentity.java` | KEEP | Stable device identity remains independent of automation. |
| `InteractionJournal.java` | KEEP | Preserve NUDGE interaction replay authority; do not overload with code/automation jobs. |
| `InterruptionActivity.java` | AUGMENT | Retain READY/STOP/SNOOZE/audio/choice semantics; replace flat widgets with animated presentation helper. |
| `MainActivity.java` | AUGMENT | Premium presentation, target-app diagnostics and a local ChatGPT automation probe. |
| `NudgeAccessibilityService.java` | REPLACE | Empty hook becomes snapshot/action/gesture/screenshot service with package fencing and stale-state rejection. |
| `NudgeAlarmReceiver.java` | KEEP | No quarry residual. |
| `Outbox.java` | AUGMENT | Durable automation/code receipts get distinct record types; preserve idempotent event/audio behavior. |
| `SecretStore.java` | KEEP/AUGMENT-LATER | Existing Keystore token stays; add trusted code-signing public key only when live-code lane lands. |
| `WorkerClient.java` | AUGMENT-LATER | Existing HTTPS boundary stays; automation/code endpoints only after protocol is frozen. |
| `strings.xml` | AUGMENT | Accessibility disclosure, automation status, developer/live-code labels. |
| `styles.xml` | AUGMENT | Modern dark/light surface defaults; animation remains framework-driven. |
| `accessibility_service.xml` | AUGMENT | Add windows-changed events and include-not-important-views; preserve retrieval/gesture/screenshot declaration. |
| `MiniJson.java` | KEEP | Existing bounded JSON substrate remains adequate. |
| `Models.java` | KEEP for NUDGE | Do not contaminate NUDGE_PROTOCOL/1; automation gets its own typed model. |
| `Protocol.java` | KEEP | Freeze proven nudge protocol. |
| `ReplayGuard.java` | KEEP | Reuse the semantic rule in the new automation/code ledgers, not the class itself blindly. |
| `CoreTests.java` | AUGMENT | Add pure-Java automation planner/stability/failure tests. |
| `TransportTests.java` | KEEP initially | Extend only when a controller automation endpoint exists. |

## New seams

1. `PremiumUi.java` — framework-only gradients, elevation, alpha/translation/scale entrance choreography and press feedback.
2. `AutomationPlanner.java` — pure-Java selection/stability logic testable without Android.
3. `NudgeAccessibilityService.java` runtime snapshot/action adapter.
4. `ChatGptAutomationEngine.java` — package-fenced state machine and durable outcome model.
5. `AutomationJournal.java` — job idempotency and UNKNOWN_OUTCOME re-observation.
6. `AffectObservation.java` — evidence + confidence + provenance, never truth/deception labels.
7. `ContextAdapter` family — user-selected document import first; Health Connect and provider OAuth are optional permissioned adapters.
8. `SignedBehaviorBundle` / `CodeUpdateManager` — staged live-code lane after automation passes.

## Exact donor slices

All directly composable slices are Apache-2.0 and are frozen with SHA-256 in `COMPOSITION.lock.json`.

| Capability | Donor | Exact slice | Composition use |
|---|---|---|---|
| Framework transitions | android/animation-samples @ `df3196da` | `BasicTransitionFragment.java:82-108` | `TransitionManager`/delayed-property mutation model for lightweight premium UI. |
| Text insertion | Appium UIA2 @ `5412fdb3` | `SendKeysToElement.java:62-96` | set-text-first interaction, explicit replacement semantics and failure reporting. |
| UI tree snapshot | Appium UIA2 @ `5412fdb3` | `AccessibilityNodeInfoDumper.java:197-228,302-317` | bounded semantic tree projection and snapshot-first lookup. |
| Window/root retrieval | LineageOS framework @ `68ee585f` | `AccessibilityService.java:1073-1123` | active/all-window observation boundary. |
| Gesture dispatch | LineageOS framework @ `68ee585f` | `AccessibilityService.java:1286-1315` | fallback taps/swipes only after node actions fail. |
| Global actions | LineageOS framework @ `68ee585f` | `AccessibilityService.java:2560-2572` | BACK/HOME/etc as explicitly requested operations. |
| Display screenshot | LineageOS framework @ `68ee585f` | `AccessibilityService.java:2705-2735` | visual fallback on API 30+. |
| Window screenshot | LineageOS framework @ `68ee585f` | `AccessibilityService.java:2761-2766` | overlay-free target-window capture on API 34+. |
| Face live stream | MediaPipe samples @ `ece8a1f9` | `FaceLandmarkerHelper.kt:147-200` | second-lane camera inference adapter. |
| Face blendshapes | MediaPipe samples @ `ece8a1f9` | `FaceBlendshapesResultAdapter.kt:34-44` | raw 52-category observation vector; no emotion/lie oracle. |
| User-selected documents | Android storage samples @ `4aee388a` | `MainActivity.kt:86-135` | explicit ACTION_OPEN_DOCUMENT + persistent URI permission. |
| Health permission/read | Android health samples @ `8613828d` | `HealthConnectManager.kt:115-141` | explicit grant check/request and bounded record reads. |
| Signed live-code manifest | CashApp Zipline @ `62759c27` | `ManifestVerifier.kt:48-100` | trusted-key manifest boundary. |
| Fast code reload | CashApp Zipline @ `62759c27` | `FastCodeUpdates.kt:36-67` | development push/reconnect semantics; not copied until dependency lane exists. |

Optional visual donor: Airbnb Lottie Android @ `05ea92e90381eb8a8ae06855ea2b74f322bebbec`, Apache-2.0. It is **not** in the first transplant because the current deterministic APK builder has no dependency-resolution lane. Add only after framework animations are accepted.

## ChatGPT Android automation state machine

```text
IDLE
 -> REQUIRE_USER_ENABLED_ACCESSIBILITY
 -> LAUNCH_TARGET_PACKAGE
 -> WAIT_TARGET_FOREGROUND
 -> SNAPSHOT_A
 -> LOCATE_COMPOSER
 -> SET_TEXT
 -> REOBSERVE_TEXT
 -> LOCATE_SEND
 -> SEND
 -> REOBSERVE_AFTER_SEND
 -> WAIT_RESPONSE_START
 -> SAMPLE_RESPONSE
 -> STABILITY_GATE
 -> EXTRACT_NEW_RESPONSE
 -> DURABLE_RECEIPT
 -> COMPLETE
```

### Selection rules

- Target package is explicitly configured and every snapshot/action is rejected if the active package differs.
- Composer: enabled + editable node; prefer focused node, then lowest/largest editable node. Never enter text into password nodes.
- Text insertion: `ACTION_SET_TEXT` first. Clipboard paste is not the primary path.
- Send: enabled clickable node with strong semantic evidence (view id, content description, text), then a clickable control geometrically adjacent to the verified composer. Coordinate gesture is last resort.
- Never reuse a node handle after an observed content/window generation change. Re-snapshot and reselect.

### Response stability

Capture a pre-send semantic fingerprint. After send:
1. wait for a changed tree;
2. find new non-editable textual nodes not present in the pre-send projection;
3. canonicalize screen order and de-duplicate parent/child repeated text;
4. require the candidate response fingerprint to be unchanged across at least three observations spanning >= 800 ms;
5. if the UI exposes a visible generation/stop control, require it to disappear before completion;
6. bounded timeout -> `UNKNOWN_OUTCOME`, never blind resubmit.

A timeout after SEND is **not** NOT_APPLIED. The engine re-observes before any retry.

## Screenshot / vision fallback

Tree-first automation is authoritative whenever actionable nodes exist. Screenshot is a fallback for custom-rendered surfaces or inaccessible semantics.

- API 34+: prefer `takeScreenshotOfWindow(windowId,...)` so an accessibility overlay does not contaminate the target image.
- API 30-33: use display screenshot.
- Older API: no accessibility screenshot claim; use an explicitly granted MediaProjection lane if enabled.
- Screenshot result is evidence, not an action target by itself. Any vision-derived target must be mapped back to a current semantic node when possible; otherwise a bounded gesture is followed by immediate re-observation.

## Android control surface map

### Ordinary, explicit user-grant lanes

- AccessibilityService: window trees, node actions, gestures, global actions, screenshots, optional overlays.
- NotificationListenerService: notification context after the user enables notification access.
- UsageStatsManager: coarse foreground/app-use history after Usage Access grant.
- MediaProjection: screen capture after system consent; do not treat the grant as Accessibility authority.
- Storage Access Framework: user-selected files/folders and persisted URI grants.
- Health Connect: per-record-type read permissions; users can revoke at any time.
- Microphone/camera: runtime permissions plus visible capture policy.
- Overlay permission: optional explanatory/highlight UI; not required for action execution.
- InputMethodService: only if the user explicitly chooses this app as an IME; otherwise Accessibility `ACTION_SET_TEXT` remains preferred.

### Optional high-authority lanes

- Device owner / profile owner: provisioning-time managed-device capabilities; never silently assumed on a personal phone.
- ADB/Shizuku/root: development/owner-controlled power-user lane, not a normal Android permission.
- Default-assistant / VoiceInteractionService: only when the user deliberately selects the app as assistant.
- Package installation/update: normal PackageInstaller requires user confirmation unless a legitimately provisioned higher-authority lane exists.

## Affect / emotional-state observation contract

The runtime stores **observations**, not asserted inner states.

Each observation contains:
- source and capture consent;
- timestamp/window;
- raw features;
- model/version;
- quality score;
- hypothesis scores;
- calibration confidence;
- provenance to the triggering nudge/session.

Candidate signals:
- face: MediaPipe landmarks/blendshape scores, head pose, blink/brow/mouth dynamics and tracking quality;
- temporal: change and persistence across multiple frames rather than one-frame labels;
- voice/prosody: pitch/energy/rate/pause/spectral features after explicit microphone capture;
- interaction: response latency, snooze/stop, corrections, repeated choices, app-switch timing;
- diary/context: only user-selected documents or explicit provider integrations;
- health: only specifically granted Health Connect record types.

No generic “lie detector” becomes truth authority. Deception, diagnosis, intent and emotional labels are hypotheses at most and cannot drive irreversible/high-stakes action without independent confirmation.

## Live-code composition decision

Use a tiered runtime instead of jumping directly from prose to self-modifying APK bytes.

**Tier 0 — existing dynamic interaction envelope.** Already proven.

**Tier 1 — signed declarative behavior bundle.** ChatGPT may synthesize bounded UI/automation/state-machine programs encoded as data. The bundle is schema-validated, hash-addressed, signed by a trusted P-256/Ed25519 key, journaled, dry-run checked and atomically activated with rollback.

**Tier 2 — signed hot-loadable module.** Later: compile a narrow interface module to DEX, verify signed manifest + module hash, copy only into app-private storage, load with Android class loading, and keep previous module until post-load acceptance passes.

**Tier 3 — full APK source patch/rebuild.** Reuse the already-proven `aapt2 -> javac -> D8 -> zipalign -> apksigner` pipeline as the oracle. On-device tooling must reproduce the same stages and receipt. Normal installation remains user-confirmed. A failed smoke test retains the previous signed APK/module.

The first implementation target is Tier 1 plus the ChatGPT automation engine. This gives genuine live program synthesis without introducing a compiler/toolchain dependency before the automation substrate is proven.

## Acceptance boundary for first transplant

PASS requires:
1. existing 17 core + 3 transport tests remain green;
2. new planner tests cover composer selection, send selection, stale snapshot, stable response, package mismatch and UNKNOWN_OUTCOME;
3. android.jar compilation succeeds;
4. APK v2/v3 verification succeeds;
5. manifest still reports minSdk 26 / targetSdk 35;
6. static checks prove AccessibilityService contains observation + node action + gesture + screenshot code;
7. no direct donor with a non-compatible license was copied;
8. physical-device status remains explicitly unproven until ADB install/launch/logcat exists.
