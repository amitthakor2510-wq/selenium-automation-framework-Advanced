---
title: "SyntheticDataGenerator"
type: class
layer: data
tags: [fw/data, kind/class]
parent: "[[Data-Driven Testing]]"
source: "core/data/synthetic/SyntheticDataGenerator.java"
loc: 131
---

# SyntheticDataGenerator

> [!abstract] DataFaker-powered realistic data (names, emails, usernames, passwords) plus fixed boundary/edge-case values — seedable for reproduction.

**Part of:** [[Data-Driven Testing]]  ·  **Layer:** Data-driven  ·  **Source:** `core/data/synthetic/SyntheticDataGenerator.java` · 131 LOC

`SyntheticDataGenerator` (131 LOC) wraps `Faker`; `SyntheticDataProvider` (115 LOC) converts output into the same [[DataRow]] list the file readers produce, so `@DataProvider`s are interchangeable.

- `synthetic.data.count=3` (small on purpose — demoqa registration is ReCaptcha-rate-limited).
- `synthetic.data.seed=` blank = fresh random data; set an integer to replay the exact sequence of a failing run.
- Used by `RegistrationSyntheticDataTest`; suite `demoqa-synthetic-data.xml` (group `synthetic-data`).

## Connections

- **Depends on →** [[DataRow]]
- **Used by ←** [[DataProvider]] · [[DemoQA]]
- **Related ↔** [[global properties]]
