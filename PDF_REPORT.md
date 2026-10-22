# PDF Report

A single, self-contained PDF summary of a test run: a plain-English verdict, donut chart, counts, key findings
(most common failure causes, slowest tests), per-class bars,
environment, results by class, **every failure** (message, condensed stack trace, screenshot), skipped tests
with reasons, and the full results table. Page numbers on every page, bookmarks for each section.

No extra library: the writer is plain Java (`com.automation.core.report.pdf`), so there is nothing new for the
license scan, the Docker image or an offline CI runner.

## Turn it on

OFF by default. Any one of:

```bash
mvn test -Dsite=demoqa -Dreport.pdf.enabled=true
export REPORT_PDF_ENABLED=true            # CI/CD variable
# or set report.pdf.enabled=true in src/test/resources/config/global.properties
```

Output: `target/pdf-reports/<site>-<browser>-<suite>-report.pdf` (one per suite run).

## Settings (`global.properties`, all overridable with `-D` or env, e.g. `REPORT_PDF_ENABLED`)

| Key | Default | Meaning |
|---|---|---|
| `report.pdf.enabled` | blank (= off) | master switch |
| `report.pdf.output.dir` | `target/pdf-reports` | where PDFs go |
| `report.pdf.title` | `Test Execution Report` | header title |
| `report.pdf.screenshots` | `true` | embed failure screenshots |
| `report.pdf.screenshots.max` | `25` | cap on embedded screenshots |
| `report.pdf.screenshots.maxWidthPx` | `1000` | screenshots are downscaled to this width (JPEG) |
| `report.pdf.stacktrace.lines` | `10` | lines per failure; `0` hides the trace |
| `report.pdf.include.passed` | `true` | `false` = table lists only failed/skipped |
| `report.pdf.failures.max` | `50` | detailed failure blocks; the rest stay in the table |
| `email.attach.pdf` | `true` | attach the PDF to the e-mail report |

## E-mail

With `email.enabled=true` the PDF written during the run is attached automatically (before the zip, and only
files from *this* run). See `docs/EMAIL_AND_LICENSE.md`.

## Consolidated PDF (CI)

Each test JVM writes its own PDF (with screenshots). To get ONE PDF for a whole pipeline from all
`testng-results.xml` files (no screenshots, the XML has none):

```bash
mvn -q process-test-resources compile exec:java@pdf-report -Ppdf-report
# optional: -Dreport.pdf.results.dir=target -Dreport.pdf.output.file=out/all.pdf -Dsite=demoqa -Dbrowser=chrome
```

GitLab: set the CI/CD variable `REPORT_PDF_ENABLED=true`. Test jobs then archive `target/pdf-reports/`, and the
`report` job publishes them to `public/pdf-report/` (incl. `consolidated-report.pdf`) on GitLab Pages.
Jenkins/GitHub pipelines are not changed; run the commands above in a final step if you want it there.

## Notes and limits

- Text is WinAnsi (Latin, curly quotes, dashes, euro). Other scripts (e.g. Devanagari) and emoji print as `?`.
- Messages and stack traces go through the same secret masking as the e-mail report; data-driven parameters are
  never printed (repeated names get `[#n]`).
- Screenshots are kept in memory only while the suite runs (max 100 / 64 MB), then released.
- Failure screenshots come from `TestListener`'s existing capture, so a failure without a live driver has none.
