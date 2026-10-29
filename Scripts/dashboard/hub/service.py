"""Backend of the Test Hub (/hub): what can be tested, whether this computer is ready, and recent results.

Read-only and side-effect free: it only looks at PATH, a few local ports and the runs folder.
"""
import shutil
import socket

from auth import secrets_store
from runner import allowlist, workspace

# id, title, one-line promise, where it opens, what you must give, what is optional
INTENTS = [
    {"id": "website-flow", "title": "Click through a website", "icon": "🖱️",
     "blurb": "Record the steps a person would take (log in, search, buy) and check the result.",
     "open": "/wizard?type=ui", "needs": ["Website address", "The steps, in order"],
     "optional": ["Browser", "Retries", "Video / screenshots", "PDF report"]},
    {"id": "website-bugs", "title": "Scan a website for bugs", "icon": "🕷️",
     "blurb": "Follow every link from a start page and report broken pages, errors and accessibility problems.",
     "open": "/wizard?type=crawler", "needs": ["Address to start from"],
     "optional": ["Max pages / depth", "Browser", "Accessibility check"]},
    {"id": "website-a11y", "title": "Check accessibility", "icon": "♿",
     "blurb": "Find things that block people using screen readers or keyboards (axe-core).",
     "open": "/wizard?type=accessibility", "needs": ["Which site to scan"],
     "optional": ["Severity that fails the run", "Address override", "Browser"]},
    {"id": "website-speed", "title": "Load / speed test", "icon": "⚡",
     "blurb": "Send many virtual users at a site and check response times and error rate.",
     "open": "/wizard?type=performance", "needs": ["Which site to load-test"],
     "optional": ["Users, ramp-up, iterations", "Slowest allowed answer", "Address override"]},
    {"id": "api-requests", "title": "Test an API (write requests)", "icon": "🔌",
     "blurb": "List the requests to send and what each should answer.",
     "open": "/wizard?type=api", "needs": ["API address", "At least one request"],
     "optional": ["Login (bearer / basic / API key)", "Extra headers", "Retries", "Self-signed certificates"]},
    {"id": "api-openapi", "title": "Test an API from Swagger / OpenAPI", "icon": "📜",
     "blurb": "Give the link to the API's document and a test is generated for every endpoint.",
     "open": "/wizard?type=openapi", "needs": ["OpenAPI / Swagger link"],
     "optional": ["Login", "Address override", "Negative tests", "Retries"]},
    {"id": "mobile-app", "title": "Check a mobile app", "icon": "📱",
     "blurb": "Explore an Android or iOS app through Appium and report crashes and dead ends.",
     "open": "/wizard?type=mobile", "needs": ["App package name or app file"],
     "optional": ["Device name", "Appium address", "Screens / depth", "Words never to tap"]},
    {"id": "ready-made", "title": "Run a ready-made suite", "icon": "📦",
     "blurb": "Smoke and regression suites for the bundled sites, BDD scenarios, visual checks and more.",
     "open": "/control", "needs": ["Pick a suite"], "optional": ["Site, browser, headless, retries"]},
    {"id": "quality", "title": "Check the framework's own health", "icon": "🛡️",
     "blurb": "Unit tests, code style, dependency vulnerabilities, licenses, project audit.",
     "open": "/control?group=Quality%20%26%20security", "needs": [], "optional": []},
    {"id": "reports", "title": "Build or send a report", "icon": "📊",
     "blurb": "Allure, PDF, segmented reports and the e-mail report (dry run by default).",
     "open": "/control?group=Reports", "needs": [], "optional": []},
]


def _port_open(port, host="127.0.0.1"):
    try:
        with socket.create_connection((host, port), 0.25):
            return True
    except OSError:
        return False


def readiness(runner_enabled, environ, repo_root="."):
    """Checklist for the Hub. status: ok | warn (some tests need it) | bad (nothing can run)."""
    out = []

    def add(id_, label, status, detail, fix=""):
        out.append({"id": id_, "label": label, "status": status, "detail": detail, "fix": fix})

    add("runner", "Running tests from the dashboard", "ok" if runner_enabled else "bad",
        "switched on" if runner_enabled else "switched off",
        "" if runner_enabled else "Start the dashboard with Scripts/start-dashboard.sh (it turns the runner on).")
    try:
        mvn = allowlist.find_maven(repo_root)  # the repo's ./mvnw, wherever the dashboard was started from
        add("maven", "Maven", "ok", mvn)
    except allowlist.NotAllowed as e:
        mvn = shutil.which("mvn")
        add("maven", "Maven", "ok" if mvn else "bad", mvn or str(e), "" if mvn else "Install Maven, or run the dashboard from the project folder that holds ./mvnw.")
    java = shutil.which("java")
    add("java", "Java", "ok" if java else "bad", java or "not found", "" if java else "Install a JDK (17 or newer) and make sure `java` is on the PATH.")
    docker = shutil.which("docker")
    add("docker", "Docker (for the Selenium Grid)", "ok" if docker else "warn", docker or "not found — only needed for Grid runs",
        "" if docker else "Optional. Install Docker if you want to run browsers in the Grid.")
    appium = _port_open(4723)
    add("appium", "Appium server (mobile tests)", "ok" if appium else "warn", "answering on port 4723" if appium else "not running",
        "" if appium else "Only needed for mobile tests: start it with `appium` and connect an emulator or phone.")
    ollama = _port_open(11434)
    add("ollama", "Ollama (AI features, CAPTCHA vision)", "ok" if ollama else "warn", "answering on port 11434" if ollama else "not running",
        "" if ollama else "Optional. Only the AI features and the SAHMAT suites use it.")
    avail = secrets_store.available()
    add("secrets", "Saved secrets (passwords, tokens)", "ok" if avail else "warn", "available" if avail else "not set up",
        "" if avail else "Needed only to use logins in API/UI tests: an admin sets DASHBOARD_SECRET_KEY, then adds secrets.")
    return out


def recent_runs(limit=10):
    keep = ("id", "name", "type", "state", "verdict", "headline", "counts", "startedBy", "createdAt", "finishedAt", "reportLink")
    return [{k: r.get(k) for k in keep} for r in workspace.list_runs(limit)]
