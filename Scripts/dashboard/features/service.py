"""
Turns a Control Center request ({feature, options}) into ONE allowlisted argv.

  describe(ctx)            -> what the page shows (features, bundles, global switches, availability)
  validate_spec(spec, ctx) -> list of error strings ([] = fine)      # used by RunnerService.start
  build(feature, options, ctx) -> plan {argv, props, report_dirs, timeout, title, env_passthrough}

ctx = {"repo_root", "sites": [names], "suite_sites": {"foo.xml": "site"|None}, "environ": os.environ}
Nothing here executes anything; the runner does that, with shell=False.
"""
import os
import re
import shutil
import socket
import sys

from features import catalog as C

SUITE_PREFIX = "suite:"
_SUITE_FILE = re.compile(r"^[A-Za-z0-9_-]+\.xml$")
_NAME = C._NAME
_BOOLS = {True: "true", False: "false", "true": "true", "false": "false"}


class FeatureError(ValueError):
    pass


# ── value validation ─────────────────────────────────────────
def coerce(o, value, ctx=None):
    """Validate one switch value against its declared type; return the string to use."""
    t = o["type"]
    if t == "bool":
        if value not in _BOOLS:
            raise FeatureError(f"switch '{o['key']}' must be true or false")
        return _BOOLS[value]
    s = str(value).strip()
    if t == "int":
        if not re.fullmatch(r"\d{1,6}", s) or not (o["min"] <= int(s) <= o["max"]):
            raise FeatureError(f"switch '{o['key']}' must be a whole number {o['min']}-{o['max']}")
        return str(int(s))
    if t == "enum":
        if s not in (o["values"] or []):
            raise FeatureError(f"switch '{o['key']}' must be one of: {', '.join(o['values'] or [])}")
        return s
    rule = {"url": C._URL, "ref": C._REF, "name": C._NAME, "relurl": C._REL_URL}[t]
    if not rule.fullmatch(s) or s.startswith("-"):
        raise FeatureError(f"switch '{o['key']}' has a value that is not allowed")
    return s


# ── dynamic suite features ───────────────────────────────────
def _suite_features(ctx):
    out = []
    sdir = os.path.join(ctx["repo_root"], "testng-suites")
    if not os.path.isdir(sdir):
        return out
    for fn in sorted(os.listdir(sdir)):
        if not _SUITE_FILE.fullmatch(fn):
            continue
        base = fn[:-4]
        site = ctx.get("suite_sites", {}).get(fn) or C.SUITE_SITE_DEFAULTS.get(base)
        low = base.lower()
        safari = "safari" in low
        perf = low.endswith("-perf")
        if low.startswith(("api-",)):
            group = "API testing"
        elif "bdd" in low:
            group = "BDD (Cucumber)"
        elif low.startswith("mobile"):
            group = "Mobile (Appium)"
        elif any(k in low for k in ("accessibility", "visual", "perf", "synthetic")):
            group = "Accessibility, visual & performance"
        else:
            group = "UI tests"
        if safari:
            group = "Safari (macOS only)"
        prefix = list(C.MVN_TEST)
        opts = []
        if perf:
            prefix = ["{mvn}", "-B", "-ntp", "-Pperf", "test", "-Dgroups=perf"]
            opts += C.PERF_OPTS
        if "synthetic" in low:
            opts += [C.opt("synthetic.data.count", "Rows to generate", "int", lo=1, hi=1000, default=3),
                     C.opt("synthetic.data.seed", "Seed (blank = random)", "int", lo=0, hi=999999)]
        needs = ["appium"] if low.startswith("mobile") or low == "bdd-mobile" else []
        if base.startswith("SAHMAT"):
            needs.append("ollama")
        if safari:
            needs.append("macos")
        web = group in ("UI tests", "Accessibility, visual & performance", "BDD (Cucumber)", "Safari (macOS only)") and not perf
        f = C.F(SUITE_PREFIX + base, group, C.SUITE_TITLES.get(base, base.replace("-", " ")),
                f"testng-suites/{fn}" + (f" · site: {site}" if site else ""), prefix, options=opts,
                globals=web or perf, artifacts=C.REPORT_DIRS + (["target/perf-reports"] if perf else []),
                timeout=60 if "regression" in low else 30, needs=needs, suite=f"testng-suites/{fn}",
                site=site, safari=safari)
        if "bdd" in low:
            f["tags"] = True
        out.append(f)
    return out


def all_features(ctx):
    return C.STATIC + _suite_features(ctx)


def find(feature_id, ctx):
    for f in all_features(ctx):
        if f["id"] == feature_id:
            return f
    raise FeatureError(f"unknown feature {feature_id!r}.")


# ── availability ─────────────────────────────────────────────
def _port_open(port):
    try:
        with socket.create_connection(("127.0.0.1", port), 0.25):
            return True
    except OSError:
        return False


def availability(f, ctx):
    """(blockers, warnings): blockers stop the run, warnings are shown but don't."""
    blockers, warnings = [], []
    env = ctx.get("environ", os.environ)
    for n in f["needs"]:
        if n == "python3" and not shutil.which("python3") and not shutil.which("python"):
            blockers.append("python3 not found")
        elif n == "git" and not shutil.which("git"):
            blockers.append("git not found")
        elif n == "docker" and not shutil.which("docker"):
            blockers.append("docker not found")
        elif n == "macos" and sys.platform != "darwin":
            blockers.append("macOS only (Safari)")
        elif n == "display" and os.name != "nt" and sys.platform != "darwin" and not env.get("DISPLAY"):
            blockers.append("no display available (DISPLAY unset)")
        elif n == "appium" and not _port_open(4723):
            warnings.append("Appium server not reachable on :4723")
        elif n == "ollama" and not _port_open(11434):
            warnings.append("Ollama not reachable on :11434 (CAPTCHA vision will fall back)")
        elif n == "internet":
            warnings.append("needs internet access")
    if f["prefix"][0] == "{mvn}":
        try:
            from runner import allowlist
            allowlist.find_maven(ctx["repo_root"])
        except Exception as e:  # noqa: BLE001
            blockers.append(str(e))
    elif f["prefix"][0] == "bash" and not shutil.which("bash"):
        blockers.append("bash not found")
    if f["risk"] == "system" and env.get("DASHBOARD_RUNNER_ALLOW_SYSTEM", "").strip() != "1":
        blockers.append("system-level action: start the dashboard with DASHBOARD_RUNNER_ALLOW_SYSTEM=1 to enable")
    return blockers, warnings


# ── build ────────────────────────────────────────────────────
def _allowed_options(f):
    opts = {o["key"]: o for o in f["options"]}
    for o in f.get("positional", []):
        opts[o["key"]] = o
    if f["globals"]:
        for o in C.GLOBAL_OPTIONS:
            opts.setdefault(o["key"], o)
    if f.get("suite") and not f.get("fixed_site"):
        opts["site"] = C.opt("site", "Site", "name")
    if f.get("site_arg"):
        opts["site"] = C.opt("site", "Site", "name", default="demoqa")
        opts["suiteFile"] = C.opt("suiteFile", "Suite file", "name")
    if f.get("tags"):
        opts["cucumber.filter.tags"] = C.opt("cucumber.filter.tags", "Tag filter", "name")
    if f.get("safari"):
        opts.pop("browser", None)
    return opts


_TAGS = re.compile(r"^[@A-Za-z0-9_ ()]{1,120}$")


def build(feature_id, options, ctx):
    f = find(feature_id, ctx)
    options = options or {}
    if not isinstance(options, dict):
        raise FeatureError("options must be an object")
    blockers, _ = availability(f, ctx)
    if blockers:
        raise FeatureError(f"{f['title']} can't run here: " + "; ".join(blockers))
    allowed = _allowed_options(f)
    for k in options:
        if k not in allowed:
            raise FeatureError(f"option '{k}' is not available for {f['title']}")

    argv = list(f["prefix"])
    props = {}

    # site + suite
    if f.get("suite"):
        site = str(options.get("site") or f.get("site") or "")
        if site:
            if site not in ctx["sites"]:
                raise FeatureError(f"unknown site: {site!r}")
            argv.append(f"-Dsite={site}")
            props["site"] = site
        argv.append(f"-DsuiteXmlFile={f['suite']}")
        if f.get("safari"):
            argv.append("-Dbrowser=safari")
    if f.get("site_arg"):
        site = str(options.get("site") or "demoqa")
        if site not in ctx["sites"]:
            raise FeatureError(f"unknown site: {site!r}")
        argv.append(site)
        sf = options.get("suiteFile")
        if sf:
            sf = str(sf)
            if not re.fullmatch(r"[A-Za-z0-9_-]+", sf) or not os.path.isfile(os.path.join(ctx["repo_root"], "testng-suites", sf + ".xml")):
                raise FeatureError("unknown suite file")
            argv.append(f"testng-suites/{sf}.xml")
    if f.get("tags") and options.get("cucumber.filter.tags"):
        tags = str(options["cucumber.filter.tags"])
        if not _TAGS.fullmatch(tags):
            raise FeatureError("tag filter may only contain @tags, letters, digits, spaces, 'and/or/not' and ()")
        argv.append(f"-Dcucumber.filter.tags={tags}")
        props["cucumber.filter.tags"] = tags

    # recorder: fixed shape, validated parts
    if f.get("recorder"):
        site = str(options.get("recorder.site") or "saucedemo")
        if site not in ctx["sites"]:
            raise FeatureError(f"unknown site: {site!r}")
        name = coerce(allowed["recorder.name"], options.get("recorder.name") or "TC_Recorded")
        url = coerce(allowed["recorder.url"], options.get("recorder.url") or "/")
        argv += [f"-Dsite={site}", "-Dheadless=false", f"-Dexec.args=--name {name} --url {url}"]
        props.update({"site": site, "headless": "false"})
        options = {}

    # positional scaffolding args
    for o in f.get("positional", []):
        val = options.get(o["key"], o["default"])
        argv.append(coerce(o, val))
    if f.get("positional"):
        options = {}

    # switches. Absent flag/arg switches take their declared default (so a bundle step or a re-run is
    # as safe as a click on the page), and so do prop switches marked always=True (e.g. e-mail dry-run).
    if not f.get("positional") and not f.get("recorder"):
        options = dict(options)
        for o in f["options"]:
            if o["key"] not in options and o["default"] is not None and (o["kind"] != "prop" or o.get("always")):
                options[o["key"]] = o["default"]
    for key in sorted(options):
        o = allowed[key]
        if key in ("site", "suiteFile", "cucumber.filter.tags") or key.startswith("recorder."):
            continue
        val = coerce(o, options[key])
        if o["kind"] == "prop":
            argv.append(f"-D{key}={val}")
            props[key] = val
        elif o["kind"] == "flag":
            if val == "true":
                argv.append(o["flag"])
        else:  # arg
            argv += [o["flag"], val]
    if argv[0] == "{mvn}":
        from runner import allowlist
        argv[0] = allowlist.find_maven(ctx["repo_root"])
    return {"argv": argv, "props": props, "report_dirs": f["artifacts"], "timeout": f["timeout"], "title": f["title"],
            "env_passthrough": []}


def validate_spec(spec, ctx):
    errs = []
    if not isinstance(spec, dict) or spec.get("type") != "feature":
        return ["not a feature spec"]
    try:
        build(spec.get("feature"), spec.get("options"), ctx)
    except FeatureError as e:
        errs.append(str(e))
    return errs


# ── description for the page ─────────────────────────────────
def _public_opt(o):
    d = {k: o[k] for k in ("key", "label", "type", "kind", "default", "values", "min", "max", "help")}
    return d


def describe(ctx):
    feats = []
    for f in all_features(ctx):
        blockers, warnings = availability(f, ctx)
        opts = [_public_opt(o) for o in f["options"]] + [_public_opt(o) for o in f.get("positional", [])]
        if f.get("recorder"):
            opts = [_public_opt(o) for o in f["options"]]
        feats.append({"id": f["id"], "group": f["group"], "title": f["title"], "desc": f["desc"], "options": opts,
                      "globals": f["globals"] and not f.get("safari"), "risk": f["risk"], "blockers": blockers, "warnings": warnings,
                      "site": f.get("site"), "pickSite": bool(f.get("suite")), "siteArg": bool(f.get("site_arg")),
                      "tags": bool(f.get("tags")), "timeout": f["timeout"], "suite": f.get("suite")})
    order = []
    for f in feats:
        if f["group"] not in order:
            order.append(f["group"])
    return {"features": feats, "groups": order, "bundles": C.BUNDLES, "sites": ctx["sites"],
            "globalOptions": [_public_opt(o) for o in C.GLOBAL_OPTIONS]}


def filter_options(feature_id, options, ctx):
    """Keep only the switches this feature accepts (used to apply the page's shared switches to a bundle)."""
    allowed = _allowed_options(find(feature_id, ctx))
    return {k: v for k, v in (options or {}).items() if k in allowed}


def bundle_steps(bundle_id, global_options):
    for b in C.BUNDLES:
        if b["id"] == bundle_id:
            steps = []
            for s in b["steps"]:
                opts = dict(s.get("options", {}))
                steps.append((s["feature"], opts))
            return b, steps
    raise FeatureError(f"unknown bundle {bundle_id!r}.")
