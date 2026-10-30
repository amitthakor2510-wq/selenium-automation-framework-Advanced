import os
import unittest

from tests import _helpers as h
from runner import allowlist as al
from specs.converters import convert


class AllowlistTests(unittest.TestCase):
    def setUp(self):
        self._t = h.tmpdir()
        self.repo = os.path.join(self._t.name, "repo")
        os.makedirs(os.path.join(self.repo, "testng-suites"))
        h.write(os.path.join(self.repo, "testng-suites", "demoqa-perf.xml"))
        h.write(os.path.join(self.repo, "testng-suites", "demoqa-accessibility.xml"))
        self.ws = os.path.join(self._t.name, "ws")
        os.makedirs(os.path.join(self.ws, "inputs"))
        # a Maven launcher must exist for build_command
        self.bin = os.path.join(self._t.name, "bin")
        os.makedirs(self.bin)
        mvn = os.path.join(self.bin, "mvn")
        h.write(mvn, "#!/bin/sh\n")
        os.chmod(mvn, 0o755)
        self._path = os.environ["PATH"]
        os.environ["PATH"] = self.bin + os.pathsep + self._path

    def tearDown(self):
        os.environ["PATH"] = self._path
        self._t.cleanup()

    def cmd(self, spec):
        plan = convert(spec, os.path.join(self.ws, "inputs"))
        return al.build_command(spec["type"], plan, self.repo, self.ws)

    def test_ui_command_shape(self):
        argv = self.cmd(h.ui_spec())
        self.assertTrue(argv[0].endswith("mvn"))
        self.assertEqual(argv[1:4], ["-B", "-ntp", "test"])
        self.assertTrue(any(a.startswith("-DsuiteXmlFile=" + self.ws) for a in argv))
        self.assertIn("-Dsite=saucedemo", argv)
        self.assertNotIn("sh", argv)

    def test_perf_a11y_crawler_api(self):
        self.assertIn("-Pperf", self.cmd(h.perf_spec()))
        self.assertIn("-DsuiteXmlFile=testng-suites/demoqa-accessibility.xml", self.cmd(h.a11y_spec()))
        self.assertIn("exec:java@bug-crawler", self.cmd(h.crawler_spec()))
        api = self.cmd(h.api_spec())
        self.assertEqual(api[:2], ["bash", "Scripts/api-test.sh"])

    def test_rejects_unlisted_property(self):
        plan = {"properties": {"exec.args": "rm -rf /"}}
        with self.assertRaises(al.NotAllowed):
            al.build_command("ui", plan, self.repo, self.ws)
        with self.assertRaises(al.NotAllowed):
            al.build_command("performance", {"properties": {"wizard.keyword.repo": "/x"}}, self.repo, self.ws)

    def test_rejects_bad_values(self):
        for k, bad in (("site", "a;b"), ("site", "../x"), ("browser", "netscape"), ("url", "file:///etc/passwd"),
                       ("url", "http://a b"), ("retry.count", "99"), ("headless", "yes")):
            with self.assertRaises(al.NotAllowed, msg=f"{k}={bad}"):
                al.build_command("ui", {"properties": {k: bad}}, self.repo, self.ws)

    def test_rejects_paths_outside_workspace(self):
        outside = os.path.join(self._t.name, "evil.csv")
        h.write(outside)
        with self.assertRaises(al.NotAllowed):
            al.build_command("ui", {"properties": {"wizard.keyword.script": outside}}, self.repo, self.ws)
        link = os.path.join(self.ws, "inputs", "link.csv")
        os.symlink(outside, link)
        with self.assertRaises(al.NotAllowed):
            al.build_command("ui", {"properties": {"wizard.keyword.script": link}}, self.repo, self.ws)

    def test_rejects_arbitrary_suite(self):
        for s in ("../../etc/passwd", "/etc/passwd", "testng-suites/missing.xml", "testng-suites/a b.xml"):
            with self.assertRaises(al.NotAllowed):
                al.build_command("ui", {"suite_xml": s, "properties": {}}, self.repo, self.ws)

    def test_api_spec_must_be_in_workspace(self):
        with self.assertRaises(al.NotAllowed):
            al.build_command("api", {"spec_file": "/etc/passwd", "base_url": "https://a.com"}, self.repo, self.ws)

    def test_unknown_type(self):
        with self.assertRaises(al.NotAllowed):
            al.build_command("shell", {}, self.repo, self.ws)

    def test_env_is_minimal_and_keeps_dashboard_secrets_out(self):
        environ = {"PATH": "/bin", "HOME": "/h", "DASHBOARD_PASSWORD": "pw", "DASHBOARD_SECRET_KEY": "k", "AWS_SECRET_ACCESS_KEY": "x", "API_TOKEN": "t"}
        env = al.build_env({"env_passthrough": ["API_TOKEN"]}, {"WIZARD_SECRET_A": "v"}, environ)
        self.assertEqual(set(env), {"PATH", "HOME", "API_TOKEN", "WIZARD_SECRET_A"})
        with self.assertRaises(al.NotAllowed):
            al.build_env({"env_passthrough": ["DASHBOARD_PASSWORD"]}, {}, environ)
        with self.assertRaises(al.NotAllowed):
            al.build_env({}, {"PATH": "x"}, environ)

    def test_redact(self):
        self.assertEqual(al.redact("pw=hunter22 ok", ["hunter22"]), "pw=*** ok")

    def test_missing_maven_in_container_explains_docker_limit(self):
        from unittest import mock
        os.environ["PATH"] = ""  # no mvn anywhere
        with mock.patch.object(al, "in_container", return_value=True):
            with self.assertRaises(al.NotAllowed) as cm:
                al.find_maven(self.repo)
        self.assertIn("no Java or Maven", str(cm.exception))
        self.assertIn("DOCKER.md", str(cm.exception))
        with mock.patch.object(al, "in_container", return_value=False):
            with self.assertRaises(al.NotAllowed) as cm:
                al.find_maven(self.repo)
        self.assertNotIn("Dockerfile.dashboard", str(cm.exception))


if __name__ == "__main__":
    unittest.main()
