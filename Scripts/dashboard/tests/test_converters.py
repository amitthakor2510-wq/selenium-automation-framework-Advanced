import csv
import io
import json
import os
import unittest

from tests import _helpers as h
from specs.converters import convert


class ConverterTests(unittest.TestCase):
    def setUp(self):
        self._t = h.tmpdir()
        self.d = self._t.name

    def tearDown(self):
        self._t.cleanup()

    def test_ui_files_and_secret_rewrite(self):
        plan = convert(h.ui_spec(), self.d)
        rows = list(csv.DictReader(io.StringIO(h.read(os.path.join(self.d, "scenario.csv")), newline="")))
        self.assertEqual([r["keyword"] for r in rows], ["NAVIGATE", "TYPE", "TYPE", "CLICK", "VERIFY_URL_CONTAINS"])
        self.assertEqual(rows[2]["testData"], "${env:WIZARD_SECRET_SAUCE_PW}")
        self.assertEqual(plan["secret_env"], {"WIZARD_SECRET_SAUCE_PW": "SAUCE_PW"})
        self.assertEqual(rows[0]["testCase"], "TC_Valid_login")
        repo = h.read(os.path.join(self.d, "objectrepository.properties"))
        self.assertIn("wiz.e1=id:user-name", repo)
        self.assertEqual({r["locatorKey"] for r in rows} - {""}, {"wiz.e1", "wiz.e2", "wiz.e3"})
        suite = h.read(plan["suite_xml"])
        self.assertIn("WizardKeywordRunTest", suite)
        self.assertEqual(plan["properties"]["wizard.keyword.cases"], "TC_Valid_login")
        # the secret value can't be in any generated file (only a reference to it)
        for f in plan["files"]:
            self.assertNotIn("hunter", h.read(f))

    def test_ui_locators_deduplicated(self):
        s = h.ui_spec()
        s["details"]["steps"].append({"keyword": "CLICK", "locator": "id:login-button"})
        convert(s, self.d)
        repo = h.read(os.path.join(self.d, "objectrepository.properties"))
        self.assertEqual(repo.count("id:login-button"), 1)

    def test_suite_name_is_xml_escaped(self):
        s = h.ui_spec(name='A "quoted" & <odd> name')
        plan = convert(s, self.d)
        import xml.dom.minidom
        xml.dom.minidom.parseString(h.read(plan["suite_xml"]).split("\n", 1)[1])

    def test_backslash_in_locator_escaped_for_properties(self):
        s = h.ui_spec()
        s["details"]["steps"][1]["locator"] = "xpath://a[@x='a\\b']"
        convert(s, self.d)
        self.assertIn("a\\\\b", h.read(os.path.join(self.d, "objectrepository.properties")))

    def test_api(self):
        plan = convert(h.api_spec(), self.d)
        doc = json.loads(h.read(plan["spec_file"]))
        self.assertEqual(doc["baseUrl"], "https://jsonplaceholder.typicode.com")
        self.assertEqual(doc["auth"], {"type": "bearer", "token": "${env:API_TOKEN}"})
        self.assertEqual(doc["tests"][0]["get"], "/posts/1")
        self.assertEqual(doc["tests"][1]["body"], {"title": "x"})
        self.assertEqual(plan["env_passthrough"], ["API_TOKEN"])

    def test_perf_crawler_a11y(self):
        p = convert(h.perf_spec(), self.d)
        self.assertEqual(p["suite_xml"], "testng-suites/demoqa-perf.xml")
        self.assertEqual(p["properties"]["perf.threads"], "5")
        c = convert(h.crawler_spec(), self.d)
        self.assertEqual(c["properties"]["crawler.startUrl"], "https://example.com")
        a = convert(h.a11y_spec(), self.d)
        self.assertEqual(a["properties"]["a11y.failOn"], "serious,critical")


if __name__ == "__main__":
    unittest.main()
