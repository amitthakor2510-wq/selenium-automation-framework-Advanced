# E-mail report & dependency license compliance

Two opt-in features. Both are **off by default** - a fresh checkout behaves exactly as before.

| Feature | What it does | Switch |
|---|---|---|
| **E-mail report** | After a run, mails the pass/fail/skip counts, the failed tests, a link to the CI run, the license summary, and the reports as attachments | `email.enabled=true` |
| **License compliance scan** | Reads each dependency's declared license (offline, from the local Maven repo) and checks it against `license-policy.properties` | `license.scan.enabled=true` (in a test run) or `-Plicense-check` (as a build gate) |

Code: `core/mail/*`, `core/license/*`, listener `sites/listeners/PostRunReportListener`
(registered in `META-INF/services/org.testng.ITestNGListener`, so it covers every suite with no suite-XML edits).

---

## 1. E-mail report

### Try it safely first (nothing is sent)

```bash
mvn test -Dsuite=... -Demail.enabled=true -Demail.dryRun=true \
         -Demail.from=qa@example.com -Demail.to=team@example.com
```

A `target/email/report-<timestamp>.eml` file is written - open it in any mail client to see exactly what would be sent.

### Real sending

Set the keys in `src/test/resources/config/global.properties` (section *E-MAIL REPORT*), **except the password**:

```bash
export EMAIL_SMTP_PASSWORD='your-app-password'   # Gmail: create an App Password; never your login password
mvn test -Demail.enabled=true -Demail.smtp.host=smtp.gmail.com -Demail.smtp.username=you@gmail.com \
         -Demail.from=you@gmail.com -Demail.to=team@example.com
```

Every `email.*` key can also be an environment variable named after it: `email.smtp.host` -> `EMAIL_SMTP_HOST`,
`email.to` -> `EMAIL_TO`, `email.sendOn` -> `EMAIL_SENDON`. Lookup order: `-D` > properties files > environment.

| Key | Default | Meaning |
|---|---|---|
| `email.enabled` | `false` | master switch |
| `email.dryRun` | `false` | write an `.eml` instead of sending |
| `email.smtp.host` / `.port` | - / `587` | server |
| `email.smtp.security` | `starttls` | `starttls` (587) \| `ssl` (465) \| `none` (loopback/unauthenticated relay only) |
| `email.smtp.username` | - | omit for an unauthenticated relay |
| `email.smtp.password` | - | **env var only** (`EMAIL_SMTP_PASSWORD`) |
| `email.from` / `email.to` / `.cc` / `.bcc` | - | comma or `;` separated lists |
| `email.subject` | `[{status}] {site} {suite} - {passed}/{total} passed` | placeholders: `{status} {site} {browser} {suite} {total} {passed} {failed} {skipped}` |
| `email.sendOn` | `always` | `always` \| `failure` (a failed test **or** a license violation) \| `never` |
| `email.attach.reports` | `true` | zip (`report.zip.enabled=true`) if present, else each Extent `index.html` |
| `email.attach.licenseReport` | `true` | attach `license-report.html` |
| `email.attach.maxTotalMb` | `10` | anything over the cap is left out and the mail says so |
| `email.timeoutMs` | `20000` | connect/read/write timeout - a dead SMTP server never hangs a run |

### Safety properties (all covered by unit tests)

- Password: environment only, masked in logs, never in error messages. Credentials over an unencrypted connection to a non-loopback host are **refused**.
- STARTTLS is *required* (not merely offered) and the server certificate host name is verified.
- Test names, failure messages, subject and attachment names can't inject mail headers or HTML; `password=...`, `token=...`, `Bearer ...` in failure messages are masked.
- Only report files modified **since this run started** are attached - a stale report from an earlier run is never mailed as if it were new.
- The in-run listener never changes a test result or fails the build because of e-mail.

### One mail per pipeline, not one per JVM

The listener sends one mail per suite per JVM. A pipeline with many site/browser legs should therefore **not** set
`email.enabled=true` globally; use the consolidated command as a final step instead. It reads the per-leg
`target/gate/*.txt` files (or, failing that, every `testng-results.xml`), so it reports **every** leg:

```bash
mvn -q test-compile exec:java@email-report -Pemail-report -Demail.enabled=true
```

Already wired (opt-in):

| CI | Enable with | Settings |
|---|---|---|
| GitHub Actions - job `email-report` | repo **Variable** `EMAIL_REPORT_ENABLED=true` | secrets `EMAIL_SMTP_HOST`, `EMAIL_SMTP_USERNAME`, `EMAIL_SMTP_PASSWORD`, `EMAIL_FROM`, `EMAIL_TO`; variables `EMAIL_SMTP_PORT`, `EMAIL_SMTP_SECURITY`, `EMAIL_SEND_ON` |
| GitLab CI - job `email-report` | CI/CD variable `EMAIL_REPORT_ENABLED=true` | variables `EMAIL_SMTP_HOST`, `EMAIL_SMTP_USERNAME`, `EMAIL_SMTP_PASSWORD` (**Masked**), `EMAIL_FROM`, `EMAIL_TO`, optional `EMAIL_SMTP_PORT`, `EMAIL_SMTP_SECURITY`, `EMAIL_SENDON` |
| Jenkins - stage `Email Report` | env var `EMAIL_REPORT_ENABLED=true` | same names as environment variables; bind `EMAIL_SMTP_PASSWORD` from a Jenkins credential |

---

## 2. License compliance scan

```bash
mvn -q test-compile exec:java@license-scan -Plicense-check
```

Writes `target/license-report/` (`license-report.html`, `.json`, `.txt`, `license-summary.properties`) and exits non-zero
if the policy says a finding must fail. `test-compile` comes first so every dependency (and its POM) is in `~/.m2`.

Or inside a normal test run: `-Dlicense.scan.enabled=true` (the result is logged, written to the same folder and summarised in the e-mail).

### How a verdict is reached

Each jar's license text (`"The Apache Software License, Version 2.0"`) is normalised to an SPDX-style id (`Apache-2.0`).
Maven treats several `<license>` entries as **alternatives**, so:

| Status | Meaning | Default effect |
|---|---|---|
| `ALLOWED` | any declared license is in `license.allowed` | - |
| `DENIED` | **all** declared licenses are in `license.denied` | **FAIL** |
| `REVIEW` | a license exists but is on neither list (or unrecognised text) | warn |
| `MISSING` | no license information anywhere | warn |

Edit **`license-policy.properties`** (repo root) to change the lists, to make `REVIEW`/`MISSING` fail
(`license.review.action=FAIL`), to `license.ignore` your own artifacts, or to correct a wrong declaration with
`license.overrides=group:artifact=MIT`. Keep a comment next to every override saying why.

### Where the license comes from (all offline)

1. the `.pom` next to the jar in the Maven repository - and, when it has no license, its **parent POM chain** (very often where the license is declared);
2. otherwise the `pom.xml` embedded in the jar;
3. otherwise `MISSING`.

POMs are parsed with DOCTYPEs/external entities disabled.

### Known limits

- It trusts what the POM *declares*; it does not inspect source headers or LICENSE files inside jars.
- A jar outside a Maven repository layout whose embedded POM relies on a parent POM can only be reported as `MISSING` (the parent can't be found) - add a `license.overrides` entry once you've checked it.
- Not a legal review. `REVIEW`/`MISSING` rows exist precisely so a person decides.

Already wired: GitHub Actions job `license-scan` and GitLab job `license-scan` (red when a denied license is found), Jenkins stage `License Scan` (marks the build UNSTABLE).
