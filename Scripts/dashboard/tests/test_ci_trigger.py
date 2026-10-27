import time
import unittest
from unittest import mock

from runner import ci_trigger as ci

ENV = {"DASHBOARD_CI_GITHUB_REPO": "o/r", "DASHBOARD_CI_GITHUB_TOKEN": "ghtok",
       "DASHBOARD_CI_GITLAB_URL": "https://gl.example", "DASHBOARD_CI_GITLAB_PROJECT": "grp%2Fproj", "DASHBOARD_CI_GITLAB_TOKEN": "gltok",
       "DASHBOARD_CI_JENKINS_URL": "https://j.example", "DASHBOARD_CI_JENKINS_JOB": "my job", "DASHBOARD_CI_JENKINS_USER": "u",
       "DASHBOARD_CI_JENKINS_TOKEN": "jtok"}


class CiTests(unittest.TestCase):
    def test_configured(self):
        self.assertEqual(ci.configured(ENV), ["github", "gitlab", "jenkins"])
        self.assertEqual(ci.configured({}), [])
        self.assertEqual(ci.configured({"DASHBOARD_CI_GITHUB_REPO": "o/r"}), [])

    def test_only_allowed_params(self):
        self.assertEqual(ci.clean_params({"suite_type": "smoke", "headless": True}), {"suite_type": "smoke", "headless": "true"})
        for bad in ({"command": "x"}, {"suite_type": "all; rm"}, {"browser": "ie"}, {"site": "a b"}):
            with self.assertRaises(ci.CiError):
                ci.clean_params(bad)

    def test_unconfigured_provider(self):
        with self.assertRaises(ci.CiError):
            ci.start("github", {}, environ={})

    def test_bad_ref(self):
        with self.assertRaises(ci.CiError):
            ci.start("github", {}, ref="main; rm", environ=ENV)

    def test_github_start_and_poll(self):
        calls = []

        def fake(method, url, headers=None, body=None, ca_file=None):
            calls.append((method, url, body))
            if method == "POST":
                return 204, {}, None
            created = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            return 200, {}, {"workflow_runs": [{"created_at": created, "status": "completed", "conclusion": "success", "html_url": "https://gh/run/1"}]}
        with mock.patch.object(ci, "http_request", fake):
            h = ci.start("github", {"suite_type": "smoke", "browser": "chrome", "headless": "true", "site": "x"}, environ=ENV)
            self.assertEqual(calls[0][2], {"ref": "main", "inputs": {"suite_type": "smoke", "browser": "chrome", "headless": "true"}})
            self.assertEqual(ci.poll(h, ENV), {"state": "passed", "url": "https://gh/run/1"})

    def test_gitlab(self):
        def fake(method, url, headers=None, body=None, ca_file=None):
            if method == "POST":
                self.assertEqual(body["variables"], [{"key": "SUITE_TYPE", "value": "smoke"}])
                return 201, {}, {"id": 7, "web_url": "https://gl/p/7"}
            return 200, {}, {"status": "failed", "web_url": "https://gl/p/7"}
        with mock.patch.object(ci, "http_request", fake):
            h = ci.start("gitlab", {"suite_type": "smoke", "site": "demoqa"}, environ=ENV)
            self.assertEqual(ci.poll(h, ENV)["state"], "failed")

    def test_jenkins_queue_then_build(self):
        step = {"n": 0}

        def fake(method, url, headers=None, body=None, ca_file=None):
            if method == "POST":
                self.assertIn("/job/my%20job/buildWithParameters?SUITE_TYPE=smoke", url)
                return 201, {"location": "https://j.example/queue/item/5/"}, None
            step["n"] += 1
            if url.endswith("/queue/item/5/api/json"):
                return 200, {}, ({} if step["n"] == 1 else {"executable": {"url": "https://j.example/job/x/9/"}})
            return 200, {}, {"building": False, "result": "SUCCESS"}
        with mock.patch.object(ci, "http_request", fake):
            h = ci.start("jenkins", {"suite_type": "smoke"}, environ=ENV)
            self.assertEqual(ci.poll(h, ENV)["state"], "queued")
            self.assertEqual(ci.poll(h, ENV)["state"], "passed")

    def test_jenkins_rejects_foreign_queue_url(self):
        with self.assertRaises(ci.CiError):
            ci.poll({"provider": "jenkins", "queue": "https://evil.example/queue/1"}, ENV)


if __name__ == "__main__":
    unittest.main()
