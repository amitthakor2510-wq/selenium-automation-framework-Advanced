---
title: "VideoRecorder"
type: class
layer: runtime
tags: [fw/runtime, kind/class]
parent: "[[Test Execution Runtime]]"
source: "core/utils/VideoRecorder.java"
loc: 171
---

# VideoRecorder

> [!abstract] Per-test screen recording via `java.awt.Robot` (Monte Screen Recorder) — no ffmpeg needed. Off by default.

**Part of:** [[Test Execution Runtime]]  ·  **Layer:** Test runtime  ·  **Source:** `core/utils/VideoRecorder.java` · 171 LOC

- `video.enabled=false`, `video.fps=10`, `video.keep.on.pass=false`, `video.output.dir=target/videos`.
- Records the **JVM display**, not the browser window → meaningless with `headless=true`; CI toggles (`RECORD_VIDEO`, `record_video`) force headless off and wrap the run in `xvfb-run`.
- One instance per test attempt, owned by [[TestListener]] (start in `beforeInvocation`, stop in `afterInvocation`). Web only — Appium has its own recording API.
- AVI/TSCC is not browser-playable → reports link it as a download. Growth handled by [[ArtifactRetentionCleaner]].

## Connections

- **Used by ←** [[TestListener]]
- **Related ↔** [[ArtifactRetentionCleaner]] · [[global properties]]
