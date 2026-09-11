# 🤖 AI Features

Three opt-in features share one lightweight text-LLM client so the framework can lean
on a local (or remote) model for the reasoning that's hardest to hand-code: recovering
a broken locator when neither DOM attributes nor visual similarity have anything left
to match on, explaining *why* a test actually failed, and skimming a crawled page for
the kind of defect only a reader would notice.

All three are **off by default**, **report-only or index-only** (nothing here auto-edits
source, auto-retries a test, or lets a model invent a locator it wasn't given), and share
one client and one config namespace so you configure the model once.

Implementation: `src/main/java/com/automation/core/ai/` (client + exception analyzer),
`core/selfhealing/AiLocatorHealer.java` (self-healing's stage 3), `core/crawler/`
(the bug crawler).

This is a separate AI integration from the CAPTCHA-solving AI Vision call documented in
[CAPTCHA_SOLVER.md](CAPTCHA_SOLVER.md) — that one sends an *image* to a vision model and
is CaptchaSolver-specific (`captcha.ai.*` config); everything on this page is text-only
and shares its own `ai.*` config namespace. You'll typically want a different model for
each job.

## Quick start

```properties
# config/global.properties (or -D overrides)
ai.provider=ollama
ai.endpoint=http://localhost:11434/api/chat
ai.model=qwen2.5-coder:7b
```

```bash
ollama pull qwen2.5-coder:7b   # or whatever you set ai.model to — nothing here auto-pulls
```

Then turn on whichever feature(s) you want — each has its own flag, see below.

## Choosing a model

`ai.model` is always read from config — nothing in this framework hardcodes one. For
`ai.provider=ollama` (the default, no API key needed), a coding-tuned model in the
**Qwen-Coder** family is currently the strongest generally-available free/local pick for
the reasoning these three features need: picking the right element out of a candidate
list, root-causing a stack trace against page evidence, and spotting content-level bugs.
Roughly, by hardware:

| Hardware budget | Reasonable pick |
|---|---|
| A GPU/box that can comfortably run a large local model | A larger Qwen3-Coder tag |
| A modest GPU or CPU-only box with decent RAM | `qwen2.5-coder:7b` (this repo's default) or `qwen3:8b` |
| Very constrained | A smaller general Qwen3/Llama tag — expect lower-quality index picks and root-causing, so keep confidence thresholds conservative |

If you'd rather use a hosted model, set `ai.provider=anthropic` and `ai.apiKey` (or the
`ANTHROPIC_API_KEY` environment variable) — same three features, same config keys
otherwise. Model availability and naming changes over time; check what's current for
whichever provider you pick rather than trusting a specific tag baked into an old doc.

## 1. AI-assisted self-healing (stage 3)

`SelfHealingEngine` already has two healing stages: DOM attribute/text similarity, then
(opt-in) a visual screenshot-hash fallback. `self-healing.ai.enabled=true` adds a third,
tried only when both of those come up short:

- `AiLocatorHealer` builds a small pool of live, already-resolved candidate elements
  (the same near-miss/broad-scan pool stage 2 would have screenshotted) and describes
  each one — tag, id, class, role, aria-label, visible text — to the model, alongside
  the same description of the baseline element that broke.
- The model replies with a **candidate index** (or `-1` for "no match") plus a
  self-reported confidence. It is never asked for a CSS selector or XPath — an
  out-of-range or hallucinated answer simply can't point anywhere but a real element
  Selenium already resolved, which is the whole safety property of this stage.
- The pick is only used if confidence clears `self-healing.ai.confidence` (default
  `0.6`) **and** the element is still displayed/enabled at the moment of return
  (candidates can go stale between the scan and the AI round trip completing).

```properties
self-healing.ai.enabled=true
self-healing.ai.confidence=0.6
```

Healed elements show up in `target/self-healing/healing-report.json` with
`"method": "ai"` same as `"dom"`/`"visual"`, so a run that leaned on this stage is
still visible, not silent.

## 2. AI root-cause analysis on test failure

`ai.exceptionAnalysis.enabled=true` wires `AiExceptionAnalyzer` into
`TestListener.attachFailureDiagnostics()` — the same method that already captures a
screenshot, page source, and browser console logs on every failed/skipped test.

On top of that existing evidence, it asks the model to classify the failure (e.g.
locator-drift, timing/flakiness, application-bug, data-mismatch) and suggest a concrete
next step, then attaches the answer as one more Allure text attachment:
"AI Root-Cause Analysis — <test method name>". It never changes the test's actual
pass/fail outcome, never retries, and never touches source.

```properties
ai.exceptionAnalysis.enabled=true
```

Off by default because every enabled failure/skip costs one network round trip to
`ai.endpoint` — a checkout with no AI server reachable shouldn't have its failure path
slowed down (or log-spammed) by a feature it never opted into.

## 3. AI bug crawler

`core/crawler/SiteCrawler.java`, run via `CrawlerCli` (or the `bug-crawler` Maven
profile), does a breadth-first crawl of a site (same host as the start URL only) and
runs a set of checks on every page it visits:

- broken links/images — an HTTP `HEAD` (falling back to `GET` if the server rejects
  `HEAD`) per discovered link, **not** a full browser page load per link
- browser console errors (`SEVERE`-level entries)
- duplicate element `id`s
- empty/missing `href` and `alt` attributes
- mixed content (`http://` resources on an `https://` page)
- accessibility violations — reusing the same `AxeBuilder` axe-core wrapper
  `AccessibilityUtils` already uses elsewhere in this framework, called directly here
  so a violation is just another issue in the report, never a build failure on its own

Optionally, `crawler.ai.enabled=true` adds one more pass per page: `AiPageReviewer`
sends the trimmed rendered HTML to the model and asks it to flag anything only a reader
would notice — leftover placeholder/lorem-ipsum text, a visible stack trace or error
dump rendered into the page, obviously mismatched labels — explicitly told *not* to
repeat anything the rule-based checks above already cover.

A second, independent AI pass covers a different case: you already know (or suspect)
specific bugs and want every crawled page checked against *your* list rather than an
open-ended review. Point `crawler.checklistFile` at a plain text file, one bug/behavior
per line (blank lines and `#` comments are skipped):

```
the footer copyright year is stuck on 2023
the cart badge doesn't update after a quick-add
price shown on the product card doesn't match the price on its detail page
```

`AiChecklistReviewer` sends this list plus the trimmed page HTML to the model once per
page and asks it to judge each item as `present` (found, with evidence quoted from the
HTML), `absent`, or `uncertain` — only `present` results are added to the report, so a
checklist covering behavior static HTML can't show (e.g. "the button doesn't respond to
a second click") will just come back `uncertain` on every page rather than a false
positive or a wall of negative results. Setting `crawler.checklistFile` is itself the
opt-in — it does not require `crawler.ai.enabled=true` as well, and both passes can run
together or independently (they share a single `driver.getPageSource()` fetch per page
when both are on).

```bash
mvn -q exec:java@bug-crawler -Pbug-crawler -Dcrawler.startUrl=https://example.com
mvn -q exec:java@bug-crawler -Pbug-crawler -Dcrawler.startUrl=https://example.com \
    -Dcrawler.maxPages=20 -Dcrawler.ai.enabled=true -Dsite=demoqa
mvn -q exec:java@bug-crawler -Pbug-crawler -Dcrawler.startUrl=https://example.com \
    -Dcrawler.checklistFile=my-bug-checklist.txt -Dsite=demoqa
```

Produces `target/crawler/crawl-report.json` (machine-readable, one entry per visited
page with its issues) and `target/crawler/crawl-report.txt` (human-readable summary).
Checklist findings appear in both with category `checklist`, alongside `ai-review` (from
`crawler.ai.enabled`) and the rule-based categories (`broken-link`, `console`,
`duplicate-id`, etc.).

```properties
crawler.maxPages=50
crawler.maxDepth=3
crawler.outputDir=target/crawler
crawler.a11y.enabled=true
crawler.ai.enabled=false
crawler.checklistFile=
```

`-Dsite=<site>` (defaults to `demoqa`, same as the rest of the framework) still picks
which `browser`/`headless`/Grid config the crawler's own `WebDriver` uses — the crawler
itself will happily crawl any host you point `crawler.startUrl` at, regardless of which
site's config supplied the browser settings.

### 3a. Visual AI review (screenshots, not just HTML)

All three passes above only ever look at markup — `AiPageReviewer` and
`AiChecklistReviewer` read HTML text, and the rule-based checks read DOM attributes. None
of them can see how a page actually *renders*: overlapping text, an element pushed off
the visible viewport, a broken CSS layout, or a blank area where content should be can
all look completely fine in the HTML while being visibly wrong on screen.

`crawler.ai.vision.enabled=true` adds a fourth, independent pass: `AiScreenshotReviewer`
takes a full-viewport screenshot of the page and sends it to a vision-capable model
(`AiVisionClient`, its own `ai.vision.*` config namespace — separate from both the
text-only `ai.*` model above and `captcha.ai.*`, since you'll usually want a different
model for "read this image of a CAPTCHA" vs. "read this HTML" vs. "look at this whole
page and tell me what looks broken"). Findings are added to the report under the
`ai-visual-review` category.

```properties
crawler.ai.vision.enabled=false
ai.vision.provider=anthropic
ai.vision.endpoint=
ai.vision.apiKey=
ai.vision.model=
ai.vision.timeout.seconds=60
```

`ai.vision.provider=anthropic` needs a real Claude model in `ai.vision.model` (e.g. a
current Claude model tag) and `ai.vision.apiKey`/`ANTHROPIC_API_KEY`.
`ai.vision.provider=ollama` works with a local vision-capable model (llava, bakllava,
moondream, etc.) — no API key needed, same as the text `ai.*` client.

## 4. Mobile app AI bug crawler

`mobile/crawler/MobileAppCrawler.java`, run via `MobileCrawlerCli` (or the
`mobile-bug-crawler` Maven profile), is the mobile counterpart to the web bug crawler
above — same idea (visit screens, run checks, optionally ask an LLM what looks wrong),
adapted to Appium: there's no URL graph to follow, so it explores depth-first by tapping
clickable elements and navigating back, rather than breadth-first by following `href`s.

Checks run on every screen:

- crash/ANR dialogs (`has stopped`, `isn't responding`, etc. — matched in the UI-hierarchy
  dump)
- leaving the app under test entirely (Android only, via `getCurrentPackage()`)
- duplicate `resource-id`/accessibility-id values on the same screen
- image/icon controls with no accessible name (`content-desc` on Android, `name`/`label`
  on iOS) and no text — the mobile equivalent of the web crawler's missing-`alt` check
- screens with no visible text/content-desc/label at all (likely a blank/broken render)

Optionally, `crawler.mobile.ai.enabled=true` adds `AiMobileScreenReviewer` (text pass over
the screen's UI-hierarchy XML, same shape as `AiPageReviewer`), and
`crawler.mobile.ai.vision.enabled=true` adds the same `AiScreenshotReviewer` visual pass
the web crawler uses, pointed at a screenshot of the current screen instead of a
webpage.

**Read this before pointing it at a real device/account.** Unlike the web crawler (which
only ever makes read-only HTTP `HEAD`/`GET` requests to check links), this crawler
physically taps buttons/links/cells in a real app to discover new screens — which means
it can trigger real actions (submitting a form, placing an order, logging out, deleting
data) if the app exposes them as a reachable tap. `crawler.mobile.avoidTextContains` is a
comma-separated, case-insensitive denylist checked against each candidate element's
text/content-desc/name/label/resource-id before it is ever tapped — the default covers
common destructive/irreversible actions (`delete`, `logout`, `uninstall`, `pay`,
`purchase`, `reset`, `submit`, `confirm`, etc.), but you should extend it with anything
app-specific before a real run. `crawler.mobile.maxScreens` /
`crawler.mobile.maxDepth` / `crawler.mobile.maxElementsPerScreen` also bound how much
exploration happens. This is a best-effort safety net, not a guarantee.

```bash
mvn -q exec:java@mobile-bug-crawler -Pmobile-bug-crawler -Dsite=<your-mobile-site>
mvn -q exec:java@mobile-bug-crawler -Pmobile-bug-crawler \
    -Dcrawler.mobile.maxScreens=15 -Dcrawler.mobile.ai.enabled=true
```

Produces `target/mobile-crawler/crawl-report.json` and `crawl-report.txt` — same report
format as the web crawler (they share `CrawlReport`/`CrawlReportWriter`), just with
screen signatures instead of URLs and no HTTP status per entry.

```properties
crawler.mobile.maxScreens=30
crawler.mobile.maxDepth=4
crawler.mobile.maxElementsPerScreen=8
crawler.mobile.tapSettleMillis=800
crawler.mobile.outputDir=target/mobile-crawler
crawler.mobile.avoidTextContains=delete,logout,log out,sign out,uninstall,pay,purchase,buy,remove account,reset,submit,confirm
crawler.mobile.ai.enabled=false
crawler.mobile.ai.vision.enabled=false
```

## Full config reference

| Key | Default | Meaning |
|---|---|---|
| `ai.provider` | `ollama` | `ollama` or `anthropic` |
| `ai.endpoint` | `http://localhost:11434/api/chat` (ollama) / `https://api.anthropic.com/v1/messages` (anthropic) | Override for a remote/LAN Ollama box or a proxy |
| `ai.apiKey` | *(blank)* | Only required for `ai.provider=anthropic`; falls back to `ANTHROPIC_API_KEY`. For ollama, only sent as a Bearer token if you set one (e.g. a reverse proxy) |
| `ai.model` | *(none — must be set)* | Any tag your provider can serve |
| `ai.timeout.seconds` | `60` | Per-call HTTP timeout |
| `self-healing.ai.enabled` | `false` | Stage-3 AI self-healing |
| `self-healing.ai.confidence` | `0.6` | Minimum model confidence (0.0–1.0) to accept its pick |
| `ai.exceptionAnalysis.enabled` | `false` | AI root-cause analysis on failure/skip |
| `crawler.maxPages` | `50` | Bug-crawler page cap |
| `crawler.maxDepth` | `3` | Bug-crawler link-depth cap |
| `crawler.outputDir` | `target/crawler` | Where the JSON/text report is written |
| `crawler.a11y.enabled` | `true` | Run the axe-core accessibility check per page |
| `crawler.ai.enabled` | `false` | Optional per-page AI content review |
| `crawler.checklistFile` | *(blank)* | Path to a one-item-per-line bug checklist; every crawled page is checked against it (see above) |
| `crawler.ai.vision.enabled` | `false` | Optional per-page visual AI review (screenshot, not HTML) |
| `ai.vision.provider` | `anthropic` | `anthropic` or `ollama` — separate model/provider from `ai.*` and `captcha.ai.*` |
| `ai.vision.model` | *(none — must be set)* | Any vision-capable tag your `ai.vision.provider` can serve |
| `ai.vision.apiKey` | *(blank)* | Only required for `ai.vision.provider=anthropic`; falls back to `ANTHROPIC_API_KEY` |
| `ai.vision.timeout.seconds` | `60` | Per-call HTTP timeout |
| `crawler.mobile.maxScreens` | `30` | Mobile bug-crawler screen cap |
| `crawler.mobile.maxDepth` | `4` | Mobile bug-crawler tap-depth cap |
| `crawler.mobile.maxElementsPerScreen` | `8` | Max candidate taps tried per screen |
| `crawler.mobile.tapSettleMillis` | `800` | Wait after a tap before inspecting the resulting screen |
| `crawler.mobile.outputDir` | `target/mobile-crawler` | Where the mobile crawler's JSON/text report is written |
| `crawler.mobile.avoidTextContains` | *(destructive-action defaults — see above)* | Elements matching any of these substrings are never tapped |
| `crawler.mobile.ai.enabled` | `false` | Optional per-screen AI content review (UI-hierarchy XML) |
| `crawler.mobile.ai.vision.enabled` | `false` | Optional per-screen visual AI review (screenshot) |

## Why these features, and not more

"AI that does coding" inside a live test-automation framework is easy to describe and
genuinely risky to build carelessly — an agent that edits source files or regenerates
tests on its own would need its own review/rollback story before it belongs in a CI
pipeline. The features above intentionally stop short of that: they inform
(self-healing's index-only pick, the failure root-cause note, the crawlers' findings)
rather than act, so the review step stays with a human, same as everything else this
framework's reports already surface. The mobile crawler is the one exception worth
calling out explicitly — it does *act*, in the narrow sense of tapping real UI elements
to explore — which is exactly why it ships with an opt-out-style denylist and hard
exploration caps instead of an unrestricted "click everything" mode.
