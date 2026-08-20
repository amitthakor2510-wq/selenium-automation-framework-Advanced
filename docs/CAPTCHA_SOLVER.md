<div align="center">

# 🧩 CAPTCHA Solver

*Automatic AND keyword-driven CAPTCHA solving — wired into every Page Object (old and new), every keyword-driven test, and mobile.*

</div>

---

## 📋 Table of Contents
- [🧠 How It's Wired (the short version)](#-how-its-wired-the-short-version)
- [⚙️ Setup](#️-setup)
- [▶️ Running It](#️-running-it)
- [🪄 Mode 1 — Automatic (zero test code)](#-mode-1--automatic-zero-test-code)
- [🎯 Mode 2 — Explicit (keyword-driven)](#-mode-2--explicit-keyword-driven)
- [🚫 What It Can't Solve](#-what-it-cant-solve)
- [🔡 OCR Accuracy Pipeline (letter/digit confusion)](#-ocr-accuracy-pipeline-letterdigit-confusion)
- [🔧 Configuration Reference](#-configuration-reference)
- [🤖 AI Vision provider setup](#-ai-vision-provider-setup)
- [🧪 Verifying It's Actually Working](#-verifying-its-actually-working)
- [🩹 Troubleshooting](#-troubleshooting)
- [📐 Design Notes / Extending It](#-design-notes--extending-it)

---

## 🧠 How It's Wired (the short version)

There is exactly **one** class that does the solving — `com.automation.core.utils.CaptchaSolver` — and **two** ways it gets invoked. Every test in this project goes through one of them automatically; nothing needs to be hand-wired per site or per page.

| Test style | How CAPTCHA solving reaches it | Where the hook lives |
|---|---|---|
| Page-Object-Model UI tests (`BookStoreApplicationPage`, `PracticeFormPage`, every `sites/*/pages/*.java`, "old" or newly added) | **Automatic** — every call to `navigateTo(...)` triggers a detect-and-solve pass | `BasePage.navigateTo()` → `BasePage.handleCaptchaIfPresent()` |
| Keyword-driven tests (CSV/Excel/JSON/YAML scripts run through `KeywordEngine`) | **Automatic** after every `NAVIGATE` step, **plus** explicit `SOLVE_TEXT_CAPTCHA` / `SOLVE_MATH_CAPTCHA` / `SOLVE_CAPTCHA_WITH_AI` keywords when you want to name the exact elements | `KeywordEngine.execute()` |
| Mobile / Appium screen objects (`mobile/sites/*/pages/*.java`) | Same detection/solve logic, called explicitly after a screen transition that might show one (mobile apps don't "navigate" the way a browser does, so this isn't auto-fired from the constructor) | `BaseMobilePage.handleCaptchaIfPresent()` |
| REST API tests (`BookStoreApiTest`, etc.) | Not applicable — there's no image to OCR in a pure HTTP call | — |

Because the automatic hook lives in **`BasePage`** and **`BaseMobilePage`** — the parent class of every single Page Object / screen object in the framework, existing and future — adding CAPTCHA coverage to a brand-new site or page never requires writing any CAPTCHA-specific code. Copy `TemplatePage.java` (or run `Scripts/new-site.sh`), extend `BasePage` like normal, and it's covered.

---

## ⚙️ Setup

### 1. Native Tesseract binary (required)

`CaptchaSolver` uses [Tess4J](https://github.com/nguyenq/tess4j), a JNI wrapper — it does **not** bundle Tesseract itself. The actual OCR engine must be installed on whatever machine runs `mvn test`.

| Environment | Command | Status in this project |
|---|---|---|
| Local Ubuntu/Debian | `sudo apt-get install -y tesseract-ocr tesseract-ocr-eng` | your responsibility — see below |
| Local macOS | `brew install tesseract` | your responsibility |
| Local Windows | Install from the [official installer](https://github.com/UB-Mannheim/tesseract/wiki), default path `C:\Program Files\Tesseract-OCR\tessdata` | your responsibility |
| Docker (`docker compose run tests`) | — | ✅ already installed by `Dockerfile` |
| Jenkins (bare-metal agent) | — | ✅ auto-installs itself at the start of `Run Tests Per Site` if missing (needs the agent's sudo/root) |
| GitHub Actions | — | ✅ `Setup Tesseract OCR (CaptchaSolver)` step installs it per-OS before `Run Tests` |
| GitLab CI | — | ✅ install-if-missing block right after the browser setup, same pattern as Chrome/Firefox/Edge |

Run `tesseract --version` to confirm — if that command isn't found, nothing else here will work regardless of Java-side config.

### 2. Maven dependency (already present)

`tess4j` is already declared in `pom.xml`:

```xml
<dependency>
    <groupId>net.sourceforge.tess4j</groupId>
    <artifactId>tess4j</artifactId>
    <version>5.11.0</version>
</dependency>
```

Nothing to add here — this is just so you know where to look if you ever bump the version.

### 3. tessdata path (auto-detected — usually nothing to do)

`CaptchaSolver` resolves the tessdata directory in this order, and fails fast with an actionable message if none of them work:

1. `-Dtesseract.datapath=...` or `tesseract.datapath=` in `config/global.properties` / `config/{site}.properties`
2. `TESSDATA_PREFIX` environment variable, if already set on the host/container
3. First existing path from a built-in candidate list covering Debian/Ubuntu (incl. this project's own Docker image), RHEL/Fedora/Alpine, macOS Homebrew (Intel + Apple Silicon), and Windows

You only need to set `tesseract.datapath` explicitly if auto-detection picks the wrong install, or Tesseract lives somewhere non-standard:

```properties
# config/global.properties
tesseract.datapath=/usr/share/tesseract-ocr/5/tessdata
```

---

## ▶️ Running It

Nothing extra to pass — it's on by default. Run tests the normal way:

```bash
mvn test -Dsite=demoqa -Dbrowser=chrome
mvn test -Dsite=saucedemo -Dbrowser=chrome -DsuiteXmlFile=testng-suites/saucedemo-keyword-driven.xml
```

If a page happens to render a solvable text/math CAPTCHA, you'll see it in the logs without having written anything CAPTCHA-specific:

```
INFO  CaptchaSolver initialised with tessdata at: /usr/share/tesseract-ocr/5/tessdata
INFO  🔎 CAPTCHA auto-detected on page — attempting automatic OCR solve
INFO  ▶ solveTextCaptcha started
INFO  ✅ Text CAPTCHA solved: [8X3K9Q]
INFO  Typed CAPTCHA answer into field: [8X3K9Q]
```

To turn it off (e.g. a suite that intentionally wants to assert on CAPTCHA-blocked behavior itself, like `RegistrationPage.isBlockedByRecaptcha()`):

```bash
mvn test -Dcaptcha.autoDetect.enabled=false
```

---

## 🪄 Mode 1 — Automatic (zero test code)

This is what makes it "wired automatically in new and old." `CaptchaSolver` exposes static, side-effect-free detection methods that any driver-facing code can call:

```java
CaptchaSolver.detectCaptchaImage(driver);        // Optional<WebElement>
CaptchaSolver.detectCaptchaInputField(driver);   // Optional<WebElement>
CaptchaSolver.isKnownUnsolvableCaptchaPresent(driver); // reCAPTCHA/hCaptcha check
```

`BasePage.handleCaptchaIfPresent()` and `BaseMobilePage.handleCaptchaIfPresent()` wrap these into one no-throw, no-config-needed call:

```java
// BasePage.navigateTo() — already wired, shown here for reference
protected void navigateTo(String path) {
    driver.get(...);
    handleCaptchaIfPresent();   // <-- this line is the whole integration
}
```

Detection scans for common, case-insensitive `id`/`class`/`name`/`alt`/`src` patterns containing `captcha` on `<img>`/`<canvas>` elements, and a matching nearby `<input>`. It is:

- **Cheap** — a handful of `findElements()` calls, no screenshot/OCR unless something actually matches.
- **Silent when absent** — the overwhelming majority of pages have no CAPTCHA; detection returns instantly and the page object continues completely normally.
- **Non-fatal** — any exception during detection/solving is caught and logged as a warning; it can never fail an unrelated test.

If you want it to run again after some in-page action that isn't a full `navigateTo(...)` (e.g. a failed-login retry that reveals a CAPTCHA it didn't show the first time), call it directly from your page object:

```java
public void submitLogin(String user, String pass) {
    ...
    submitButton.click();
    handleCaptchaIfPresent();   // inherited from BasePage — safe to call any time
}
```

Mobile screen objects call the same method explicitly (there's no single "navigation" moment to hook automatically, since the app is already launched before any screen object exists):

```java
public class LoginScreen extends BaseMobilePage {
    public void afterLoginAttempt() {
        handleCaptchaIfPresent();
    }
}
```

---

## 🎯 Mode 2 — Explicit (keyword-driven)

For CSV/Excel/JSON/YAML keyword scripts, name the CAPTCHA image and the answer field explicitly instead of relying on pattern-matching — useful when a site's markup doesn't contain the word "captcha" anywhere, or when you want a test to fail loudly if the CAPTCHA locator ever breaks (rather than silently skipping, which auto-detect does).

Four keywords, all already handled by `KeywordEngine.execute()`:

| Keyword | What it does |
|---|---|
| `SOLVE_TEXT_CAPTCHA` | OCR reads an alphanumeric image CAPTCHA and types the result. **Hard-fails the test** if the image locator never resolves — use when a CAPTCHA is guaranteed to be present and you want a broken locator to fail loudly. |
| `SOLVE_TEXT_CAPTCHA_IF_PRESENT` | Same row shape as `SOLVE_TEXT_CAPTCHA` (waits up to `captcha.wait.seconds`, default `timeout.long`, for the image to appear), but if it never renders — conditional CAPTCHAs, slow/flaky pages — it logs and **moves on to the next step instead of failing the test**. Clicks the input field before handing off to the solver. Use this whenever a CAPTCHA is only sometimes shown (e.g. SAHMAT's login sub-module). |
| `SOLVE_MATH_CAPTCHA` | OCR reads a math-expression image (`3 + 5 = ?`) and types the evaluated answer |
| `SOLVE_CAPTCHA_WITH_AI` | Reserved for a Vision-API-based solver; currently falls back to OCR (see [Design Notes](#-design-notes--extending-it)) |

**Convention:** `locatorKey` points at the CAPTCHA image (same as every other keyword), and `testData` holds the ObjectRepository key of the input field to type the answer into — because `KeywordStep` only carries one locator slot, but solving a CAPTCHA needs two elements. This convention is shared by `SOLVE_TEXT_CAPTCHA_IF_PRESENT` too.

A related keyword, `WAIT_FOR_PAGE_LOAD`, is worth pairing with either CAPTCHA keyword on slow-loading or SPA-style pages — it waits for `document.readyState == 'complete'` (real signal, not a fixed sleep) instead of racing a CAPTCHA/element check against a page that's still rendering. It never fails the test on its own; see [Configuration Reference](#-configuration-reference) for `pageLoad.timeout`. Example, matching SAHMAT's login flow (the login form is a dynamically-rendered sub-module, not a fresh page load):

```
TC01,1,NAVIGATE,,,,Go to the homepage
TC01,2,WAIT_FOR_PAGE_LOAD,,,,Wait for the homepage to finish loading
TC01,3,CLICK,site.header.loginTrigger,,,Open the login sub-module
TC01,4,WAIT_FOR_PAGE_LOAD,,,,Wait for the (dynamically rendered) sub-module to finish loading
TC01,5,TYPE,site.login.username,myuser,,Enter username
TC01,6,TYPE,site.login.password,mypass,,Enter password
TC01,7,SOLVE_TEXT_CAPTCHA_IF_PRESENT,site.login.captchaImage,site.login.captchaInput,,Solve the CAPTCHA if one actually rendered this run
TC01,8,CLICK,site.login.submitButton,,,Submit
```

```
# testdata/keyword/<site>_login_keywords.csv
testCase,stepNo,keyword,locatorKey,testData,expected,description
TC01,1,NAVIGATE,,/login,,Open login page
TC01,2,TYPE,site.login.username,myuser,,Enter username
TC01,3,TYPE,site.login.password,mypass,,Enter password
TC01,4,SOLVE_TEXT_CAPTCHA,site.login.captchaImage,site.login.captchaInput,,Solve the CAPTCHA
TC01,5,CLICK,site.login.submitButton,,,Submit
TC01,6,VERIFY_URL_CONTAINS,,/dashboard,Login succeeded
```

And in the matching `objectrepository/<site>.properties`:

```properties
site.login.captchaImage=id:captchaImg
site.login.captchaInput=id:captchaInput
```

Both locator keys go through `SelfHealingEngine` the same as every other keyword, so minor DOM drift on the CAPTCHA widget heals itself the same way a broken login-button locator would.

---

## 🚫 What It Can't Solve

OCR against a rendered image only ever works for genuine **text or math** CAPTCHAs. It is deliberately **not** attempted against:

- **Google reCAPTCHA** (`iframe[src*=recaptcha]`, `.g-recaptcha`) — including the checkbox/"I'm not a robot" and invisible variants
- **hCaptcha** (`iframe[src*=hcaptcha]`, `.h-captcha`)

`CaptchaSolver.isKnownUnsolvableCaptchaPresent(driver)` detects these and backs off with a clear log message instead of wasting time trying (and failing) to OCR them:

```
WARN  ⚠ reCAPTCHA/hCaptcha detected on page — cannot be auto-solved via OCR
      (needs a human, a paid solving service, or a test-account/API workaround). Skipping.
```

This is a **real, already-documented scenario in this project**: DemoQA's own `/register` endpoint can return a server-side `"Please verify ReCaptcha!"` error under rate-limiting, which is exactly this case — see `RegistrationPage.isBlockedByRecaptcha()` and the note in that class. The fix for that scenario isn't OCR; it's reducing registration frequency, reusing an existing test account, or seeding accounts via a direct API call instead of the UI.

---

## 🔡 OCR Accuracy Pipeline (letter/digit confusion)

Whenever AI Vision is disabled or its API call fails, `CaptchaSolver` no longer just runs a single whole-string OCR pass. `resolveViaOcr()` (used by both `SOLVE_TEXT_CAPTCHA` and `SOLVE_CAPTCHA_WITH_AI`'s OCR fallback) tries two strategies against the same preprocessed image:

1. **Per-character segmentation (`segmentedIdentify()`) — the primary path.** The CAPTCHA image is split into individual character blobs (connected-component analysis), noise speckles are dropped, fragments of the same glyph (e.g. the dot of an "i") are merged back together, and then **each character is OCR'd completely alone** (Tesseract PSM 10) instead of as part of the full string. This is the actual fix for confusable-glyph misreads like `0`/`O`, `1`/`I`/`l`, `5`/`S`, `6`/`G`, `8`/`B`, `9`/`g` — whole-string OCR reads every character with its neighbours' strokes bleeding into the same recognition window, which is the single biggest cause of "most characters right, a few wrong." Each isolated character is OCR'd three times (full charset, digits-only, letters-only) and the highest-confidence read wins; when the top two candidates disagree on a *known* confusable pair and land close in confidence, a small geometric shape check (`resolveConfusableByShape()` — hole counting, ink-density regions, aspect ratio) gets the final say for the pairs it can actually discriminate (`5`/`S`, `8`/`B`, `6`/`G`, `9`/`g`/`q`). `0`/`O` is deliberately left to OCR confidence alone — there's no shape rule that holds up across real-world CAPTCHA fonts.

   Segmentation automatically backs off (returns nothing, no error) whenever it isn't trustworthy for a given image — too few/many components after filtering (`captcha.segmentation.minChars`/`maxChars`), or any single character too low-confidence (`captcha.segmentation.confidenceFloor`) — and the pipeline falls back to whole-string OCR for that image.

   **Upper/lower case correction (`applyRelativeCaseCorrection()`).** Isolating and independently upscaling each glyph — the thing that fixes shape confusion above — breaks case detection for the letters whose upper- and lower-case forms are literally the same shape at different sizes: `C`/`c`, `O`/`o`, `S`/`s`, `U`/`u`, `V`/`v`, `W`/`w`, `X`/`x`, `Z`/`z`. A cropped, upscaled "z" and a cropped, upscaled "Z" look identical to the classifier once the surrounding string's relative sizing is gone, which is why (before this fix) a lowercase `z` would reliably come back as `Z`. The fix: after every character in the CAPTCHA has an initial guess, find the tallest "anchor" character — a digit or a letter whose case *does* change its shape (so its height reliably reflects a full-height glyph in this rendering) — and reclassify any of the 8 shape-symmetric letters above whose own height comes in well below that anchor (`captcha.segmentation.caseHeightRatio`, default `0.78`) as lower-case. If a CAPTCHA happens to contain only shape-symmetric letters and no anchor at all, there's nothing reliable to correct against, so the original OCR case guess is left alone rather than risk an ungrounded flip. This correction only runs on the connected-component segmentation path — the grid-segmentation fallback (below) crops every slice to the full image height by construction, so there's no per-character height signal available there.

   **Known-length correction (`resolveExpectedLength()`).** `solveTextCaptcha()`/`solveWithAI()` read the answer input field's own HTML `maxlength` attribute before OCR-ing anything (falling back to `captcha.expected.length` if the field has no `maxlength` set). If connected-component segmentation finds a different number of characters than that known length — the actual root cause behind a symptom like "the solved answer looks right but only part of it ends up in the field": segmentation over/under-reads by 1-4 characters (a noise speckle picked up as a fake glyph, or one glyph accidentally split into two), that wrong-length string still passes the broad `minChars`/`maxChars` range check and gets typed in full, then silently truncated by the field's own `maxlength` — grid segmentation is tried instead, since it segments by count rather than by shape and isn't fooled by the same noise/split. If grid segmentation *also* can't produce the right count, the segmented result is abandoned entirely (falls back to whole-string OCR) rather than typing an answer already known to be the wrong length.

2. **Whole-string OCR (`identifyText()`)** — the original PSM 7 (falling back to PSM 8) pass, run either as the primary result (segmentation disabled/inapplicable) or discarded in favour of the segmented read when both succeed.

`preprocessImage()` also gained three passes ahead of the existing median-filter denoise + Otsu threshold, all of which help segmentation produce cleaner, separable character blobs (not just whole-string OCR accuracy):
- **Contrast normalization** — stretches a washed-out/low-contrast image to the full black-white range before anything else runs.
- **Line removal** — erases long, thin decorative strike-through/underline lines some CAPTCHA generators draw specifically to defeat OCR (only ones that don't already merge with a character stroke into one connected shape — a line that cuts straight through a glyph can't be distinguished from the glyph itself this way).
- **Deskew** — searches a small rotation range and straightens a CAPTCHA rendered at a slight angle, which otherwise throws off both segmentation's bounding boxes and whole-string OCR's baseline assumption.

All of the above is tunable per-site via the `captcha.segmentation.*` / `captcha.preprocessing.*` keys in the [Configuration Reference](#-configuration-reference) — e.g. a CAPTCHA generator that always renders exactly 6 characters can tighten `minChars`/`maxChars` to `6`/`6` so segmentation never accidentally accepts a miscounted read.

---

## 🔧 Configuration Reference

All in `src/test/resources/config/global.properties` (or override with `-D` / a site-specific `config/{site}.properties`):

| Key | Default | Meaning |
|---|---|---|
| `captcha.autoDetect.enabled` | `true` | Master switch for **Mode 1** (automatic). Explicit `SOLVE_*` keywords in Mode 2 are unaffected by this flag — they run whenever a script uses them, regardless. |
| `captcha.wait.seconds` | falls back to `timeout.long` (`15`) | How long `SOLVE_TEXT_CAPTCHA_IF_PRESENT` waits for the CAPTCHA image to appear before treating this run as having no CAPTCHA and continuing (no test failure). |
| `pageLoad.timeout` | falls back to `timeout.long` (`15`) | How long `WAIT_FOR_PAGE_LOAD` waits for `document.readyState == 'complete'` before logging a warning and continuing anyway. Can also be overridden per-step via that row's `testData` (seconds). |
| `tesseract.datapath` | *(unset — auto-detected)* | Overrides tessdata directory resolution; see [Setup](#️-setup) |
| `captcha.segmentation.enabled` | `true` | Master switch for per-character segmentation (see [OCR accuracy pipeline](#-ocr-accuracy-pipeline-letterdigit-confusion) below). When `false`, only the original whole-string OCR runs. |
| `captcha.segmentation.minChars` / `.maxChars` | `3` / `12` | Segmentation is abandoned (falls back to whole-string OCR) unless the image separates into a component count in this range. |
| `captcha.segmentation.paddingPx` | `6` | Whitespace padding added around each isolated character crop before OCR-ing it alone. |
| `captcha.segmentation.upscale` | `4` | Extra upscale factor applied to each single-character crop, on top of the full image's own 2x upscale. |
| `captcha.segmentation.minComponentAreaRatio` | `0.12` | Components smaller than `median component area * this ratio` are treated as noise/speckle and dropped. Raise for noisy CAPTCHAs; lower if small real characters get filtered out. |
| `captcha.segmentation.confidenceFloor` | `35.0` | Minimum Tesseract confidence (0-100) a single isolated character needs before the segmented result is trusted at all. |
| `captcha.preprocessing.deskew.enabled` | `true` | Straightens a CAPTCHA strip rendered at a slight angle before OCR/segmentation. |
| `captcha.preprocessing.lineRemoval.enabled` | `true` | Erases long, thin decorative strike-through/underline lines that don't already touch a character stroke. |
| `captcha.ai.enabled` | `false` | Master switch — when `true`, `SOLVE_TEXT_CAPTCHA`/`SOLVE_TEXT_CAPTCHA_IF_PRESENT` try AI Vision first and fall back to OCR only if the API call itself fails. `SOLVE_CAPTCHA_WITH_AI` always uses AI Vision regardless of this flag. |
| `captcha.ai.provider` | `anthropic` | `anthropic` or `ollama` — see [AI Vision provider setup](#-ai-vision-provider-setup) below. |
| `captcha.ai.endpoint` | provider default | Anthropic: `https://api.anthropic.com/v1/messages`. Ollama: `http://localhost:11434/api/generate`. Override for a remote/non-default-port Ollama host. |
| `captcha.ai.apiKey` | falls back to `ANTHROPIC_API_KEY` env var | Required for `provider=anthropic`. Not required for `provider=ollama` — if set anyway, sent as an `Authorization: Bearer` header (useful behind an authenticated reverse proxy). |
| `captcha.ai.model` | *(unset — required)* | A vision-capable model you have access to. Anthropic: a current Claude model. Ollama: a vision-capable local model tag, e.g. `llava`. |
| `captcha.image.load.timeout` | `10` | Seconds to wait for the CAPTCHA `<img>` itself to finish loading (`img.complete && naturalWidth > 0`) before screenshotting/solving it. Separate from `pageLoad.timeout`/`WAIT_FOR_PAGE_LOAD`, which only checks `document.readyState` and can report "complete" well before an async-rendered CAPTCHA image has actually loaded — screenshotting too early captures a broken-image placeholder (icon + alt text) instead of the real CAPTCHA, which then gets OCR'd/vision-read as garbage that isn't a solving-accuracy problem at all. |

Environment variable (not a `config/*.properties` key):

| Variable | Meaning |
|---|---|
| `TESSDATA_PREFIX` | Standard Tesseract env var; used as a fallback if `tesseract.datapath` isn't set |

---

## 🤖 AI Vision provider setup

### Anthropic (default)

```properties
captcha.ai.enabled=true
captcha.ai.provider=anthropic
captcha.ai.apiKey=          # or set ANTHROPIC_API_KEY instead
captcha.ai.model=claude-...  # a current vision-capable Claude model
```

### Ollama (local or remote, e.g. `llava`)

`CaptchaSolver` talks to Ollama's `/api/generate` directly — a different
request/response shape than Anthropic's, handled natively, not just a
different URL. No API key needed.

```properties
captcha.ai.enabled=true
captcha.ai.provider=ollama
captcha.ai.endpoint=http://192.168.2.17:11434/api/generate   # your Ollama host:port
captcha.ai.model=llava
```

Checklist if it's not picking up an answer:

1. **Confirm the property names above exactly.** `captcha.ai.endpoint`,
   not `captcha.api.url` — a value under any other key is silently
   ignored by `ConfigReader`, and the solver falls back to the Anthropic
   default endpoint with no key configured, which fails closed (then OCR
   fallback kicks in, so you'd see plausible-but-wrong OCR answers rather
   than an obvious error).
2. **Confirm `captcha.ai.provider=ollama` is actually set.** Pointing
   `captcha.ai.endpoint` at an Ollama URL while `captcha.ai.provider` is
   still (implicitly) `anthropic` sends an Anthropic-shaped request body
   (`x-api-key` header, `content` block array) to an endpoint that doesn't
   understand it — Ollama will reject it, not silently adapt.
3. **`curl` the endpoint from the machine actually running the tests**
   (not just the Ollama host itself) — `curl http://<host>:11434/api/tags`
   should list your model. If the test machine is remote, the Ollama host
   needs `OLLAMA_HOST=0.0.0.0` (systemd override) and its firewall must
   allow the port; `curl`-ing `/` alone only proves Ollama is running
   locally on its own box, not that it's reachable from elsewhere.
4. Watch the log line on failure — `resolveWithVisionApi()` logs the raw
   HTTP status/body on a non-200 response and retries 429/5xx up to 3
   times before falling back to OCR, so a bad model name, wrong port, or
   unreachable host shows up there.

---

## 🧪 Verifying It's Actually Working

There's no live demo CAPTCHA on DemoQA or SauceDemo (SauceDemo has none at all; DemoQA's is reCAPTCHA, not OCR-solvable — see above), so the honest way to confirm the wiring works end-to-end is a local static-HTML smoke check rather than trusting log lines against a real site:

1. Save this as `captcha-smoke-test.html` and open it in the browser your suite uses:
   ```html
   <!doctype html><html><body>
     <img id="captchaImg" alt="captcha"
          src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNTAiIGhlaWdodD0iNTAiPjxyZWN0IHdpZHRoPSIxNTAiIGhlaWdodD0iNTAiIGZpbGw9IndoaXRlIi8+PHRleHQgeD0iMTAiIHk9IjM1IiBmb250LXNpemU9IjMwIiBmb250LWZhbWlseT0ibW9ub3NwYWNlIj5BQjEyM0M8L3RleHQ+PC9zdmc+" />
     <input id="captchaInput" type="text" />
   </body></html>
   ```
   (that data URI renders the text `AB123C`)
2. Point a throwaway page object / a quick `driver.get("file://" + path)` at it, or add a one-off `@Test` under a scratch package that calls `CaptchaSolver.detectCaptchaImage(driver)` / `.autoSolveIfPresent(driver)` directly.
3. Confirm the log shows the OCR attempt and that `#captchaInput` gets populated with something close to `AB123C` (OCR on a clean synthetic image like this should be exact; real distorted CAPTCHAs will be noisier — see [Design Notes](#-design-notes--extending-it)).

If step 3 doesn't produce output, work through [Troubleshooting](#-troubleshooting) below before assuming the wiring itself is broken — the far more common cause is a missing/misconfigured native Tesseract install, not a bug in `CaptchaSolver`.

---

## 🩹 Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `ConfigException: Could not locate a tessdata directory` | `tesseract-ocr` isn't installed on the machine actually running `mvn test` | Install it — see [Setup](#️-setup). Confirm with `tesseract --version` first, before touching any Java config. |
| Same error, only in CI, only on one platform | That specific pipeline's tesseract-install step didn't run (wrong `if:` condition, cache issue, or a runner image change) | Check the CI log for the tesseract setup step's output; re-run with `tesseract --version` added as a debug line if needed |
| `KeywordExecutionException: Unhandled keyword: SOLVE_TEXT_CAPTCHA` | You're running against an older build before `KeywordEngine` had cases for the three CAPTCHA keywords | Update to a build that includes this fix (already the case as of this doc) |
| Auto-detect never fires even though the page clearly has a CAPTCHA | `captcha.autoDetect.enabled=false` is set somewhere, or the image/input's `id`/`class`/`name`/`alt`/`src` genuinely doesn't contain "captcha" anywhere | Check effective config; if the markup just doesn't say "captcha", switch to [Mode 2](#-mode-2--explicit-keyword-driven) and name the locators explicitly instead of relying on pattern-matching |
| `solveTextCaptcha` runs but the answer is wrong / garbled | OCR accuracy on a distorted/noisy real-world CAPTCHA image | First check the logs for whether the segmented (per-character) or whole-string path answered — see [OCR Accuracy Pipeline](#-ocr-accuracy-pipeline-letterdigit-confusion). Tune `captcha.segmentation.minChars`/`maxChars` to the site's actual CAPTCHA length, adjust `minComponentAreaRatio`/`confidenceFloor` for that font's noise level, or (for a genuinely adversarial CAPTCHA) treat it the same as reCAPTCHA — not something OCR alone should be expected to beat reliably |
| One specific character is consistently misread as its look-alike (`0`/`O`, `5`/`S`, `8`/`B`, etc.) | Segmentation's shape tiebreaker doesn't have a rule for that specific pair/font, or whole-string OCR is being used instead of segmentation for that image | Confirm segmentation is actually running for that CAPTCHA (`captcha.segmentation.enabled=true`, and the component count needs to fall within `minChars`/`maxChars`); enable `captcha.ai.enabled=true` for that site as the more robust option if the font is unusually distorted |
| `SOLVE_CAPTCHA_WITH_AI` behaves identically to `SOLVE_TEXT_CAPTCHA` | Expected — it's a documented fallback stub. No Vision API key is wired in by default. | See [Design Notes](#-design-notes--extending-it) to wire a real Vision API |
| Answer contains real page text (e.g. literally the word "Captcha", a label, or other UI copy) rather than CAPTCHA-looking characters | The `<img>` was screenshotted before it actually finished loading — the browser was still showing its broken-image placeholder (icon + alt text) at capture time, and that alt text is what got OCR'd/vision-read. `document.readyState=='complete'`/`WAIT_FOR_PAGE_LOAD` passing does NOT guarantee an async-rendered CAPTCHA image has loaded yet. | Nothing to configure by default — `screenshotElementWithMargin()` now waits for `img.complete && naturalWidth > 0` before capturing and throws a clear error instead of silently capturing a broken image. If it's still happening, raise `captcha.image.load.timeout` (default `10`s) for a CAPTCHA that's just slow to render, or check the network/CDN if it never loads at all. |
| A CAPTCHA-image `<img>` element is found, but nothing gets typed anywhere | No element within the built-in `input[id/name/placeholder/aria-label *= "captcha"]` patterns exists nearby | Mode 1 logs a warning and stops rather than guessing at the wrong field; switch to Mode 2 for that page and name the exact input key |
| CAPTCHA-solving somehow affects an unrelated test's timing/flakiness | Every `navigateTo(...)` now runs a few extra `findElements()` calls | This is a handful of cheap, local DOM queries, not network calls — if it's genuinely a measurable slowdown for a specific suite, disable via `captcha.autoDetect.enabled=false` for that run |

See also the general [🩹 Troubleshooting & Glossary](troubleshooting.md) for errors not specific to CAPTCHA handling.

---

## 📐 Design Notes / Extending It

- **Why two modes instead of one?** Auto-detect (Mode 1) optimizes for *never having to write CAPTCHA-specific test code* — it's a safety net that quietly does nothing on the ~95% of pages without a CAPTCHA. Explicit keywords (Mode 2) optimize for *determinism* — when you know a specific test's CAPTCHA locator and want a hard failure if it ever breaks, rather than a silent skip.
- **Why not attempt reCAPTCHA/hCaptcha at all, even partially?** Those aren't OCR problems — they're either invisible risk-scoring challenges or image-classification challenges served by Google/hCaptcha's own infrastructure, with anti-automation detection built in. Attempting them wastes a screenshot/OCR cycle for a guaranteed failure and risks tripping bot-detection harder than just not trying. Treat a page blocked by one as a test scenario to assert on (`isBlockedByRecaptcha()`-style), not something to defeat.
- **Wiring in a real Vision API for `SOLVE_CAPTCHA_WITH_AI`:** `CaptchaSolver.solveWithAI()` already screenshots the element and base64-encodes it — the `TODO` block in that method is exactly where an OpenAI/Claude Vision call goes. Keep the same `(WebDriver, WebElement, WebElement) -> String` signature so `KeywordEngine`'s existing wiring doesn't need to change, and keep the OCR fallback in place for when no API key is configured, so the keyword never hard-fails a suite that isn't expecting a Vision API dependency.
- **Extending auto-detect's pattern list:** `CAPTCHA_IMAGE_LOCATORS` / `CAPTCHA_INPUT_LOCATORS` / `UNSOLVABLE_CAPTCHA_LOCATORS` in `CaptchaSolver.java` are plain `List<By>` constants — add a `By.cssSelector(...)` entry for a site-specific naming convention you keep running into, and every Page Object/keyword test benefits immediately without any other change.
