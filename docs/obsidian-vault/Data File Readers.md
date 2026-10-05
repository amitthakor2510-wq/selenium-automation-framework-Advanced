---
title: "Data File Readers"
type: class
layer: data
tags: [fw/data, kind/class]
parent: "[[Data-Driven Testing]]"
source: "core/data/readers/DataFileReaderRegistry.java"
loc: 83
---

# Data File Readers

> [!abstract] One `DataFileReader` implementation per format, picked by `DataFileReaderRegistry` from the file extension.

**Part of:** [[Data-Driven Testing]]  ·  **Layer:** Data-driven  ·  **Source:** `core/data/readers/DataFileReaderRegistry.java` · 83 LOC

| Reader | Format | Notes |
|---|---|---|
| `CsvDataFileReader` | `.csv` | OpenCSV; ragged rows padded (8 unit tests) |
| `ExcelDataFileReader` | `.xlsx/.xls` | Apache POI |
| `JsonDataFileReader` | `.json` | Jackson (6 unit tests) |
| `YamlDataFileReader` | `.yaml/.yml` | SnakeYAML |
| `ZipDataFileReader` | `.zip` | reads every supported file inside and merges rows |
| `DataFileReaderRegistry` | — | extension → reader; unknown extension = `DataFileException` |

Adding a format = implement `DataFileReader` and register it.

## Connections

- **Depends on →** [[Framework Exceptions]]
- **Used by ←** [[DataProvider]]
- **Related ↔** [[DataRow]]
