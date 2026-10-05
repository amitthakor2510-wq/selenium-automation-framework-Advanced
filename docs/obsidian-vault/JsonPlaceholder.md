---
title: "JsonPlaceholder"
type: site
layer: site
tags: [fw/site, kind/site]
parent: "[[Sites Under Test]]"
---

# JsonPlaceholder

> [!abstract] API-only reference site (no browser) — `JsonPlaceholderApiTest` + load test.

**Part of:** [[Sites Under Test]]  ·  **Layer:** Sites under test

`url=https://jsonplaceholder.typicode.com`, tagged `site.jsonplaceholder.type=api` (excluded from the browser matrix by `enabled-sites.sh --browser-only`). Suites `api-tests-jsonplaceholder.xml`, `jsonplaceholder-perf.xml`. Schema `post.json`. Produced from the manual checklist later automated by [[new-api-site script|new-api-site.sh]].

## Connections

- **Depends on →** [[ApiClient]] · [[PerfTestBase]]
- **Related ↔** [[API Testing Layer]] · [[enabled-sites script]] · [[pipeline-config properties]]
