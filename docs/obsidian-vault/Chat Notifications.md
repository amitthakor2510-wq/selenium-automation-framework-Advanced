---
title: "Chat Notifications"
type: tool
layer: ci
tags: [fw/ci, kind/tool]
parent: "[[CI-CD Pipelines]]"
---

# Chat Notifications

> [!abstract] `notify_chat.py` — stdlib-only Slack/Teams run summary shared by all three pipelines.

**Part of:** [[CI-CD Pipelines]]  ·  **Layer:** CI / CD

Secret `CHAT_WEBHOOK_URL` (GitHub secret / GitLab masked var / Jenkins credential `chat-webhook-url`); `CHAT_PROVIDER` slack|teams|auto, `NOTIFY_ON` failure|always. Zero parsed tests is never green; never fails the pipeline (4xx not retried, others 3 attempts). Tests: `test_notify_chat.py` against a localhost server.

## Connections

- **Related ↔** [[CI Python Scripts]]
