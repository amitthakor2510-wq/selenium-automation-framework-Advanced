"""Control Center catalog: every feature builds, nothing unsafe gets through, referenced files exist."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import config_files as cf  # noqa: E402
import suites as su  # noqa: E402
from features import catalog as C  # noqa: E402
from features import service as S  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


def ctx(**env):
    sites = [s["name"] for s in cf.read_sites(os.path.join(ROOT, "pipeline-config.properties"))]
    by = su.list_suites(os.path.join(ROOT, "testng-suites"), sites)["bySite"]
    return {"repo_root": ROOT, "sites": sites, "environ": dict(env),
            "suite_sites": {fn: s for s, fs in by.items() for fn in fs}}


class CatalogTest(unittest.TestCase):
    def test_ids_unique(self):
        ids = [f["id"] for f in S.all_features(ctx())]
        self.assertEqual(len(ids), len(set(ids)))

    def test_every_script_and_suite_exists(self):
        for f in S.all_features(ctx()):
            if f["prefix"][0] in ("bash", "python3"):
                self.assertTrue(os.path.isfile(os.path.join(ROOT, f["prefix"][1])), f["id"])
            if f.get("suite"):
                self.assertTrue(os.path.isfile(os.path.join(ROOT, f["suite"])), f["id"])

    def test_every_suite_file_has_a_card(self):
        ids = {f["id"] for f in S.all_features(ctx())}
        for fn in os.listdir(os.path.join(ROOT, "testng-suites")):
            if fn.endswith(".xml"):
                self.assertIn("suite:" + fn[:-4], ids)

    def test_every_available_feature_builds_with_no_options(self):
        c = ctx(DISPLAY=":0", DASHBOARD_RUNNER_ALLOW_SYSTEM="1")
        built = 0
        for f in S.all_features(c):
            blockers, _ = S.availability(f, c)
            if blockers:
                continue
            plan = S.build(f["id"], {}, c)
            self.assertTrue(plan["argv"] and plan["timeout"] > 0)
            built += 1
        self.assertGreater(built, 20)

    def test_global_option_defaults_are_valid(self):
        for o in C.GLOBAL_OPTIONS:
            if o["default"] is not None:
                S.coerce(o, o["default"])


class SafetyTest(unittest.TestCase):
    def setUp(self):
        self.c = ctx()

    def bad(self, feature, options):
        with self.assertRaises(S.FeatureError):
            S.build(feature, options, self.c)

    def test_unknown_feature_and_option_rejected(self):
        self.bad("rm-rf", {})
        self.bad("suite:saucedemo-smoke", {"unknown.key": "1"})

    def test_injection_attempts_rejected(self):
        self.bad("suite:saucedemo-smoke", {"browser": "chrome; rm -rf /"})
        self.bad("suite:saucedemo-smoke", {"site": "x -Dfoo=bar"})
        self.bad("suite:saucedemo-smoke", {"retry.count": "9"})
        self.bad("suite:saucedemo-smoke", {"headless": "yes"})
        self.bad("tia-preview", {"base": "--upload-pack=evil"})
        self.bad("tia-preview", {"base": "a b"})
        self.bad("bug-crawler", {"crawler.startUrl": "file:///etc/passwd"})
        self.bad("bug-crawler", {"crawler.maxPages": "-1"})
        self.bad("suite:bdd", {"cucumber.filter.tags": "@a;rm -rf"})
        self.bad("coverage-map", {"suiteFile": "../../etc/passwd"})
        self.bad("prune-artifacts", {"keep-runs": "5 --dir /"})

    def test_values_become_single_argv_items(self):
        argv = S.build("suite:saucedemo-smoke", {"browser": "firefox", "headless": True}, self.c)["argv"]
        self.assertIn("-Dbrowser=firefox", argv)
        self.assertIn("-Dheadless=true", argv)
        self.assertIn("-DsuiteXmlFile=testng-suites/saucedemo-smoke.xml", argv)

    def test_email_is_dry_run_unless_explicitly_switched_off(self):
        self.assertIn("-Demail.dryRun=true", S.build("email-report", {}, self.c)["argv"])
        self.assertIn("-Demail.dryRun=false", S.build("email-report", {"email.dryRun": False}, self.c)["argv"])

    def test_prune_and_audit_defaults_are_safe(self):
        self.assertIn("--dry-run", S.build("prune-artifacts", {}, self.c)["argv"])
        self.assertIn("--quick", S.build("audit", {}, self.c)["argv"])

    def test_system_features_blocked_without_env_flag(self):
        self.bad("new-site", {})
        c = ctx(DASHBOARD_RUNNER_ALLOW_SYSTEM="1")
        plan = S.build("new-site", {"name": "shop", "url": "https://shop.example.com"}, c)
        self.assertEqual(plan["argv"][-2:], ["shop", "https://shop.example.com"])
        with self.assertRaises(S.FeatureError):
            S.build("new-site", {"name": "-bad", "url": "https://x.example"}, c)

    def test_safari_suites_blocked_off_macos(self):
        if sys.platform != "darwin":
            self.bad("suite:saucedemo-safari-smoke", {})

    def test_perf_suites_use_perf_profile(self):
        argv = S.build("suite:demoqa-perf", {"perf.threads": "3"}, self.c)["argv"]
        self.assertIn("-Pperf", argv)
        self.assertIn("-Dgroups=perf", argv)
        self.assertIn("-Dperf.threads=3", argv)

    def test_bundles_reference_real_features(self):
        ids = {f["id"] for f in S.all_features(self.c)}
        for b in C.BUNDLES:
            for step in b["steps"]:
                self.assertIn(step["feature"], ids)


if __name__ == "__main__":
    unittest.main()
