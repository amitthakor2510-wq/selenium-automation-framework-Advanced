"""Server-side pass-rate history: one entry per distinct run, tolerant of bad or unwritable storage."""
import json
import os
import unittest

from tests import _helpers as h
import results_history as rh


def _sf(total=10, passed=8, failed=2, skipped=0):
    return {"total": total, "passed": passed, "failed": failed, "skipped": skipped}


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self._t = h.tmpdir()
        self.root = self._t.name
        self.data = os.path.join(self.root, "data")
        self._old = os.environ.get("DASHBOARD_DATA_DIR")
        os.environ["DASHBOARD_DATA_DIR"] = self.data
        self.reports = os.path.join(self.root, "target", "surefire-reports")
        os.makedirs(self.reports)
        self.report = os.path.join(self.reports, "TEST-a.xml")
        self._touch(1_800_000_000)

    def tearDown(self):
        if self._old is None:
            os.environ.pop("DASHBOARD_DATA_DIR", None)
        else:
            os.environ["DASHBOARD_DATA_DIR"] = self._old
        self._t.cleanup()

    def _touch(self, when):
        h.write(self.report, "<testsuite/>")
        os.utime(self.report, (when, when))

    def test_records_a_run_stamped_with_the_report_time(self):
        hist = rh.record(self.root, _sf())
        self.assertEqual(len(hist), 1)
        self.assertEqual(hist[0]["t"], 1_800_000_000)
        self.assertEqual(hist[0]["rate"], 80.0)
        self.assertEqual(oct(os.stat(rh.history_path()).st_mode & 0o777), "0o600")

    def test_polling_the_same_run_adds_nothing(self):
        for _ in range(5):
            rh.record(self.root, _sf())
        self.assertEqual(len(rh.read()), 1)

    def test_rerun_with_identical_counts_is_a_new_entry(self):
        rh.record(self.root, _sf())
        self._touch(1_800_000_500)
        self.assertEqual(len(rh.record(self.root, _sf())), 2)

    def test_changed_results_are_a_new_entry(self):
        rh.record(self.root, _sf())
        self._touch(1_800_000_500)
        hist = rh.record(self.root, _sf(passed=10, failed=0))
        self.assertEqual([e["rate"] for e in hist], [80.0, 100.0])

    def test_no_results_records_nothing(self):
        self.assertEqual(rh.record(self.root, _sf(0, 0, 0, 0)), [])
        self.assertFalse(os.path.exists(rh.history_path()))

    def test_capped_at_max_entries(self):
        for i in range(rh.MAX_ENTRIES + 7):
            self._touch(1_800_000_000 + i)
            rh.record(self.root, _sf())
        hist = rh.read()
        self.assertEqual(len(hist), rh.MAX_ENTRIES)
        self.assertEqual(hist[-1]["t"], 1_800_000_000 + rh.MAX_ENTRIES + 6)

    def test_corrupt_file_is_ignored_and_replaced(self):
        os.makedirs(self.data)
        h.write(rh.history_path(), "{not json")
        self.assertEqual(rh.read(), [])
        self.assertEqual(len(rh.record(self.root, _sf())), 1)

    def test_invalid_entries_are_dropped_on_read(self):
        os.makedirs(self.data)
        good = {"t": 5, "sig": "1/1/0/0", "rate": 100.0}
        h.write(rh.history_path(), json.dumps([good, {"t": "x"}, {"t": 1, "sig": "s", "rate": 999}, 7, None]))
        self.assertEqual(rh.read(), [good])

    def test_unwritable_data_dir_never_raises(self):
        def boom(_entries):
            raise PermissionError("read-only file system")
        original = rh._write
        rh._write = boom
        try:
            hist = rh.record(self.root, _sf())
        finally:
            rh._write = original
        self.assertEqual(len(hist), 1)  # served from memory this time, nothing raised


if __name__ == "__main__":
    unittest.main()
