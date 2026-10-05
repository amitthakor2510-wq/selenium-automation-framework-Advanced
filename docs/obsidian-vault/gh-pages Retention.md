---
title: "gh-pages Retention"
type: pipeline
layer: ci
tags: [fw/ci, kind/pipeline]
parent: "[[CI-CD Pipelines]]"
---

# gh-pages Retention

> [!abstract] Prevents unbounded growth of the `gh-pages` branch, GitLab Pages artifacts and local videos.

**Part of:** [[CI-CD Pipelines]]  ·  **Layer:** CI / CD

`gh-pages-retention.yml` (weekly + manual): once the branch exceeds `KEEP_COMMITS` (50) it rewrites history to one commit with the same tree and force-pushes. Allure `keep_reports: 20`; videos never copied to Pages (7-day artifact). GitLab `pages` artifact `expire_in: 30 days`. Jenkins `numToKeepStr: 10`. Policy doc: `RETENTION_POLICY.md`.

## Connections

- **Depends on →** [[ArtifactRetentionCleaner]]
- **Related ↔** [[VideoRecorder]]
