"""
Stdlib-only tests for notify_chat.py — no network beyond a throwaway
localhost HTTP server, no third-party packages. Run with:

    python3 -m unittest discover -s .github/workflows/scripts -p 'test_*.py'
"""
import http.server
import json
import os
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import notify_chat  # noqa: E402

TESTCASE_PASS = '<testcase classname="com.x.LoginTest" name="ok" time="1"/>'
TESTCASE_FAIL = '<testcase classname="com.x.LoginTest" name="bad" time="1"><failure message="boom"/></testcase>'
TESTCASE_SKIP = '<testcase classname="com.x.LoginTest" name="meh" time="1"><skipped/></testcase>'


def write_report(directory, *cases):
    xml = '<testsuite name="com.x.LoginTest">' + "".join(cases) + "</testsuite>"
    with open(os.path.join(directory, "TEST-com.x.LoginTest.xml"), "w", encoding="utf-8") as f:
        f.write(xml)


class _Recorder(http.server.BaseHTTPRequestHandler):
    received = []
    status = 200

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        _Recorder.received.append(json.loads(self.rfile.read(length)))
        self.send_response(_Recorder.status)
        self.end_headers()

    def log_message(self, *args):
        pass


class NotifyChatTest(unittest.TestCase):
    def setUp(self):
        _Recorder.received = []
        _Recorder.status = 200
        self.server = http.server.HTTPServer(("127.0.0.1", 0), _Recorder)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/hook"
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def env(self, **extra):
        base = {"CHAT_WEBHOOK_URL": self.url, "SUREFIRE_DIR": self.tmp.name}
        base.update(extra)
        return base

    def test_provider_detection(self):
        self.assertEqual(notify_chat.detect_provider("https://hooks.slack.com/services/T/B/x"), "slack")
        self.assertEqual(notify_chat.detect_provider("https://acme.webhook.office.com/webhookb2/x"), "teams")
        self.assertEqual(notify_chat.detect_provider("https://prod-1.westus.logic.azure.com/workflows/x"), "teams")
        self.assertEqual(notify_chat.detect_provider("https://hooks.slack.com/x", "teams"), "teams")
        self.assertEqual(notify_chat.detect_provider("https://x.logic.azure.com/y", "slack"), "slack")

    def test_no_webhook_means_silent_success(self):
        self.assertEqual(notify_chat.main({"SUREFIRE_DIR": self.tmp.name}), 0)
        self.assertEqual(_Recorder.received, [])

    def test_green_run_is_silent_by_default(self):
        write_report(self.tmp.name, TESTCASE_PASS)
        self.assertEqual(notify_chat.main(self.env()), 0)
        self.assertEqual(_Recorder.received, [])

    def test_green_run_sends_when_notify_on_always(self):
        write_report(self.tmp.name, TESTCASE_PASS)
        notify_chat.main(self.env(NOTIFY_ON="always"))
        self.assertEqual(len(_Recorder.received), 1)
        self.assertIn("All 1 tests passed", _Recorder.received[0]["text"])

    def test_failure_sends_slack_message_listing_failed_test(self):
        write_report(self.tmp.name, TESTCASE_PASS, TESTCASE_FAIL, TESTCASE_SKIP)
        notify_chat.main(self.env(REPORT_URL="https://reports.example/r"))
        payload = _Recorder.received[0]
        self.assertIn("1 of 3 tests failed", payload["text"])
        body = json.dumps(payload)
        self.assertIn("com.x.LoginTest.bad", body)
        self.assertIn("https://reports.example/r", body)

    def test_teams_payload_is_an_adaptive_card_message(self):
        write_report(self.tmp.name, TESTCASE_FAIL)
        notify_chat.main(self.env(CHAT_PROVIDER="teams"))
        payload = _Recorder.received[0]
        self.assertEqual(payload["type"], "message")
        att = payload["attachments"][0]
        self.assertEqual(att["contentType"], "application/vnd.microsoft.card.adaptive")
        self.assertEqual(att["content"]["type"], "AdaptiveCard")

    def test_zero_parsed_tests_is_reported_as_failure_not_green(self):
        notify_chat.main(self.env())  # empty surefire dir
        self.assertIn("No test results", _Recorder.received[0]["text"])

    def test_failed_pipeline_verdict_overrides_all_green_tests(self):
        write_report(self.tmp.name, TESTCASE_PASS)
        notify_chat.main(self.env(PIPELINE_RESULT="failure"))
        self.assertIn("Pipeline finished with a failure", _Recorder.received[0]["text"])

    def test_long_failure_list_is_truncated(self):
        cases = [f'<testcase classname="com.x.T" name="t{i}"><failure/></testcase>' for i in range(25)]
        write_report(self.tmp.name, *cases)
        notify_chat.main(self.env(MAX_FAILURES_LISTED="5"))
        body = json.dumps(_Recorder.received[0])
        self.assertIn("and 20 more", body)

    def test_delivery_failure_never_fails_the_pipeline(self):
        write_report(self.tmp.name, TESTCASE_FAIL)
        _Recorder.status = 404  # 4xx: no retry, no exception escapes
        self.assertEqual(notify_chat.main(self.env()), 0)

    def test_ci_context_detection(self):
        gh = notify_chat.ci_context({"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "o/r",
                                     "GITHUB_RUN_ID": "42", "GITHUB_REF_NAME": "main", "GITHUB_SHA": "abc"})
        self.assertEqual(gh["run_url"], "https://github.com/o/r/actions/runs/42")
        gl = notify_chat.ci_context({"GITLAB_CI": "true", "CI_PIPELINE_URL": "https://gl/p/1"})
        self.assertEqual(gl["ci"], "GitLab CI")
        jk = notify_chat.ci_context({"JENKINS_URL": "https://j/", "BUILD_URL": "https://j/job/x/7/"})
        self.assertEqual(jk["run_url"], "https://j/job/x/7/")


if __name__ == "__main__":
    unittest.main()
