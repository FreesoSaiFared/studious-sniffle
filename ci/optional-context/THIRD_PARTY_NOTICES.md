# Optional context/sensing third-party notices

This lane is deliberately separate from the dependency-free Self-Nudge APK.

- MediaPipe samples: Google AI Edge / TensorFlow Authors, Apache License 2.0. Donor commit `ece8a1f99726a364509be9d07835a69ef65d3e35`.
- MediaPipe Tasks Vision runtime: `com.google.mediapipe:tasks-vision:1.0.0`; upstream license terms apply.
- Face Landmarker model: Google MediaPipe model asset at the exact URL recorded in `OPTIONAL_CONTEXT.lock.json`; model provenance and hash are emitted by CI before artifact publication.
- Tunify YIN implementation: Copyright © 2023 Stavros Barousis, MIT License; donor commit `836876a1b30581ad99495bfde74b4060df06f17d`. The bounded Java pitch implementation is adapted from its YIN structure.
- Android Health samples: Android Open Source Project, Apache License 2.0; donor commit `8613828d6309c00bec8a7704cced9fc445bd018f`.
- Health Connect client: `androidx.health.connect:connect-client:1.1.0-alpha12`; AndroidX license terms apply.

The runtime contract forbids translating these raw features directly into truth, deception, diagnosis, or asserted emotional state.
