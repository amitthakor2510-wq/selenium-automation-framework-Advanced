---
title: "Maintenance Scripts"
type: tool
layer: tooling
tags: [fw/tooling, kind/tool]
parent: "[[Scripts and Tooling]]"
---

# Maintenance Scripts

> [!abstract] Housekeeping: package audit, artifact pruning, git hooks, coverage headroom and multi-browser SAHMAT runner.

**Part of:** [[Scripts and Tooling]]  ·  **Layer:** Scripts / tooling

- `audit-project.sh [--fix-packages|--quick]` — catches what `mvn compile` misses (a `.java` whose `package` doesn't match its folder).
- `prune-artifacts.sh [--keep-runs N --gap-minutes N --dry-run]` → [[ArtifactRetentionCleaner]].
- `install-hooks.sh` — points `core.hooksPath` at `.githooks/` (pre-commit: Checkstyle + gitleaks).
- `coverage-headroom.py [--threshold N]` — per-package table vs the pom threshold; exit 1 below.
- `run-SAHMAT-all-browsers.sh [smoke|regression]` — chrome → firefox → edge sequentially on one machine.

## Connections

- **Depends on →** [[ArtifactRetentionCleaner]] · [[Secret Scanning]] · [[JaCoCo Coverage Gate]]
- **Related ↔** [[SAHMAT]]
