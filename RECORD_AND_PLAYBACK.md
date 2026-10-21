<div align="center">

# 🎬 Record & Playback
### Turn a browser session into a keyword-driven test

</div>

---

Record a session once; get a keyword script, object-repository locators (with fallbacks), a runner
and a suite — then the tool replays the new test in a fresh browser to prove it works. Playback is
the existing [keyword engine](KEYWORD_DRIVEN_TESTING.md), so recorded and hand-written tests share
self-healing, retries, reporting and CI.

## Quick start

```bash
Scripts/record.sh saucedemo TC_Login /          # <site> <TestCaseName> [start path or URL] [options]
# same thing without the script (works on Windows too):
mvn -q test-compile exec:java@recorder -Precorder -Dsite=saucedemo -Dheadless=false -Dexec.args="--name TC_Login --url /"
```

A browser opens on the site. Use it normally. A small **REC** toolbar sits top right:

| Button | Does |
|---|---|
| **Verify text** | The next element you click is recorded as a check on its text (or value, for fields) instead of a click |
| **Verify visible** | The next click records an "is displayed" check |
| **Hover: ON/off** | While ON, resting the mouse on an element records a `HOVER` (for menus that open on hover) |
| **Stop** | Finish. Pressing **ENTER** in the terminal does the same |

On stop it writes the files, then replays `TC_Login` in a new browser and prints `REPLAY PASSED` or
the failing step. Exit code: `0` ok, `1` recorded but replay failed, `2` nothing recorded / bad arguments.

Options: `--name` (required) · `--url` (path or full URL, default the site root) · `--script` /
`--repo` (override the output files) · `--prefix` (key prefix, default the site name) · `--overwrite`
(replace an existing test case of that name) · `--no-replay` · `--no-auto-assert` (skip the automatic
URL check after page changes) · `--max-minutes` (default 30). The site comes from `-Dsite`.

## What it writes

| File | Content |
|---|---|
| `src/test/resources/testdata/keyword/<site>_recorded_keywords.csv` | The new test case as keyword rows (other cases are never touched) |
| `src/test/resources/objectrepository/<site>.properties` | New keys appended under a dated comment: `site.page.element=type:value` plus `.fallback.1/.2`. A key that already exists with the same locator is reused; a name clash gets a numeric suffix |
| `src/test/java/com/automation/sites/<site>/tests/Recorded<Site>KeywordTest.java` | Created **once** per site (never rewritten): runs every test case in the recorded script |
| `testng-suites/<site>-recorded.xml` | Created once: a suite for that class |
| `self-healing-data/locator-repository.json` | A self-healing baseline for each recorded locator (see below) |

## What is captured

| You do | Recorded as |
|---|---|
| Type in a field | `TYPE` (a `CLEAR` first if the field already had text; clearing it records `CLEAR`) |
| Click / double-click / right-click | `CLICK` / `DOUBLE_CLICK` / `RIGHT_CLICK` (the two clicks of a double click are merged) |
| Pick from a `<select>` | `SELECT_BY_TEXT` |
| Tick or untick a checkbox, choose a radio | `CHECK` / `UNCHECK`. A radio hidden behind a styled label is recorded as a `CLICK` on the label |
| Choose a file | `UPLOAD_FILE` pointing at `src/test/resources/testdata/uploads/<name>` — **copy the file there** (browsers do not reveal the original path) |
| Press Enter in a field / Escape | `PRESS_KEY` |
| Work inside a same-origin iframe | `SWITCH_TO_FRAME` / `SWITCH_TO_DEFAULT_CONTENT` around the steps |
| Open or close a window or tab | `SWITCH_TO_WINDOW NEW` / `CLOSE_WINDOW` |
| Dismiss a JavaScript alert | `ACCEPT_ALERT` |
| A click that loads a new page | `WAIT_FOR_PAGE_LOAD` and, unless `--no-auto-assert`, `VERIFY_URL_CONTAINS` |
| Type a new address / use Back | `NAVIGATE` |
| Toolbar **Verify text/visible**, **Hover** | `VERIFY_TEXT` / `VERIFY_ATTRIBUTE value` / `VERIFY_DISPLAYED`, `HOVER` |

Only real user input is recorded — events a page script fires on its own (for example a dependent
dropdown that sets another dropdown) are ignored, so they do not become phantom steps.

## How locators are chosen

Candidates are tried best-first and each must match **exactly that one element**: stable `id` →
`data-testid`/`data-test`/`data-qa`/`data-cy` → `name` → `aria-label`/`placeholder`/`title` → link text →
text XPath (only for buttons, links, labels, headings, list items — never for elements whose text is data)
→ stable class combination. Generated ids (`btn-83921-x`, `:r1:`, UUIDs, 4+ digit runs) are skipped. The
best becomes the primary and up to two more become `fallback.N`. A position-based locator is used only
when nothing else is unique, is **never** written as a fallback (it can quietly match the wrong element),
and produces a note telling you to add an id or `data-testid` to the page.

## Secrets

A password field (and any field whose key looks sensitive: password, token, otp, secret, cvv…) is
written as `${env:SITE_PAGE_ELEMENT}`, never as the value. The tool lists the variable names to set; the
replay right after recording uses the value you typed, in memory only.

```bash
export SAUCEDEMO_LOGIN_PASSWORD='...'                 # bash
$env:SAUCEDEMO_LOGIN_PASSWORD = '...'                 # PowerShell
mvn test -DSAUCEDEMO_LOGIN_PASSWORD=...               # or as a -D property
```

Other typed values (usernames, search text) are recorded literally. Review the CSV and replace what
should come from data with `${env:...}`, `${config:...}` or `${var:...}`.

## Running recorded tests

```bash
mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/saucedemo-recorded.xml
```

`KeywordScriptSuiteBase` reads the recorded script and runs each test case as its own TestNG test
(groups `keyword-driven`, `recorded`), so **a newly recorded test case runs with no Java**. To include
them in an existing suite, add the generated class or the `recorded` group to it.

## Self-healing baseline

Self-healing normally learns an element only after it has been found once. The recorder stores what it
saw (tag, id, name, classes, text, key attributes, parent) for every locator at record time, so even
the first replay can heal a locator that has drifted. Healing scores candidates on those attributes and
needs 0.55 to accept one; a bare `<input>` with no classes cannot reach that after its id is gone (only
name, type and parent remain), which is why recorded keys also carry **fallback locators** — they are
what rescues such fields. Fallback use is logged and appears in the self-healing report with stage
`fallback`.

## Limitations

- **Visible browser only.** Not headless, not on a Selenium Grid session without a display.
- **Same-origin iframes only.** Cross-origin iframes and shadow DOM are not captured. After a
  *cross-origin* navigation the last fraction of a second of input can be lost.
- **Not recorded automatically:** drag and drop (the `DRAG_AND_DROP` keyword exists — add the row by
  hand), date/time inputs (add the step by hand), native file/print/permission dialogs, `confirm()` /
  `prompt()` answers (every dialog is recorded as `ACCEPT_ALERT`; a prompt's text is not captured),
  switching between open tabs without opening or closing one, and what happens in a different window
  you did not open from the page.
- **Hover** is recorded only while the toolbar's Hover mode is ON.
- **Assertions** exist only where you add them with the toolbar (plus the automatic URL check). A
  recording with none passes as long as nothing errors.
- **Typed data is literal** except for sensitive fields; there is no automatic parameterisation.
- Recorded tests drive the page directly through the keyword engine — they do not use your page objects.
- Content that changes every load (ads, captchas, one-time codes) cannot be replayed; sites behind them
  need those steps handled by hand.

## Verification

- `StepBuilderTest`, `ScriptWriterTest`, `LocatorKeyNamerTest`, `RunnerScaffoldTest`,
  `FingerprintSeederTest`, `ObjectRepositoryFallbackTest`, `KeywordVariablesTest` — browser-free,
  part of the `unit-tests` profile.
- `RecorderBrowserIT` — a scripted user works a local fixture site (`src/test/resources/recorder-fixtures`)
  covering every row of the table above; the recording is written to disk, replayed in a second
  browser, then shown to self-heal on a drifted page (and to fail without its baseline). It opens no
  external site and keeps its own self-healing store under `target/`:

  ```bash
  mvn test -Dtest=RecorderBrowserIT -Dsite=saucedemo -Dheadless=true
  ```
