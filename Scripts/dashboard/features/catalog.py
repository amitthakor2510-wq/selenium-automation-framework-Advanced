"""
Catalog of every runnable framework capability, for the Control Center page.

Each feature maps to ONE fixed program + fixed leading arguments. The only variable parts are
"switches" (options) whose KEY is declared on the feature and whose VALUE is validated by a strict
type rule (bool / enum / int / url / ref / name). Nothing the browser sends is ever passed through
as a command, a shell fragment or a free-form argument; commands run as argv lists with shell=False
(see runner_service.py). Anything not declared here is rejected by service.build().

Feature shape:
  id, group, title, desc, prefix (argv; "{mvn}" = Maven launcher found on this machine),
  options [..], globals (accepts the shared run switches), artifacts [repo-relative dirs],
  timeout (minutes), needs [tool/service names], risk ("safe" | "system"),
  optional: suite (testng-suites/<file>), site_option (bool: user may pick a site), fixed_site.
"""
import re

BROWSER_VALUES = ["chrome", "firefox", "edge"]

_NAME = re.compile(r"^[A-Za-z0-9_-]{1,40}$")
_URL = re.compile(r"^https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%\[\]-]{1,2000}$")
_REF = re.compile(r"^[A-Za-z0-9._/~^@{}-]{1,80}$")
_SEG = re.compile(r"^[A-Za-z0-9_-]{1,40}$")
_REL_URL = re.compile(r"^/[A-Za-z0-9._~/%?&=#-]{0,200}$")

TYPES = {"bool", "enum", "int", "url", "ref", "name", "relurl"}


def opt(key, label, typ, *, kind="prop", default=None, values=None, lo=0, hi=999999, help="", flag=None, always=False):
    """kind: prop -> -Dkey=value | flag -> adds `flag` when true | arg -> `flag value`."""
    assert typ in TYPES and kind in ("prop", "flag", "arg")
    return {"key": key, "label": label, "type": typ, "kind": kind, "default": default, "values": values,
            "min": lo, "max": hi, "help": help, "flag": flag, "always": always}


# Shared run switches: shown once on the page, applied to every feature that has globals=True.
GLOBAL_OPTIONS = [
    opt("browser", "Browser", "enum", values=BROWSER_VALUES, default="chrome"),
    opt("headless", "Headless (no browser window)", "bool", default=False),
    opt("retry.count", "Retry failed tests (0-5)", "int", lo=0, hi=5, default=2),
    opt("human.pause.enabled", "Human-like pacing pauses", "bool", default=True),
    opt("video.enabled", "Record video", "bool", default=False),
    opt("video.keep.on.pass", "Keep video of passing tests", "bool", default=False),
    opt("ai.exceptionAnalysis.enabled", "AI root-cause analysis of failures", "bool", default=False),
    opt("report.pdf.enabled", "PDF report at end of run", "bool", default=False),
    opt("quarantine.enabled", "Honour flaky-test quarantine", "bool", default=True),
    opt("perf.budget.enabled", "Performance budgets", "bool", default=True),
    opt("self-healing.enabled", "Self-healing locators", "bool", default=True),
    opt("captcha.autoDetect.enabled", "CAPTCHA auto-detect", "bool", default=True),
    opt("grid.enabled", "Use Selenium Grid (docker)", "bool", default=False),
]
GLOBAL_KEYS = [o["key"] for o in GLOBAL_OPTIONS]

REPORT_DIRS = ["target/allure-results", "target/extent-reports", "target/surefire-reports"]
MVN_TEST = ["{mvn}", "-B", "-ntp", "test"]

PERF_OPTS = [
    opt("perf.threads", "Virtual users (threads)", "int", lo=1, hi=500, default=10),
    opt("perf.rampUpSeconds", "Ramp-up seconds", "int", lo=0, hi=3600, default=5),
    opt("perf.iterations", "Iterations", "int", lo=1, hi=10000, default=5),
    opt("perf.maxP99Millis", "Max p99 (ms)", "int", lo=1, hi=600000, default=5000),
    opt("perf.maxErrorRatePercent", "Max error rate % (whole number)", "int", lo=0, hi=100, default=1),
]
CRAWLER_OPTS = [
    opt("crawler.startUrl", "Start URL", "url", default="https://example.com"),
    opt("crawler.maxPages", "Max pages", "int", lo=1, hi=1000, default=50),
    opt("crawler.maxDepth", "Max depth", "int", lo=0, hi=20, default=3),
    opt("crawler.a11y.enabled", "Accessibility check on each page", "bool", default=True),
    opt("crawler.ai.enabled", "AI page review (needs Ollama / API key)", "bool", default=False),
]


def F(id, group, title, desc, prefix, **kw):
    d = {"id": id, "group": group, "title": title, "desc": desc, "prefix": prefix, "options": [],
         "globals": False, "artifacts": [], "timeout": 30, "needs": [], "risk": "safe"}
    d.update(kw)
    return d


# ── Static features (suites are generated separately from testng-suites/, see service.py) ──
STATIC = [
    # Quality gates & code health
    F("unit-tests", "Quality & security", "Unit tests (JUnit 5)",
      "Fast framework self-tests: readers, TIA, API spec engine, mail, reports… no browser needed.",
      ["{mvn}", "-B", "-ntp", "verify", "-Punit-tests"], artifacts=["target/failsafe-reports", "target/surefire-reports"], timeout=30),
    F("checkstyle", "Quality & security", "Checkstyle (code style)", "Style rules from checkstyle.xml.",
      ["{mvn}", "-B", "-ntp", "-q", "checkstyle:check"], timeout=10),
    F("javadoc", "Quality & security", "Javadoc", "Builds the framework API docs (target/site/apidocs).",
      ["{mvn}", "-B", "-ntp", "javadoc:javadoc", "-Djavadoc.failOnError=false"], artifacts=["target/site/apidocs"], timeout=15),
    F("owasp", "Quality & security", "OWASP dependency check (CVE scan)",
      "Scans dependencies for known vulnerabilities. Slow on first run (downloads the NVD data).",
      ["{mvn}", "-B", "-ntp", "verify", "-Psecurity", "-DskipTests"],
      options=[opt("failBuildOnCVSS", "Fail build at CVSS score (11 = report only)", "int", lo=0, hi=11, default=11)],
      artifacts=["target/dependency-check"], timeout=90, needs=["internet"]),
    F("sbom", "Quality & security", "SBOM (CycloneDX)", "Software bill of materials → target/bom.json / bom.xml.",
      ["{mvn}", "-B", "-ntp", "-Psbom", "-DskipTests", "verify"], timeout=15),
    F("mutation", "Quality & security", "Mutation testing (PIT)", "Checks how good the unit tests are. Slow.",
      ["{mvn}", "-B", "-ntp", "verify", "-Pmutation"], artifacts=["target/pit-reports"], timeout=120),
    F("license-scan", "Quality & security", "Dependency license scan",
      "Checks every dependency license against license-policy.properties.",
      ["{mvn}", "-B", "-ntp", "-q", "test-compile", "exec:java@license-scan", "-Plicense-check"], timeout=15),
    F("audit", "Quality & security", "Project audit",
      "Package/directory mismatches, duplicate classes, XML + shell syntax, dead configs.",
      ["bash", "Scripts/audit-project.sh"],
      options=[opt("quick", "Quick (skip Maven compile)", "bool", kind="flag", flag="--quick", default=True)], timeout=15),

    # API testing
    F("api-example", "API testing", "API spec: public demo API",
      "Runs the bundled YAML spec against jsonplaceholder — nothing to set up.",
      ["bash", "Scripts/api-test.sh", "--example"], artifacts=["target/api-report", "target/allure-results"], timeout=15),
    F("api-all-specs", "API testing", "API spec: run every spec in apitests/",
      "Executes every YAML/JSON spec under src/test/resources/apitests.",
      ["bash", "Scripts/api-test.sh"],
      options=[opt("negative", "Also generate negative tests", "bool", kind="flag", flag="--negative", default=False),
               opt("retry", "Retry failed requests", "int", kind="arg", flag="--retry", lo=0, hi=5, default=0)],
      artifacts=["target/api-report", "target/allure-results"], timeout=20),
    F("api-openapi", "API testing", "API tests from an OpenAPI document",
      "Generates and runs tests from an OpenAPI/Swagger URL.",
      ["bash", "Scripts/api-test.sh"],
      options=[opt("openapi", "OpenAPI document URL", "url", kind="arg", flag="--openapi", default="https://petstore3.swagger.io/api/v3/openapi.json"),
               opt("negative", "Also generate negative tests", "bool", kind="flag", flag="--negative", default=False)],
      artifacts=["target/api-report", "target/allure-results"], timeout=20, needs=["internet"]),

    # AI & discovery
    F("bug-crawler", "AI & discovery", "Bug crawler (web)",
      "Crawls a site, finds broken links/images, console errors, accessibility issues; optional AI review.",
      ["{mvn}", "-B", "-q", "-Pbug-crawler", "exec:java@bug-crawler"], options=CRAWLER_OPTS, globals=True,
      artifacts=["target/crawler"], timeout=40, needs=["internet"]),
    F("mobile-bug-crawler", "AI & discovery", "Bug crawler (mobile app)",
      "Explores the Android app screens through Appium. Needs Appium + a device/emulator.",
      ["{mvn}", "-B", "-q", "-Pmobile-bug-crawler", "exec:java@mobile-bug-crawler", "-Dsite=mobile"],
      artifacts=["target/mobile-crawler", "target/crawler"], timeout=40, needs=["appium"]),
    F("recorder", "AI & discovery", "Test recorder",
      "Opens a browser, records your clicks into a keyword script. Needs a screen (not headless).",
      ["{mvn}", "-B", "-q", "test-compile", "exec:java@recorder", "-Precorder"],
      options=[opt("recorder.site", "Site", "name", kind="prop", default="saucedemo"),
               opt("recorder.name", "Test name", "name", kind="prop", default="TC_Recorded"),
               opt("recorder.url", "Start path (e.g. /)", "relurl", kind="prop", default="/")],
      timeout=30, needs=["display"], recorder=True),

    # Reports
    F("allure-report", "Reports", "Build Allure report", "Turns target/allure-results into target/allure-report.",
      ["{mvn}", "-B", "-ntp", "-q", "allure:report"], artifacts=["target/allure-report"], timeout=10),
    F("segmented-reports", "Reports", "Segmented reports (by browser/site/type)",
      "Splits one Allure run into separate reports per browser, site, suite, severity and category.",
      ["python3", ".github/workflows/scripts/generate_segmented_reports.py"],
      artifacts=["target/allure-segmented"], timeout=15, needs=["python3"]),
    F("pdf-report", "Reports", "PDF report", "Builds a PDF from the last test run (target/pdf-reports).",
      ["{mvn}", "-B", "-ntp", "-q", "process-test-resources", "compile", "exec:java@pdf-report", "-Ppdf-report"],
      artifacts=["target/pdf-reports"], timeout=10),
    F("email-report", "Reports", "E-mail the report",
      "Sends the run summary + attachments. Dry-run is ON by default: it builds the mail but sends nothing.",
      ["{mvn}", "-B", "-ntp", "-q", "test-compile", "exec:java@email-report", "-Pemail-report", "-Demail.enabled=true"],
      options=[opt("email.dryRun", "Dry run (do NOT send)", "bool", default=True, always=True),
               opt("email.attach.reports", "Attach reports", "bool", default=True),
               opt("email.attach.pdf", "Attach PDF", "bool", default=True)], timeout=10),

    # Test intelligence & housekeeping
    F("tia-preview", "Test intelligence", "Test Impact Analysis",
      "Shows which tests are affected by your code changes since a git ref — optionally runs only those.",
      ["bash", "Scripts/test-impact-analysis.sh"],
      options=[opt("base", "Compare against (git ref)", "ref", kind="arg", flag="--base", default="origin/main"),
               opt("run", "Actually run the affected tests", "bool", kind="flag", flag="--run", default=False)],
      globals=False, artifacts=REPORT_DIRS + ["target/tia"], timeout=60, needs=["git"]),
    F("coverage-map", "Test intelligence", "Build coverage map",
      "Records which test touches which class (feeds Test Impact Analysis). Runs a suite with JaCoCo.",
      ["bash", "Scripts/build-coverage-map.sh"], site_arg=True, artifacts=["target/tia"], timeout=60),
    F("prune-artifacts", "Housekeeping", "Prune old videos / artifacts",
      "Keeps the newest N runs of target/videos. Dry-run is ON by default.",
      ["bash", "Scripts/prune-artifacts.sh"],
      options=[opt("dry-run", "Dry run (delete nothing)", "bool", kind="flag", flag="--dry-run", default=True),
               opt("keep-runs", "Runs to keep", "int", kind="arg", flag="--keep-runs", lo=1, hi=1000, default=5)], timeout=10),
    F("enabled-sites", "Housekeeping", "List enabled sites", "What pipeline-config.properties currently enables.",
      ["bash", "Scripts/enabled-sites.sh"], timeout=2),
    F("maven-version", "Housekeeping", "Environment check", "Maven + Java versions this dashboard will use.",
      ["{mvn}", "-version"], timeout=2),

    # System-level (need DASHBOARD_RUNNER_ALLOW_SYSTEM=1)
    F("grid-up", "Docker Grid (advanced)", "Start Selenium Grid", "docker compose up -d hub + Chrome/Firefox/Edge nodes.",
      ["docker", "compose", "up", "-d", "selenium-hub", "chrome", "firefox", "edge"], timeout=10, needs=["docker"], risk="system"),
    F("grid-down", "Docker Grid (advanced)", "Stop Selenium Grid", "docker compose down.",
      ["docker", "compose", "down"], timeout=5, needs=["docker"], risk="system"),
    F("grid-status", "Docker Grid (advanced)", "Grid status", "docker compose ps.",
      ["docker", "compose", "ps"], timeout=2, needs=["docker"]),
    F("new-site", "Scaffolding (advanced)", "Add a new UI site",
      "Scaffolds Page Object + keyword + data-driven tests + CI wiring (writes files in the repo).",
      ["bash", "Scripts/new-site.sh"], positional=[opt("name", "Site name", "name", default="mysite"),
                                                      opt("url", "Base URL", "url", default="https://example.com")],
      timeout=5, risk="system"),
    F("new-api-site", "Scaffolding (advanced)", "Add a new API-only site",
      "Scaffolds an API-only site and its CI wiring (writes files in the repo).",
      ["bash", "Scripts/new-api-site.sh"], positional=[opt("name", "Site name", "name", default="myapi"),
                                                          opt("url", "Base URL", "url", default="https://api.example.com")],
      timeout=5, risk="system"),
]

# Friendly titles for generated suite cards (anything missing falls back to the file name).
SUITE_TITLES = {
    "demoqa-smoke": "demoqa — smoke", "demoqa-regression": "demoqa — full regression",
    "demoqa-accessibility": "demoqa — accessibility (axe-core)", "demoqa-visual": "demoqa — visual regression",
    "demoqa-perf": "demoqa — performance (JMeter)", "demoqa-synthetic-data": "demoqa — synthetic data",
    "saucedemo-smoke": "saucedemo — smoke", "saucedemo-regression": "saucedemo — full regression",
    "saucedemo-bdd": "saucedemo — BDD (Cucumber)", "SAHMAT-smoke": "SAHMAT — smoke (needs local Ollama)",
    "SAHMAT-regression": "SAHMAT — full regression (needs local Ollama)",
    "mobile-smoke": "Mobile — smoke (Appium)", "mobile-regression": "Mobile — regression (Appium)",
    "bdd": "BDD — web + API scenarios", "bdd-mobile": "BDD — mobile scenarios",
    "api-tests": "API — Book Store (REST)", "api-tests-jsonplaceholder": "API — jsonplaceholder",
    "api-tests-mocked": "API — mocked with WireMock (offline)", "api-spec": "API — spec files (YAML/JSON)",
    "api-security": "API — security checks", "api-rate-limit": "API — rate-limit checks",
    "api-contracts": "API — consumer contracts (Pact)", "api-postman": "API — Postman/Newman collection",
    "jsonplaceholder-perf": "jsonplaceholder — performance (JMeter)",
}
# Suites that don't map to exactly one site by their package references.
SUITE_SITE_DEFAULTS = {"bdd": "saucedemo", "bdd-mobile": "mobile", "api-spec": "apitest", "api-security": "apitest",
                       "api-rate-limit": "apitest", "api-contracts": "jsonplaceholder", "api-postman": "apitest",
                       "api-tests-mocked": "demoqa", "saucedemo-bdd": "saucedemo"}

BUNDLES = [
    {"id": "demo-quick", "title": "Quick demo (≈10 min)", "desc": "Audit → unit tests → API demo → mocked API → BDD (API) → PDF report.",
     "steps": [{"feature": "audit"}, {"feature": "unit-tests"}, {"feature": "api-example"},
               {"feature": "suite:api-tests-mocked"}, {"feature": "suite:bdd", "options": {"site": "jsonplaceholder"}},
               {"feature": "pdf-report"}]},
    {"id": "demo-ui", "title": "UI showcase", "desc": "saucedemo smoke (headless) → accessibility → visual → Allure report.",
     "steps": [{"feature": "suite:saucedemo-smoke", "options": {"headless": True}},
               {"feature": "suite:demoqa-accessibility", "options": {"headless": True}},
               {"feature": "suite:demoqa-visual", "options": {"headless": True}}, {"feature": "allure-report"}]},
    {"id": "quality-gate", "title": "Quality gate", "desc": "Checkstyle → unit tests → license scan → SBOM.",
     "steps": [{"feature": "checkstyle"}, {"feature": "unit-tests"}, {"feature": "license-scan"}, {"feature": "sbom"}]},
]
