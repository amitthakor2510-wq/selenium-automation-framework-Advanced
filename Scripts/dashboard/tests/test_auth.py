import os
import unittest

from tests import _helpers as h
from auth import secrets_store as ss, users as au


class UserTests(unittest.TestCase):
    def setUp(self):
        self._t = h.tmpdir()
        self.path = os.path.join(self._t.name, "users.json")

    def tearDown(self):
        self._t.cleanup()

    def test_add_verify_roles(self):
        au.add_user("alice", "tester", "correct horse battery", self.path)
        self.assertEqual(au.verify("alice", "correct horse battery", self.path), "tester")
        self.assertIsNone(au.verify("alice", "wrong password!!", self.path))
        self.assertIsNone(au.verify("nobody", "correct horse battery", self.path))

    def test_file_has_no_plaintext_and_is_private(self):
        au.add_user("bob", "lead", "a-long-password-1", self.path)
        text = h.read(self.path)
        self.assertNotIn("a-long-password-1", text)
        self.assertEqual(oct(os.stat(self.path).st_mode & 0o777), "0o600")

    def test_validation(self):
        for name, role, pw in (("Bad Name", "tester", "x" * 12), ("dashboard", "admin", "x" * 12), ("ok", "root", "x" * 12), ("ok", "tester", "short")):
            with self.assertRaises(ValueError):
                au.add_user(name, role, pw, self.path)

    def test_remove_invalidates_cached_login(self):
        au.add_user("carol", "tester", "another-long-pass", self.path)
        self.assertEqual(au.verify("carol", "another-long-pass", self.path), "tester")
        au.remove_user("carol", self.path)
        self.assertIsNone(au.verify("carol", "another-long-pass", self.path))

    def test_permissions(self):
        self.assertTrue(au.allowed("tester", "run.start"))
        self.assertFalse(au.allowed("tester", "ci.trigger"))
        self.assertFalse(au.allowed("tester", "template.delete"))
        self.assertTrue(au.allowed("lead", "ci.trigger"))
        self.assertFalse(au.allowed("lead", "secrets.manage"))
        self.assertTrue(au.allowed("admin", "secrets.manage"))
        self.assertFalse(au.allowed("admin", "no.such.permission"))
        self.assertFalse(au.allowed("ghost", "run.start"))


class SecretsTests(unittest.TestCase):
    def setUp(self):
        from cryptography.fernet import Fernet
        self._t = h.tmpdir()
        self._env = dict(os.environ)
        os.environ["DASHBOARD_SECRETS_FILE"] = os.path.join(self._t.name, "s.json")
        os.environ["DASHBOARD_SECRET_KEY"] = Fernet.generate_key().decode()

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env)
        self._t.cleanup()

    def test_roundtrip_and_encrypted_at_rest(self):
        ss.set_secret("DB_PW", "p@ss-w0rd!")
        self.assertEqual(ss.get_secret("DB_PW"), "p@ss-w0rd!")
        self.assertNotIn("p@ss-w0rd!", h.read(os.environ["DASHBOARD_SECRETS_FILE"]))
        self.assertEqual(ss.list_names(), ["DB_PW"])
        self.assertEqual(ss.resolve("${secret:DB_PW}"), "p@ss-w0rd!")
        ss.delete_secret("DB_PW")
        with self.assertRaises(KeyError):
            ss.get_secret("DB_PW")

    def test_fails_closed_without_key(self):
        os.environ.pop("DASHBOARD_SECRET_KEY")
        self.assertFalse(ss.available())
        with self.assertRaises(ss.SecretsUnavailable):
            ss.set_secret("X", "y")

    def test_bad_key(self):
        os.environ["DASHBOARD_SECRET_KEY"] = "not-a-key"
        with self.assertRaises(ss.SecretsUnavailable):
            ss.set_secret("X", "y")

    def test_names_and_refs(self):
        with self.assertRaises(ValueError):
            ss.set_secret("../x", "y")
        self.assertEqual(ss.parse_ref("${secret:A_1}"), ("secret", "A_1"))
        self.assertIsNone(ss.parse_ref("secret:A"))
        self.assertIsNone(ss.parse_ref("${secret:a b}"))
        self.assertEqual(ss.resolve("${env:HOME_X}", {"HOME_X": "v"}), "v")
        with self.assertRaises(KeyError):
            ss.resolve("${env:MISSING_ONE}", {})


if __name__ == "__main__":
    unittest.main()
