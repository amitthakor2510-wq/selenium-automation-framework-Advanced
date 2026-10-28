"""Regression tests: every validated value must be a FULL match.

Patterns written as ``^...$`` and applied with ``re.match`` still accept a trailing newline
(``$`` matches before a final "\\n"). Such a value could slip past the allowlist and, for values that
are written into generated .properties files, start a new line (extra key). All validators now use
``fullmatch``; these tests pin that behaviour.
"""
import unittest

from auth import secrets_store, users
from runner import allowlist as al
from runner import ci_trigger, workspace
from specs import spec_store


class TrailingNewlineIsRejected(unittest.TestCase):
    def test_allowlist_property_values(self):
        for key, value in (("site", "demoqa\n"), ("headless", "true\n"), ("browser", "chrome\n"), ("retry.count", "1\n")):
            with self.assertRaises(al.NotAllowed, msg=f"{key}={value!r}"):
                al._check_property("ui", key, value, "/nonexistent")

    def test_allowlist_still_accepts_clean_values(self):
        al._check_property("ui", "site", "demoqa", "/nonexistent")
        al._check_property("ui", "headless", "true", "/nonexistent")

    def test_suite_name(self):
        with self.assertRaises(al.NotAllowed):
            al._check_suite("testng-suites/demoqa-smoke.xml\n", "/nonexistent", "/nonexistent")

    def test_run_id(self):
        self.assertIsNone(workspace.RUN_ID_RE.fullmatch("20260101-000000-abcdef\n"))
        self.assertIsNotNone(workspace.RUN_ID_RE.fullmatch("20260101-000000-abcdef"))
        with self.assertRaises(Exception):
            workspace.path_for("20260101-000000-abcdef\n")

    def test_template_slug(self):
        with self.assertRaises(ValueError):
            spec_store._tdir("my-template\n")

    def test_user_and_secret_names(self):
        self.assertIsNone(users._NAME_RE.fullmatch("bob\n"))
        self.assertIsNone(secrets_store.NAME_RE.fullmatch("API_TOKEN\n"))
        self.assertIsNone(secrets_store.REF_RE.fullmatch("${secret:API_TOKEN}\n"))

    def test_ci_trigger_param_patterns(self):
        for name, rx in ci_trigger.ALLOWED_PARAMS.items():
            self.assertIsNone(rx.fullmatch("smoke\n"), name)
        self.assertIsNone(ci_trigger._REF_RE.fullmatch("main\n"))

    def test_cucumber_tag_filter_cannot_carry_a_newline(self):
        from features import service
        self.assertIsNone(service._TAGS.fullmatch("@smoke\n"))
        self.assertIsNotNone(service._TAGS.fullmatch("@smoke and not @slow"))


if __name__ == "__main__":
    unittest.main()
