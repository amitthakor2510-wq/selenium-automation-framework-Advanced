---
title: "SiteCrawler"
type: class
layer: ai
tags: [fw/ai, kind/class]
parent: "[[AI Features]]"
source: "core/crawler/SiteCrawler.java"
loc: 521
---

# SiteCrawler

> [!abstract] Breadth-first same-host crawler that runs rule-based bug checks on every page (+ optional AI passes). Run via `CrawlerCli` or the `bug-crawler` profile.

**Part of:** [[AI Features]]  ·  **Layer:** AI + CAPTCHA  ·  **Source:** `core/crawler/SiteCrawler.java` · 521 LOC

**Checks per page:** broken links/images (HTTP `HEAD`→`GET`, same-host requests carry the browser session cookies so pages behind login aren't false positives; off-site checked cookie-free) · console `SEVERE` errors · duplicate `id`s · empty `href`/`alt` · mixed content · axe-core violations ([[AccessibilityUtils]]).

**Config:** `crawler.maxPages=50`, `crawler.maxDepth=3`, `crawler.outputDir=target/crawler`, `crawler.a11y.enabled=true`.

Output: `CrawlReport` JSON + text via `CrawlReportWriter` (`CrawlIssue`, `PageResult` models). Run: `mvn exec:java@bug-crawler -Pbug-crawler -Dcrawler.startUrl=https://example.com`. Never fails a build on its own.

## Connections

- **Depends on →** [[AccessibilityUtils]] · [[AI Page Reviewers]] · [[OllamaClient]]
- **Used by ←** [[Maven Profiles]]
- **Related ↔** [[MobileAppCrawler]]
