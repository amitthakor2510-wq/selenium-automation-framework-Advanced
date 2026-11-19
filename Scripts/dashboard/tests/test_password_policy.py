"""A short DASHBOARD_PASSWORD is only tolerated on loopback; beyond this machine it is refused."""
import unittest

import dashboard_server as ds


class PasswordPolicy(unittest.TestCase):
    def test_short_password_allowed_on_loopback(self):
        for host in ("127.0.0.1", "localhost", "::1"):
            self.assertFalse(ds._password_too_weak_for_host("1234", host), host)

    def test_short_password_refused_on_other_interfaces(self):
        for host in ("0.0.0.0", "192.168.1.10", "::"):
            self.assertTrue(ds._password_too_weak_for_host("1234", host), host)

    def test_minimum_length_boundary(self):
        too_short = "x" * (ds._MIN_PASSWORD_LENGTH - 1)
        long_enough = "x" * ds._MIN_PASSWORD_LENGTH
        self.assertTrue(ds._password_too_weak_for_host(too_short, "0.0.0.0"))
        self.assertFalse(ds._password_too_weak_for_host(long_enough, "0.0.0.0"))


if __name__ == "__main__":
    unittest.main()
