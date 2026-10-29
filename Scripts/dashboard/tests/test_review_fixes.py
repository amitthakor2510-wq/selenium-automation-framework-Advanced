"""Unit regressions from the code review: config writer guards and the coverage threshold source."""
import os
import unittest

from tests import _helpers as h
import config_files as cf
import results


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


class ConfigGuards(unittest.TestCase):
    def setUp(self):
        self._t = h.tmpdir()
        self.pipe = os.path.join(self._t.name, "pipeline-config.properties")
        self.tests = os.path.join(self._t.name, "test-config.properties")
        h.write(self.pipe, "site.demoqa.enabled=true\n")
        h.write(self.tests, "test.LoginTest.enabled=true\nrun.only=\n")

    def tearDown(self):
        self._t.cleanup()

    def test_unknown_site_rejected(self):
        with self.assertRaises(ValueError):
            cf.set_site_enabled(self.pipe, "ghost", True)
        self.assertNotIn("ghost", _read(self.pipe))

    def test_unknown_test_rejected_in_single_and_bulk(self):
        with self.assertRaises(ValueError):
            cf.set_test_enabled(self.tests, "Nope", True)
        with self.assertRaises(ValueError):
            cf.set_tests_enabled(self.tests, ["LoginTest", "Nope"], False)
        text = _read(self.tests)
        self.assertNotIn("Nope", text)
        self.assertIn("test.LoginTest.enabled=true", text)  # bulk call is all-or-nothing

    def test_length_caps(self):
        with self.assertRaises(ValueError):
            cf.set_test_enabled(self.tests, "x" * (cf.MAX_NAME_LENGTH + 1), True)
        with self.assertRaises(ValueError):
            cf.set_group_enabled(self.tests, "g" * (cf.MAX_GROUP_LENGTH + 1), False)

    def test_value_with_backslash_is_written_literally(self):
        cf.PropertiesFile(self.tests).set("run.only", "a\\g<1>b")
        self.assertIn("run.only=a\\g<1>b", _read(self.tests))

    def test_existing_toggle_works(self):
        cf.set_test_enabled(self.tests, "LoginTest", False)
        cf.set_site_enabled(self.pipe, "demoqa", False)
        self.assertIn("test.LoginTest.enabled=false", _read(self.tests))
        self.assertIn("site.demoqa.enabled=false", _read(self.pipe))


class CoverageThreshold(unittest.TestCase):
    def test_reads_pom_property_and_falls_back(self):
        with h.tmpdir() as d:
            self.assertEqual(results._coverage_threshold_pct(d), 60.0)
            h.write(os.path.join(d, "pom.xml"), "<p><jacoco.core.minLineCoverage>0.45</jacoco.core.minLineCoverage></p>")
            self.assertEqual(results._coverage_threshold_pct(d), 45.0)


if __name__ == "__main__":
    unittest.main()
