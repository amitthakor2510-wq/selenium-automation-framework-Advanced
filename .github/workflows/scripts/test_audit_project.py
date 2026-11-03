"""
Integration test for check #10 of Scripts/audit-project.sh ("JUnit 5 tests wired into the
unit-tests profile"). Builds a throw-away mini repo, runs the real script with --quick and asserts
that an unwired JUnit 5 test is reported (exit 1) while a wired one is accepted (exit 0).
Without this, a regex/awk tweak could silently turn the check into a no-op.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
AUDIT = REPO_ROOT / "Scripts" / "audit-project.sh"

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project>
  <profiles>
    <profile>
      <id>unit-tests</id>
      <build><plugins><plugin><configuration>
        <includes>
          <include>**/core/data/**/*Test.java</include>
          <include>**/core/utils/*Test.java</include>
        </includes>
      </configuration></plugin></plugins></build>
    </profile>
  </profiles>
</project>
"""

TEST_SRC = """package {pkg};

import org.junit.jupiter.api.Test;

class {name} {{
    @Test
    void works() {{ }}
}}
"""


def write_test(root, pkg, name):
    folder = root / "src" / "test" / "java" / Path(*pkg.split("."))
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"{name}.java").write_text(TEST_SRC.format(pkg=pkg, name=name), encoding="utf-8")


@unittest.skipUnless(shutil.which("bash"), "bash not available")
class AuditUnwiredTestCheck(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "Scripts").mkdir()
        shutil.copy(AUDIT, self.root / "Scripts" / "audit-project.sh")
        (self.root / "pom.xml").write_text(POM, encoding="utf-8")
        (self.root / "src" / "test" / "resources").mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def run_audit(self):
        return subprocess.run(
            ["bash", str(self.root / "Scripts" / "audit-project.sh"), "--quick"],
            capture_output=True, text=True, timeout=60,
        )

    def test_wired_tests_pass(self):
        write_test(self.root, "com.automation.core.data", "RowTest")
        write_test(self.root, "com.automation.core.utils", "StringsTest")
        result = self.run_audit()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("every JUnit 5 *Test.java is picked up", result.stdout)

    def test_unwired_test_is_reported(self):
        write_test(self.root, "com.automation.core.data", "RowTest")
        write_test(self.root, "com.automation.core.orphan", "OrphanTest")
        result = self.run_audit()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("OrphanTest.java", result.stdout)
        self.assertIn("never run", result.stdout)

    def test_testng_only_class_is_ignored(self):
        # A *Test.java that is not JUnit 5 is not this check's business.
        folder = self.root / "src" / "test" / "java" / "com" / "automation" / "sites" / "x"
        folder.mkdir(parents=True)
        (folder / "BrowserTest.java").write_text(
            "package com.automation.sites.x;\n\nimport org.testng.annotations.Test;\n\n"
            "class BrowserTest {\n    @Test\n    void run() { }\n}\n", encoding="utf-8")
        result = self.run_audit()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
