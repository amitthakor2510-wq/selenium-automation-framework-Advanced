"""Performance spec -> the framework's perf.* properties for an existing <site>-perf.xml suite."""


def suite_for(site):
    return f"testng-suites/{site}-perf.xml"


def convert(spec, inputs_dir=None):
    t, d = spec["target"], spec["details"]
    props = {"site": t["site"], "perf.threads": str(d["threads"]), "perf.rampUpSeconds": str(d["rampUpSeconds"]),
             "perf.iterations": str(d["iterations"]), "perf.maxP99Millis": str(d["maxP99Millis"]),
             "perf.maxErrorRatePercent": str(d["maxErrorRatePercent"])}
    if t.get("baseUrl"):
        props["url"] = t["baseUrl"]
    return {"files": [], "suite_xml": suite_for(t["site"]), "properties": props, "secret_env": {}, "env_passthrough": []}
