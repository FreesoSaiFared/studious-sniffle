# Closure Residual Ledger

Target: user-authorized Self-Nudge Android runtime with premium interaction, contextual sensing, ChatGPT-app automation and bounded live code composition.

## Residual after frozen donors are granted for free

| Residual | Type | Current state | Closure operation |
|---|---|---|---|
| Accessibility snapshot/action implementation | missing behavior | Empty v1 service | Implement framework-only service and pure planner now. |
| ChatGPT composer/send/response semantics | integration + timing | Designed, not compiled | Implement package-fenced state machine + synthetic tests. |
| Durable automation receipts/idempotency | coordination | Existing Outbox/Replay semantics are reusable but not wired | Add AutomationJournal and receipt record. |
| Premium visual surface | presentation | Functional but flat | Add framework-only PremiumUi helper and activity integration. |
| Screenshot vision consumer | missing transform | Capture API identified; no visual inference consumer | First return/hash/store screenshots; later add on-device/controller vision adapter. |
| MediaPipe face/blendshape runtime | dependency + model | Exact donor pinned | Add Gradle/AAR dependency lane and model asset only after framework lane green. |
| Voice/prosody feature extraction | missing behavior | Contract only | Quarry a permissive on-device feature extractor or implement bounded DSP features with tests. |
| Affect calibration | empirical | No user-specific calibration dataset | Gather explicitly consented observations; fit calibration separately; never infer truth. |
| Document/diary import | adapter | SAF donor pinned | Implement ACTION_OPEN_DOCUMENT adapter with persisted grant and source provenance. |
| Health context | adapter + permission | Health donor pinned | Add optional Health Connect module and per-record permission UI. |
| Provider diary/service integrations | protocol | No providers selected | Add explicit OAuth/API adapters one at a time; never accessibility-scrape by default. |
| Signed Tier-1 behavior bundle | missing runtime | Zipline design donor pinned | Define schema, signature, activation journal and rollback; interpreter stays bounded. |
| Hot-loadable DEX Tier 2 | toolchain/runtime | Not implemented | Build only after Tier 1; signed private-storage module + stable interface. |
| Full on-device APK build Tier 3 | toolchain | CI oracle exists; phone toolchain not staged | Reproduce aapt2/javac/D8/zipalign/apksigner on device and user-confirmed install. |
| Controller automation/code job API | protocol | Existing NUDGE endpoints only | Freeze local engine first, then add new versioned endpoints without changing NUDGE_PROTOCOL/1. |
| Physical device acceptance | evidence | No ADB-visible device | Install exact built APK, launch, logcat, exercise service/action/screenshot/audio and receipts. |

## Closure metric snapshot

- Donor coverage: strong for UI transitions, Accessibility tree/action/screenshot, face observation, consented file/health context, signed live-code update semantics.
- Composition coverage: strong design, partial executable code.
- Failure anticipation: materially improved by stale-node, unknown-outcome, overlay screenshot, event-storm and signed-update donors.
- Largest remaining residual: **mechanical implementation + physical Android acceptance**, not architecture discovery.
- Novel behavior still requiring project-owned code: ChatGPT-specific semantic planner, response-stability projection, bounded signed behavior DSL, affect calibration policy.

## Stop condition for this SQC generation

Source search may stop for the first implementation generation when:
1. framework-only automation passes synthetic + APK compile tests;
2. no new failure class appears in those tests;
3. residual failures can be named as implementation defects rather than missing donor families.

A new quarry generation reopens only for an observed failure, dependency lane, or physical-device behavior that the current donor set does not anticipate.
