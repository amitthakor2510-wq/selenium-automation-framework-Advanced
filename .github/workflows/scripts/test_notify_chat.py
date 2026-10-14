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

    # ------------------------------------------------------------------ Telegram
    def tg_env(self, **extra):
        # TELEGRAM_API_BASE points the Bot API call at the throwaway local server.
        base = {"TELEGRAM_BOT_TOKEN": "123:SECRET-TOKEN", "TELEGRAM_CHAT_ID": "-1001",
                "TELEGRAM_API_BASE": f"http://127.0.0.1:{self.server.server_port}",
                "SUREFIRE_DIR": self.tmp.name}
        base.update(extra)
        return base

    def test_telegram_url_never_double_slashes_and_uses_bot_prefix(self):
        self.assertEqual(notify_chat.telegram_url("1:A", "https://x.test/"), "https://x.test/bot1:A/sendMessage")
        self.assertEqual(notify_chat.telegram_url("1:A"), "https://api.telegram.org/bot1:A/sendMessage")

    def test_telegram_failure_message_is_sent_with_buttons(self):
        write_report(self.tmp.name, TESTCASE_PASS, TESTCASE_FAIL)
        notify_chat.main(self.tg_env(REPORT_URL="https://reports.example/r", PIPELINE_RESULT="failure"))
        self.assertEqual(len(_Recorder.received), 1)
        p = _Recorder.received[0]
        self.assertEqual(p["chat_id"], "-1001")
        self.assertEqual(p["parse_mode"], "HTML")
        self.assertIn("1 of 2 tests failed", p["text"])
        self.assertIn("<code>com.x.LoginTest.bad</code>", p["text"])
        urls = [b["url"] for b in p["reply_markup"]["inline_keyboard"][0]]
        self.assertIn("https://reports.example/r", urls)

    def test_telegram_green_run_is_silent_unless_notify_on_always(self):
        write_report(self.tmp.name, TESTCASE_PASS)
        notify_chat.main(self.tg_env())
        self.assertEqual(_Recorder.received, [])
        notify_chat.main(self.tg_env(NOTIFY_ON="always"))
        self.assertEqual(len(_Recorder.received), 1)

    def test_telegram_escapes_html_in_test_names(self):
        bad = '<testcase classname="com.x.T" name="a&lt;b&gt;&amp;c"><failure/></testcase>'
        write_report(self.tmp.name, bad)
        notify_chat.main(self.tg_env())
        text = _Recorder.received[0]["text"]
        self.assertNotIn("<b>c", text)          # raw name must not inject tags
        self.assertIn("&lt;", text)

    def test_telegram_message_respects_4096_limit(self):
        cases = [f'<testcase classname="com.x.{"L" * 200}" name="t{i}"><failure/></testcase>' for i in range(60)]
        write_report(self.tmp.name, *cases)
        notify_chat.main(self.tg_env(MAX_FAILURES_LISTED="60"))
        text = _Recorder.received[0]["text"]
        self.assertLessEqual(len(text), notify_chat.TELEGRAM_MAX_CHARS)
        self.assertIn("more", text)             # the cut-off tests are counted, not silently lost
        self.assertEqual(text.count("<code>"), text.count("</code>"))

    def test_telegram_thread_id_is_sent_as_integer(self):
        write_report(self.tmp.name, TESTCASE_FAIL)
        notify_chat.main(self.tg_env(TELEGRAM_THREAD_ID="77"))
        self.assertEqual(_Recorder.received[0]["message_thread_id"], 77)

    def test_telegram_bad_thread_id_is_ignored_not_fatal(self):
        write_report(self.tmp.name, TESTCASE_FAIL)
        self.assertEqual(notify_chat.main(self.tg_env(TELEGRAM_THREAD_ID="abc")), 0)
        self.assertNotIn("message_thread_id", _Recorder.received[0])

    def test_half_configured_telegram_is_skipped(self):
        write_report(self.tmp.name, TESTCASE_FAIL)
        env = self.tg_env()
        del env["TELEGRAM_CHAT_ID"]
        self.assertEqual(notify_chat.main(env), 0)
        self.assertEqual(_Recorder.received, [])

    def test_webhook_and_telegram_both_receive_a_message(self):
        write_report(self.tmp.name, TESTCASE_FAIL)
        env = self.tg_env(CHAT_WEBHOOK_URL=self.url)
        notify_chat.main(env)
        self.assertEqual(len(_Recorder.received), 2)
        self.assertEqual(sum(1 for r in _Recorder.received if "chat_id" in r), 1)

    def test_provider_telegram_ignores_the_webhook(self):
        write_report(self.tmp.name, TESTCASE_FAIL)
        notify_chat.main(self.tg_env(CHAT_WEBHOOK_URL=self.url, CHAT_PROVIDER="telegram"))
        self.assertEqual(len(_Recorder.received), 1)
        self.assertIn("chat_id", _Recorder.received[0])

    def test_telegram_delivery_failure_never_fails_the_pipeline_or_leaks_token(self):
        import io
        import contextlib
        write_report(self.tmp.name, TESTCASE_FAIL)
        _Recorder.status = 401  # revoked bot token: 4xx, no retry
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = notify_chat.main(self.tg_env())
        self.assertEqual(rc, 0)
        self.assertNotIn("SECRET-TOKEN", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
