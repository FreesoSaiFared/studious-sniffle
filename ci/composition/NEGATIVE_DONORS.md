# Negative Donor / Solution-Space Reduction Ledger

Baseline: `31fbc88bccfcf9142fda04f3d7735f0546b609df`

| Candidate / approach | Observed issue | Eliminated configuration | Disposition |
|---|---|---|---|
| `Mangi-11/Eta@1342f8f1…` | Excellent Android agent patterns, but PolyForm Noncommercial 1.0.0. | Copying its implementation into a generally reusable/commercial-capable Self-Nudge deliverable. | ARCHITECTURE-ONLY. Keep lessons: immutable snapshots, stale identity, UNKNOWN_OUTCOME, screenshot completeness. No source transplant. |
| AndroidIDEOfficial/AndroidIDE | Repository is deprecated; heavier IDE/toolchain architecture than needed for the first live-code seam. | Making a full mobile IDE the runtime foundation. | NEGATIVE DONOR. Reuse the already-proven Self-Nudge direct Android build pipeline as oracle instead. |
| Lottie as first UI transplant | Apache-2.0 and high-quality, but current deterministic builder has no dependency resolver/AAR lane. | Introducing an animation dependency before framework-only UI is proven. | DEFER. Framework `TransitionManager` + view property animation first. |
| MediaPipe Face Landmarker as first mutation | Strong Apache-2.0 donor, but Tasks/CameraX/model dependencies are outside the current android.jar-only build. | Pretending raw MediaPipe snippets compile inside the existing Dex lane. | DEFER TO DEPENDENCY LANE. Lock exact source now. |
| OCR-first ChatGPT automation | Coordinates and OCR are less stable than available Accessibility semantics; custom surfaces can still require vision. | Treating screenshots as the primary UI ABI. | REJECT. Tree/action first, screenshot fallback. |
| Blind coordinate tapping | Unknown action outcome can duplicate sends or hit another app/window after UI movement. | Retrying taps without re-observation. | REJECT. Package/window fence + semantic action + post-action snapshot. |
| Reusing AccessibilityNodeInfo after content changes | Android UI trees can become stale; Appium explicitly models stale element failures. | Long-lived global node handles. | REJECT. Immutable snapshot identity; refresh/re-snapshot after generation change. |
| Generic “lie detector” label | Face/voice/behavior features do not establish truthfulness. | Treating deception or emotion labels as ground truth. | REJECT. Store raw/calibrated observations and hypotheses only. |
| Touch exploration / key filtering by default | Alters ordinary device interaction and is unnecessary for ChatGPT prompt/response automation. | Enabling every Accessibility flag merely because it exists. | REJECT AS DEFAULT. Keep documented optional lane only when a concrete user workflow needs it. |
| Unsigned dynamic DEX / code from model output | No provenance, rollback or trusted authority boundary. | Directly executing arbitrary generated code. | REJECT. Signed manifest + hash + schema/interface + journal + rollback. |
| Full APK rebuild as first live-code mechanism | High latency and large toolchain residual when declarative programs can already change runtime behavior. | Rebuilding/reinstalling for every small nudge or automation change. | DEFER TO TIER 3. Tier 1 signed behavior bundle first. |
| Accessibility scraping of diaries/other apps as integration API | UI scraping is brittle and violates the explicit-consent integration goal when provider/SAF routes exist. | Generic background scraping as the normal context adapter. | REJECT. Use SAF, provider APIs/OAuth, Health Connect, explicit import/share. |

## Failure classes discovered from mature donors

1. stale node identity after window/content generation changes;
2. action accepted but callback timed out: outcome is unknown, not failure;
3. editable node clear/set behavior can differ from hint text;
4. event storms can create unbounded queues;
5. custom-rendered or embedded surfaces may have incomplete Accessibility trees;
6. accessibility overlays can contaminate display screenshots;
7. service disconnect/reconnect invalidates previous handles;
8. multiple windows/displays require explicit target selection;
9. prompt send may succeed while response extraction times out;
10. dynamic-code update channels need signature verification, cache/fallback and reconnect behavior.

These are now required acceptance cases rather than future surprises.
