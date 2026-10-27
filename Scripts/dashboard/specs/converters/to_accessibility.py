"""Accessibility spec -> a11y.* properties for the site's existing accessibility suite."""


def suite_for(site):
    return f"testng-suites/{site}-accessibility.xml"


def convert(spec, inputs_dir=None):
    t, d = spec["target"], spec["details"]
    props = {"site": t["site"], "a11y.enabled": "true", "a11y.failOn": ",".join(d["failOn"]),
             "browser": t.get("browser", "chrome"), "headless": "true" if t.get("headless", True) else "false"}
    if t.get("baseUrl"):
        props["url"] = t["baseUrl"]
    return {"files": [], "suite_xml": suite_for(t["site"]), "properties": props, "secret_env": {}, "env_passthrough": []}
