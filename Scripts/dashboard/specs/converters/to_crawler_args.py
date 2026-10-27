"""Crawler spec -> CrawlerCli start URL + crawler.* / browser properties."""


def convert(spec, inputs_dir=None):
    t, d = spec["target"], spec["details"]
    props = {"crawler.startUrl": d["startUrl"], "crawler.maxPages": str(d["maxPages"]),
             "crawler.maxDepth": str(d["maxDepth"]), "crawler.a11y.enabled": "true" if d.get("a11yEnabled", True) else "false",
             "browser": t.get("browser", "chrome"), "headless": "true" if t.get("headless", True) else "false"}
    return {"files": [], "properties": props, "secret_env": {}, "env_passthrough": []}
