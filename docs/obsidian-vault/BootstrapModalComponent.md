---
title: "BootstrapModalComponent"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/components/BootstrapModalComponent.java"
loc: 152
---

# BootstrapModalComponent

> [!abstract] Reusable Bootstrap modal: wait-for-open, title/body reads, and a hardened 3-attempt close.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/components/BootstrapModalComponent.java` · 152 LOC

`waitForOpen()`, `getTitle()`, `getBody()`, `closeHardened()` — JS-click the close button → Escape → backdrop click, stopping at the first that works. Composed by `ModalDialogsPage` (small + large instances) and `PracticeFormPage`. `WebTablesPage` deliberately does **not** use it (its dialog is a form with no close button).

## Connections

- **Depends on →** [[BasePage]]
- **Used by ←** [[DemoQA]]
- **Related ↔** [[ReactDatePickerComponent]]
