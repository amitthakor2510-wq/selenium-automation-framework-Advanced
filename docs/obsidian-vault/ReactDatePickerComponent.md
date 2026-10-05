---
title: "ReactDatePickerComponent"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/components/ReactDatePickerComponent.java"
loc: 92
---

# ReactDatePickerComponent

> [!abstract] Reusable react-datepicker: open input → pick month `<select>` → year `<select>` → click day cell.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/components/ReactDatePickerComponent.java` · 92 LOC

Extracted from logic that was hand-rolled twice — `DatePickerPage` and the date-of-birth field of `PracticeFormPage`. Constructed with the input plus month/year select locators; exposes `selectDate(month, year, day)`.

## Connections

- **Depends on →** [[BasePage]]
- **Used by ←** [[DemoQA]]
- **Related ↔** [[BootstrapModalComponent]]
