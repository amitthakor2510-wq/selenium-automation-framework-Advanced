import os
import stat
import time
import unittest

from tests import _helpers as h
from auth import secrets_store
from runner import runner_service as rs, workspace
from runner.queue import QueueFull, RunQueue


FAKE_MVN = """#!/bin/sh
echo "ARGS: $@"
echo "SECRET_SEEN: $WIZARD_SECRET_SAUCE_PW"
echo "LEAK_CHECK: ${DASHBOARD_PASSWORD:-absent}"
if [ -n "$FAKE_SLEEP" ]; then sleep "$FAKE_SLEEP"; fi
mkdir -p target/extent-reports target/allure-results
echo hi > target/extent-reports/index.html
exit ${FAKE_EXIT:-0}
"""


class RunnerBase(unittest.TestCase):
    def setUp(self):
        self._t = h.tmpdir()
        root = self._t.name
        self.repo = os.path.join(root, "repo")
        os.makedirs(os.path.join(self.repo, "testng-suites"))
        for fn in ("demoqa-perf.xml", "demoqa-accessibility.xml"):
            h.write(os.path.join(self.repo, "testng-suites", fn))
        self.bin = os.path.join(root, "bin")
        os.makedirs(self.bin)
        self.mvn = os.path.join(self.bin, "mvn")
        with open(self.mvn, "w") as f:
            f.write(FAKE_MVN)
        os.chmod(self.mvn, os.stat(self.mvn).st_mode | stat.S_IEXEC)
        self._env = dict(os.environ)
        os.environ.update({"PATH": self.bin + os.pathsep + os.environ["PATH"], "DASHBOARD_DATA_DIR": os.path.join(root, "data"),
                           "DASHBOARD_RUNNER_ENABLED": "1", "DASHBOARD_PASSWORD": "dash-pw-secret",
                           "DASHBOARD_RUN_ENV_PASSTHROUGH": "FAKE_SLEEP,FAKE_EXIT"})
        os.environ.pop("FAKE_SLEEP", None)
        os.environ.pop("FAKE_EXIT", None)
        self.svc = rs.RunnerService(self.repo, lambda: h.CTX)

    def tearDown(self):
        self.svc.queue.shutdown()
        os.environ.clear()
        os.environ.update(self._env)
        self._t.cleanup()

    def wait(self, run_id, states=("passed", "failed", "error", "cancelled", "timeout"), secs=15):
        end = time.time() + secs
        while time.time() < end:
            st = self.svc.status(run_id)
            if st["state"] in states:
                return st
            time.sleep(0.05)
        self.fail(f"run stuck in {self.svc.status(run_id)['state']}")


class RunnerTests(RunnerBase):
    def test_disabled_by_default(self):
        os.environ.pop("DASHBOARD_RUNNER_ENABLED")
        with self.assertRaises(rs.RunnerDisabled):
            self.svc.start(h.ui_spec(), "alice")

    def test_invalid_spec_never_queued(self):
        bad = h.ui_spec()
        bad["details"]["steps"] = []
        with self.assertRaises(ValueError):
            self.svc.start(bad, "alice")
        self.assertEqual(workspace.list_runs(), [])

    def test_missing_secret_is_a_clear_error(self):
        os.environ.pop("DASHBOARD_SECRET_KEY", None)
        st = self.wait(self.svc.start(h.ui_spec(), "alice"))
        self.assertEqual(st["state"], "error")
        self.assertIn("SAUCE_PW", st["message"])

    def test_full_run_with_secret(self):
        from cryptography.fernet import Fernet
        os.environ["DASHBOARD_SECRET_KEY"] = Fernet.generate_key().decode()
        os.environ["DASHBOARD_SECRETS_FILE"] = os.path.join(self._t.name, "s.json")
        secrets_store.set_secret("SAUCE_PW", "s3cr3t-value")
        st = self.wait(self.svc.start(h.ui_spec(), "alice"))
        self.assertEqual(st["state"], "passed", st)
        d = workspace.path_for(st["id"])
        log = h.read(os.path.join(d, "run.log"))
        self.assertIn("ARGS: -B -ntp test -DsuiteXmlFile=", log)
        self.assertIn("SECRET_SEEN: ***", log)           # the child got it, the log doesn't show it
        self.assertNotIn("s3cr3t-value", log)
        self.assertIn("LEAK_CHECK: absent", log)         # dashboard's own env stays out of the run
        for fn in ("spec.json", "properties.generated", "status.json", "summary.html", "report-link.txt"):
            self.assertNotIn("s3cr3t-value", h.read(os.path.join(d, fn)), fn)
        self.assertTrue(os.path.isfile(os.path.join(d, "report", "extent-reports.zip")))
        self.assertEqual(st["reportLink"], f"/api/runs/{st['id']}/files/summary.html")

    def test_failure_exit_code(self):
        os.environ["FAKE_EXIT"] = "3"
        os.environ["DASHBOARD_SECRET_KEY"] = ""
        spec = h.perf_spec()
        st = self.wait(self.svc.start(spec, "alice"))
        self.assertEqual((st["state"], st["exitCode"]), ("failed", 3))

    def test_cancel_running(self):
        os.environ["FAKE_SLEEP"] = "30"
        rid = self.svc.start(h.perf_spec(), "alice")
        self.wait(rid, states=("running",))
        time.sleep(0.3)
        self.assertTrue(self.svc.cancel(rid))
        self.assertEqual(self.wait(rid)["state"], "cancelled")

    def test_timeout(self):
        os.environ["FAKE_SLEEP"] = "30"
        rs.TIMEOUT_UNIT_SECONDS = 0.5
        try:
            rid = self.svc.start(h.perf_spec(), "alice")
            self.assertEqual(self.wait(rid, secs=20)["state"], "timeout")
        finally:
            rs.TIMEOUT_UNIT_SECONDS = 60

    def test_second_run_waits_in_queue_then_cancel_pending(self):
        os.environ["FAKE_SLEEP"] = "30"
        a = self.svc.start(h.perf_spec(), "alice")
        self.wait(a, states=("running",))
        b = self.svc.start(h.perf_spec() | {"name": "Second perf"}, "bob")
        self.assertEqual(self.svc.status(b)["state"], "queued")
        self.assertEqual(self.svc.status(b)["queuePosition"], 1)
        self.assertTrue(self.svc.cancel(b))
        self.assertEqual(self.svc.status(b)["state"], "cancelled")
        self.svc.cancel(a)
        self.wait(a)

    def test_cancel_before_process_starts_is_not_dropped(self):
        """A run a worker has picked up but whose process is not spawned yet must still be cancellable.

        status.json already says "running" at that point, so a UI (or this suite) that cancels as
        soon as it sees "running" used to hit cancel() with no process registered: it returned
        False and the run went ahead. Hold the worker inside build_command() to hit the window
        deterministically instead of racing it.
        """
        import threading
        from runner import allowlist
        reached, release = threading.Event(), threading.Event()
        real = allowlist.build_command

        def slow_build_command(*args, **kwargs):
            reached.set()
            release.wait(10)
            return real(*args, **kwargs)

        allowlist.build_command = slow_build_command
        try:
            os.environ["FAKE_SLEEP"] = "30"
            rid = self.svc.start(h.perf_spec(), "alice")
            self.assertTrue(reached.wait(10), "worker never reached build_command")
            self.assertTrue(self.svc.cancel(rid), "cancel before the process started was dropped")
            release.set()
            st = self.wait(rid, secs=10)
            self.assertEqual(st["state"], "cancelled")
            self.assertNotIn("ARGS:", self.svc.log_tail(rid)["text"], "the cancelled run still started its command")
        finally:
            allowlist.build_command = real
            release.set()

    def test_log_tail_offsets(self):
        rid = self.svc.start(h.perf_spec(), "alice")
        self.wait(rid)
        first = self.svc.log_tail(rid, offset=0)
        self.assertIn("ARGS:", first["text"])
        self.assertEqual(self.svc.log_tail(rid, offset=first["offset"])["text"], "")

    def test_cleanup_keeps_active_and_trims_old(self):
        old_keep = workspace.KEEP_LAST
        workspace.KEEP_LAST = 2
        try:
            ids = []
            for _ in range(4):
                rid = self.svc.start(h.perf_spec(), "alice")
                self.wait(rid)
                ids.append(rid)
                time.sleep(1.05)  # run ids sort by second
            workspace.cleanup()
            left = [r["id"] for r in workspace.list_runs()]
            self.assertEqual(len(left), 2)
            self.assertEqual(set(left), set(ids[-2:]))
        finally:
            workspace.KEEP_LAST = old_keep


class QueueTests(unittest.TestCase):
    def test_fifo_and_limit(self):
        done = []
        q = RunQueue(lambda rid: (time.sleep(0.05), done.append(rid)), max_parallel=1)
        for i in range(5):
            q.submit(f"r{i}")
        time.sleep(0.6)
        q.shutdown()
        self.assertEqual(done, [f"r{i}" for i in range(5)])

    def test_queue_full(self):
        q = RunQueue(lambda rid: time.sleep(5), max_parallel=1)
        try:
            q.submit("first")
            time.sleep(0.1)
            for i in range(20):
                q.submit(f"w{i}")
            with self.assertRaises(QueueFull):
                q.submit("one-too-many")
        finally:
            q.shutdown()

    def test_worker_survives_exception(self):
        seen = []

        def ex(rid):
            seen.append(rid)
            if rid == "boom":
                raise RuntimeError("x")
        q = RunQueue(ex, max_parallel=1)
        # The worker logs the (deliberate) failure; capture it so it neither spams the test output
        # nor goes unchecked.
        with self.assertLogs("runner.queue", level="ERROR") as logs:
            q.submit("boom")
            q.submit("after")
            time.sleep(0.3)
        q.shutdown()
        self.assertTrue(any("boom" in m for m in logs.output))
        self.assertEqual(seen, ["boom", "after"])


if __name__ == "__main__":
    unittest.main()
