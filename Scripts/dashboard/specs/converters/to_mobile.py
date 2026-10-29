"""Mobile spec -> the mobile bug crawler (Appium) with mobile.* / crawler.mobile.* properties.

The crawler's own safety list of words it never taps (delete, log out, pay, buy, reset, submit ...) is
a full-replacement property, so extra words from the form are APPENDED to DEFAULT_AVOID here — a form
value can add to the list but never shrink it. A test (test_mobile_converter) keeps DEFAULT_AVOID in
step with src/test/resources/config/global.properties.
"""

DEFAULT_AVOID = ("delete,logout,log out,sign out,uninstall,pay,purchase,buy,remove account,reset,submit,confirm")


def avoid_list(extra):
    words = [w.strip() for w in DEFAULT_AVOID.split(",")]
    for w in (extra or "").split(","):
        w = w.strip()
        if w and w.lower() not in (x.lower() for x in words):
            words.append(w)
    return ",".join(words)


def convert(spec, inputs_dir=None):
    t, d = spec["target"], spec["details"]
    props = {"mobile.platform": t["platform"]}
    for key, field in (("mobile.app.package", "appPackage"), ("mobile.app.path", "appPath"),
                       ("mobile.app.activity", "appActivity"), ("mobile.device.name", "deviceName"),
                       ("mobile.platform.version", "platformVersion"), ("appium.server.url", "appiumUrl")):
        if t.get(field):
            props[key] = str(t[field])
    props["mobile.noReset"] = "true" if t.get("noReset") else "false"
    props["crawler.mobile.maxScreens"] = str(d.get("maxScreens", 30))
    props["crawler.mobile.maxDepth"] = str(d.get("maxDepth", 4))
    props["crawler.mobile.avoidTextContains"] = avoid_list(d.get("avoidWords"))
    return {"files": [], "properties": props, "secret_env": {}, "env_passthrough": []}
