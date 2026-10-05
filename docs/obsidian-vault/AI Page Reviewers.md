---
title: "AI Page Reviewers"
type: class
layer: ai
tags: [fw/ai, kind/class]
parent: "[[AI Features]]"
source: "core/crawler/AiPageReviewer.java"
loc: 88
---

# AI Page Reviewers

> [!abstract] Three optional per-page AI passes: open-ended HTML review, checklist review, and screenshot review.

**Part of:** [[AI Features]]  ·  **Layer:** AI + CAPTCHA  ·  **Source:** `core/crawler/AiPageReviewer.java` · 88 LOC

| Class | Flag | What it does |
|---|---|---|
| `AiPageReviewer` | `crawler.ai.enabled` | flags placeholder/lorem text, rendered stack traces, mismatched labels — told **not** to repeat rule-based findings |
| `AiChecklistReviewer` | `crawler.checklistFile=path` | checks each page against *your* list (one bug per line, `#` comments) e.g. "the footer copyright year is stuck on 2023" |
| `AiScreenshotReviewer` | `crawler.ai.vision.enabled` | sends a full-viewport screenshot to the vision model (`ai.vision.*`) for layout/rendering bugs |

The first two share one page-source fetch per page; an unreachable model skips the pass (debug log) and never fails the crawl.

## Connections

- **Depends on →** [[OllamaClient]] · [[AiVisionClient]]
- **Used by ←** [[SiteCrawler]] · [[MobileAppCrawler]]
