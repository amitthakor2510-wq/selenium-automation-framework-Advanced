---
title: "Framework Exceptions"
type: class
layer: core
tags: [fw/core, kind/class]
parent: "[[Core Framework]]"
source: "core/exceptions/FrameworkException.java"
loc: 31
---

# Framework Exceptions

> [!abstract] Typed exception hierarchy separating framework infrastructure failures from test assertion failures.

**Part of:** [[Core Framework]]  ·  **Layer:** Core framework  ·  **Source:** `core/exceptions/FrameworkException.java` · 31 LOC

`FrameworkException extends RuntimeException` →
- `ConfigException` — missing config file/key
- `DataFileException` — unreadable/malformed data file, bad column, unregistered extension
- `DriverInitializationException` — cannot create a session (unsupported browser, missing binary)
- `KeywordExecutionException` — unknown keyword, missing locator, malformed row, unresolved data key

[[RetryAnalyzer]] uses these types to decide whether a retry could ever help.

## Connections

- **Used by ←** [[RetryAnalyzer]] · [[KeywordEngine]] · [[ConfigReader]] · [[DriverFactory]] · [[Data File Readers]]
