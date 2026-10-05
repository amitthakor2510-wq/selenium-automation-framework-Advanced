---
title: "OCR Accuracy Pipeline"
type: concept
layer: ai
tags: [fw/ai, kind/concept]
parent: "[[CAPTCHA Solving]]"
---

# OCR Accuracy Pipeline

> [!abstract] How CaptchaSolver fixes letter/digit confusion: per-character segmentation, case/length correction, border + line removal, deskew.

**Part of:** [[CAPTCHA Solving]]  ·  **Layer:** AI + CAPTCHA

`resolveViaOcr()` tries **segmentation first**, whole-string OCR as fallback:

1. **Preprocess** — contrast normalization → line removal → deskew → median denoise → Otsu threshold (2× upscale).
2. **`segmentedIdentify()`** — connected components, drop noise (`minComponentAreaRatio=0.12`), merge glyph fragments (dot of an "i"), remove decorative border frames (`borderFrame.minDimRatio=0.65`, `maxDensity=0.22`), split touching characters at ink-column valleys (`widthRatio=1.6`, `valleyMaxRatio=0.35`), OCR **each glyph alone** (Tesseract PSM 10, `paddingPx=6`, `upscale=4`).
3. **Back-off** when not trustworthy: component count outside `minChars/maxChars` (3/12) or any char below `confidenceFloor=35.0`.
4. **`applyRelativeCaseCorrection()`** — shape-symmetric letters (C/c, O/o, S/s, U/u, V/v, W/w, X/x, Z/z) lowered when height < `caseHeightRatio=0.78` of the tallest anchor glyph.
5. **`resolveExpectedLength()`** — uses the answer field's HTML `maxlength` (fallback `captcha.expected.length`) to catch over/under-segmentation.
6. **Whole-string OCR** (`identifyText`, PSM 7 → 8).

Tunables: all `captcha.segmentation.*` and `captcha.preprocessing.*` keys in [[global properties|global.properties]].

## Connections

- **Depends on →** [[CaptchaSolver]]
- **Related ↔** [[SAHMAT]] · [[global properties]]
