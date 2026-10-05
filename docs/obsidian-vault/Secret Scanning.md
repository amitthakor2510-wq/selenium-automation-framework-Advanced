---
title: "Secret Scanning"
type: tool
layer: quality
tags: [fw/quality, kind/tool]
parent: "[[Quality Gates]]"
---

# Secret Scanning

> [!abstract] gitleaks (`gitleaks.toml` allowlist) in pre-commit and every pipeline — report-only until history is triaged.

**Part of:** [[Quality Gates]]  ·  **Layer:** Quality gates

Pipelines: GitHub `secret-scan` (+ full-history job), GitLab `secret-scan` (`allow_failure`), Jenkins stage (UNSTABLE). Allowlist covers test-fixture credentials. Pre-commit hook `.githooks/pre-commit` runs Checkstyle + gitleaks (`SKIP_HOOKS=1` bypass), installed by `Scripts/install-hooks.sh`. Related practice: `${env:…}` placeholders, `SensitiveData` masking, never commit `captcha.ai.apiKey`.

## Connections

- **Related ↔** [[PlaceholderResolver]] · [[SensitiveData]] · [[Maintenance Scripts]]
