# Telegram report, test author/category and browser info

Three small reporting features, all opt-in or purely descriptive. None of them changes what a test does or
its result.

- [Telegram report](#telegram-report) - run summary + report files posted to a Telegram chat
- [`@FrameworkAnnotation`](#frameworkannotation-author-and-category) - author / category per test in the Extent report
- [`BrowserInfoUtils`](#browserinfoutils) - browser / OS labels for reports

---

## Telegram report

The Telegram twin of the [e-mail report](EMAIL_AND_LICENSE.md): after a suite finishes, the framework posts
the pass/fail/skip counts, the failed tests (secrets masked), a link to the CI run, and uploads the report
files - the PDF report(s), the report zip when `report.zip.enabled=true`, otherwise each Extent `index.html`.
Only files written during **this** run are sent. It uses the JDK's `HttpClient`: no new dependency.

> The CI `notify` job (`notify_chat.py`) already posts a Telegram message from the pipeline. This feature is
> the in-JVM version for local runs and for pipelines that do not use that job. Enable **one** of them for a
> given run, or the chat gets both.

### Set-up (once)

1. In Telegram, talk to **@BotFather**, send `/newbot`, copy the token.
2. Add the bot to your group or channel (a channel needs the bot as an admin that may post).
3. Send any message there, open `https://api.telegram.org/bot<TOKEN>/getUpdates`, copy `chat.id`
   (groups are negative, e.g. `-1001234567890`; a public channel can use `@channelname`).
4. Export the secrets - **never** put the token in a properties file:

```bash
export TELEGRAM_BOT_TOKEN=123456:ABC...
export TELEGRAM_CHAT_ID=-1001234567890
# optional: export TELEGRAM_THREAD_ID=7     (forum topic)
```

On Windows PowerShell: `$env:TELEGRAM_BOT_TOKEN="..."; $env:TELEGRAM_CHAT_ID="..."`.

### Use it

```bash
# 1. Safe first try - writes target/telegram/telegram-*.txt, sends nothing, needs no token
mvn test -Dtelegram.enabled=true -Dtelegram.dryRun=true

# 2. Real send at the end of a normal run
mvn test -Dtelegram.enabled=true

# 3. One consolidated message after several runs (CI "report" job)
mvn -q test-compile exec:java@telegram-report -Ptelegram-report -Dtelegram.enabled=true
```

Together with the PDF report: `-Dreport.pdf.enabled=true -Dtelegram.enabled=true` uploads the PDF as well.

### Settings (`global.properties`, `-D...`, or the matching environment variable)

| Key | Default | Meaning |
|---|---|---|
| `telegram.enabled` | `false` | Master switch |
| `telegram.dryRun` | `false` | Write a preview file, send nothing |
| `telegram.bot.token` | - | Use `TELEGRAM_BOT_TOKEN`. Never logged |
| `telegram.chat.id` | - | Use `TELEGRAM_CHAT_ID` |
| `telegram.thread.id` | - | Forum topic (`TELEGRAM_THREAD_ID`) |
| `telegram.sendOn` | `always` | `always` / `failure` / `never` |
| `telegram.silent` | `false` | Deliver without a notification sound |
| `telegram.attach.pdf` | `true` | Upload the PDF report(s) of this run |
| `telegram.attach.reports` | `true` | Upload the report zip, else the Extent `index.html` files |
| `telegram.attach.maxFileMb` | `45` | Per-file cap (the Bot API limit is 50 MB); bigger files are named in a note |
| `telegram.maxFailuresListed` | `10` | Failed tests listed in the message; the rest are counted |
| `telegram.timeoutMs` | `20000` | HTTP timeout |
| `telegram.failOnError` | `false` | `TelegramReportCli` only: exit 1 if sending fails |

### Safety

- The token is part of every Bot API URL, so it is scrubbed from every error message and no exception is chained.
- `telegram.apiBase` must be `https` (plain `http` is accepted for `localhost` only, for tests).
- Every dynamic value is HTML-escaped; the message is trimmed to Telegram's 4096-character limit.
- A Telegram problem is logged as a warning and never fails or changes a test run.
- A report file that fails to upload does not undo the summary that was already delivered; it is named in the log line.
- Not yet exercised against the real Telegram servers - send one run with `telegram.sendOn=always` the first time.

---

## `@FrameworkAnnotation` (author and category)

```java
import com.automation.core.annotations.FrameworkAnnotation;

@FrameworkAnnotation(author = "Amit", category = {"login", "smoke"})
@Test(groups = "smoke")
public void validLogin() { ... }
```

- The Extent report shows the values as the test's **Author** and **Category** (the Dashboard tab's filter
  chips and "tests by category" chart).
- Put it on a **class** to give every test in it a default; a method's own value overrides it, and a blank
  field falls back to the class's.
- Categories are **added** to the test's TestNG groups; they do not replace them. Without an author the
  report keeps showing the site name, exactly as before.
- It is purely descriptive: TestNG groups, quarantine tags and `test-config.properties` are untouched.

## `BrowserInfoUtils`

`com.automation.core.utils.BrowserInfoUtils` gives reports and tests the same labels everywhere:

| Method | Returns |
|---|---|
| `getBrowserInfo()` | configured browser, upper case (`CHROME`) |
| `getOSInfo()` | OS name + version |
| `getBrowserVersion(driver)` | version the driver started (`""` when unknown) |
| `deviceLabel()` | `CHROME / Windows 11` - used for each test's Extent *device* |
| `describe(driver)` | `Browser: CHROME 126.0 on Windows 11 10.0` - for a log line |
| `isWindows()` / `isMac()` / `isLinux()` | OS checks |

The Extent report's *System info* panel already listed Site, Browser, Suite, Headless, OS, Java and Retry, so
that part is unchanged; what is new is the per-test *device* now carrying the OS too.
