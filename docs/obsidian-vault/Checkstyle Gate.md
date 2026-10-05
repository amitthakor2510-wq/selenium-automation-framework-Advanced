---
title: "Checkstyle Gate"
type: tool
layer: quality
tags: [fw/quality, kind/tool]
parent: "[[Quality Gates]]"
---

# Checkstyle Gate

> [!abstract] Practical static analysis (`checkstyle.xml`, plugin 3.5.0) bound to `verify`.

**Part of:** [[Quality Gates]]  ·  **Layer:** Quality gates

Catches unused/duplicate/star imports (with test-annotation exceptions), missing braces, empty/nested blocks, `equals/hashCode` bugs, `==` on strings, empty/duplicate `switch` defaults. Skips line length, indentation, naming, full Javadoc. `violationSeverity=warning`, `failsOnError=true`. CI calls the named execution directly to avoid re-running the suite.

## Connections

- **Related ↔** [[Conventions and Troubleshooting]] · [[CI-CD Pipelines]]
