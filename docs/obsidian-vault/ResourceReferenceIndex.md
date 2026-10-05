---
title: "ResourceReferenceIndex"
type: class
layer: tia
tags: [fw/tia, kind/class]
parent: "[[Test Impact Analysis]]"
source: "core/tia/ResourceReferenceIndex.java"
loc: 87
---

# ResourceReferenceIndex

> [!abstract] Maps resource files (test data, keyword scripts) to the test classes whose source mentions their basename as a string literal.

**Part of:** [[Test Impact Analysis]]  ·  **Layer:** Test impact analysis  ·  **Source:** `core/tia/ResourceReferenceIndex.java` · 87 LOC

Scans every `.java` once for literals ending in resource-like extensions. A change to `testdata/login.yaml` maps precisely to the class(es) that read it. Tested in `ResourceReferenceIndexTest`.

## Connections

- **Used by ←** [[TestImpactAnalyzer]]
- **Related ↔** [[SiteMapper]]
