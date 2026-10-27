import os
import unittest

from tests import _helpers as h
from specs import spec_store as st


class StoreTests(unittest.TestCase):
    def setUp(self):
        self._t = h.tmpdir()
        os.environ["DASHBOARD_DATA_DIR"] = self._t.name

    def tearDown(self):
        os.environ.pop("DASHBOARD_DATA_DIR", None)
        self._t.cleanup()

    def test_save_versions_and_load(self):
        a = st.save(h.ui_spec(), "alice", h.CTX)
        b = st.save(h.ui_spec(), "bob", h.CTX)
        self.assertEqual((a["slug"], a["version"], b["version"]), ("login-smoke", 1, 2))
        self.assertEqual(st.load("login-smoke")["meta"]["savedBy"], "bob")
        self.assertEqual(st.load("login-smoke", 1)["meta"]["savedBy"], "alice")
        self.assertEqual(st.list_templates()[0]["versions"], [1, 2])

    def test_invalid_spec_not_saved(self):
        bad = h.ui_spec()
        bad["details"]["steps"][2]["data"] = "plaintext-password"
        with self.assertRaises(ValueError):
            st.save(bad, "alice", h.CTX)
        self.assertEqual(st.list_templates(), [])

    def test_slug_is_path_safe(self):
        for bad in ("../x", "a/b", "", "A", "x" * 80):
            with self.assertRaises((ValueError, FileNotFoundError)):
                st.load(bad)

    def test_delete_and_missing(self):
        st.save(h.ui_spec(), "alice", h.CTX)
        st.delete("login-smoke")
        self.assertEqual(st.list_templates(), [])
        with self.assertRaises(FileNotFoundError):
            st.load("login-smoke")

    def test_version_pruning(self):
        old = st.MAX_VERSIONS
        st.MAX_VERSIONS = 3
        try:
            for _ in range(5):
                st.save(h.ui_spec(), "a", h.CTX)
            self.assertEqual(st.list_templates()[0]["versions"], [3, 4, 5])
        finally:
            st.MAX_VERSIONS = old


if __name__ == "__main__":
    unittest.main()
