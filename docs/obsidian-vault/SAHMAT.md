---
title: "SAHMAT"
type: site
layer: site
tags: [fw/site, kind/site]
parent: "[[Sites Under Test]]"
---

# SAHMAT

> [!abstract] Government PM Gati Shakti permission portal (staging) — a single self-expanding keyword test that exercises login/OTP/CAPTCHA/forgot-password.

**Part of:** [[Sites Under Test]]  ·  **Layer:** Sites under test

- URL `https://staging2.pmgatishakti.gov.in/PermissionPortal/`.
- **One `@Test`** (`runScenario`) in `LoginAndForgotPasswordKeywordTest`, with a `@DataProvider` that reads every `testCase` from `SAHMAT_login_forgot_password_keywords.csv` → new scenarios need no Java (groups `smoke, regression, keyword-driven`).
- Flow per case: `NAVIGATE` → `WAIT_FOR_PAGE_LOAD` → open login sub-module → email/password via `${env:SAHMAT_EMAIL}` / `${env:SAHMAT_PASSWORD}` → *Send OTP* → static staging OTP `000000` → `SOLVE_TEXT_CAPTCHA_IF_PRESENT` → submit → assert. Cases include valid login, wrong password, unregistered email, forgot-password.
- Object repository mixes absolute XPaths with attribute-substring CSS for OTP/CAPTCHA fields.
- CAPTCHA: `captcha.expected.length=5`, charset A-Za-z0-9, AI Vision on, OCR cross-check off.
- Suites: `SAHMAT-smoke/regression` (+ Safari variants); helper `Scripts/run-SAHMAT-all-browsers.sh` runs chrome → firefox → edge one at a time.

## Connections

- **Depends on →** [[KeywordTestBase]] · [[ObjectRepository]] · [[CaptchaSolver]] · [[SelfHealingEngine]] · [[PlaceholderResolver]]
- **Related ↔** [[Keyword-Driven Testing]] · [[CAPTCHA Solving]] · [[Maintenance Scripts]] · [[OCR Accuracy Pipeline]]
