---
title: "global properties"
type: config
layer: config
tags: [fw/config, kind/config]
parent: "[[Configuration System]]"
aliases: [global.properties]
---

# global properties

> [!abstract] Shared defaults for every site — the ~35 documented keys that shape a run.

**Part of:** [[Configuration System]]  ·  **Layer:** Configuration

`src/test/resources/config/global.properties`

| Area | Keys (default) |
|---|---|
| Browser | `browser=chrome` · `headless=false` · `timeout=10` · `timeout.long=15` · `download.wait.seconds=10` |
| Pauses | `human.pause.*` (see [[HumanActions]]) |
| Retry | `retry.count=2` |
| Grid | `grid.enabled=false` · `grid.url=http://localhost:4444/wd/hub` |
| DDT | `data.tags=` · `data.execute.column=execute` · `synthetic.data.count=3` · `synthetic.data.seed=` |
| Self-healing | `self-healing.enabled=true` · `.threshold=0.55` · `.repository.path=self-healing-data/locator-repository.json` · `.visual.enabled=false` · `.visual.weight=0.5` · `.ai.enabled=false` · `.ai.confidence=0.6` |
| Video | `video.enabled=false` … (see [[VideoRecorder]]) |
| CAPTCHA | `captcha.autoDetect.enabled=true` · `captcha.ai.*` · `captcha.segmentation.*` · `captcha.preprocessing.*` · `captcha.expected.length` |
| AI text | `ai.provider=ollama` · `ai.model=qwen2.5-coder:7b` · `ai.timeout.seconds=60` · `ai.exceptionAnalysis.enabled=false` |
| AI vision | `ai.vision.provider=anthropic` · `ai.vision.*` |
| Crawler | `crawler.maxPages=50` · `.maxDepth=3` · `.outputDir=target/crawler` · `.ai.enabled` · `.checklistFile` · `.ai.vision.enabled` · `crawler.mobile.*` |
| API | `api.log.onFailureOnly=true` · `api.retry.count=0` · `api.retry.backoffMs=500` · `api.responseTime.maxMs=5000` |
| Perf | `perf.threads=10` · `.rampUpSeconds=5` · `.iterations=5` · `.maxP99Millis=5000` · `.maxErrorRatePercent=1.0` |

> The committed file carries real endpoint/model settings for the author's local Ollama box; API keys must come from env (`ANTHROPIC_API_KEY`), never the file.

## Connections

- **Depends on →** [[ConfigReader]]
- **Related ↔** [[Site Config Files]] · [[SelfHealingEngine]] · [[CaptchaSolver]] · [[OllamaClient]] · [[PerfConfig and Assertions]] · [[ApiConfig]]
