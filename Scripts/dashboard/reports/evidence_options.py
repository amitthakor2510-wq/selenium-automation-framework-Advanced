"""
Maps the wizard's evidence choices (screenshots / video / AI note) to real framework
properties. Evidence only applies to browser-driven types.
"""

BROWSER_TYPES = ("ui", "accessibility", "crawler")
SCREENSHOT_CHOICES = ["on-failure", "always", "never"]
DEFAULTS = {"screenshots": "on-failure", "video": False, "aiNote": False}


def applies(test_type):
    return test_type in BROWSER_TYPES


def normalize(test_type, chosen):
    if not applies(test_type):
        return {}
    chosen = chosen or {}
    shots = chosen.get("screenshots", DEFAULTS["screenshots"])
    return {"screenshots": shots if shots in SCREENSHOT_CHOICES else DEFAULTS["screenshots"],
            "video": bool(chosen.get("video", False)), "aiNote": bool(chosen.get("aiNote", False))}


def to_properties(test_type, chosen):
    """
    video      -> video.enabled (+ video.keep.on.pass when 'always' screenshots are wanted too)
    aiNote     -> ai.exceptionAnalysis.enabled (AI root-cause note on failures; needs the AI
                  provider configured in global.properties — off unless chosen)
    screenshots-> the framework already attaches a screenshot to every failure; the wizard's
                  "always"/"never" choices are recorded in the spec and shown in the summary, and
                  only video.keep.on.pass is tied to "always". (No per-run screenshot switch
                  exists in the framework to map "never" to — it is reported honestly as such.)
    """
    norm = normalize(test_type, chosen)
    if not norm:
        return {}
    props = {"video.enabled": "true" if norm["video"] else "false",
             "ai.exceptionAnalysis.enabled": "true" if norm["aiNote"] else "false"}
    if norm["video"] and norm["screenshots"] == "always":
        props["video.keep.on.pass"] = "true"
    return props


def notes(test_type, chosen):
    """Plain-language caveats shown on the review step."""
    norm = normalize(test_type, chosen)
    out = []
    if norm.get("screenshots") == "never":
        out.append("Screenshots can't be switched off per run: the framework always attaches one to a failure.")
    if norm.get("screenshots") == "always":
        out.append("'Always' keeps video of passing tests; screenshots of passing tests are not captured by the framework.")
    if norm.get("aiNote"):
        out.append("AI note needs an AI provider configured (ai.* in global.properties); otherwise it's skipped.")
    return out
