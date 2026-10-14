"""
Stdlib-only tests for Scripts/shard-tests.py (duration-balanced suite sharding).
Run with the other CI helper tests:

    python3 -m unittest discover -s .github/workflows/scripts -p 'test_*.py'
"""
import importlib.util
import json
import os
import tempfile
import unittest
import xml.etree.ElementTree as ET

_HERE = os.path.dirname(os.path.abspath(__file__))
_SCRIPT = os.path.normpath(os.path.join(_HERE, "..", "..", "..", "Scripts", "shard-tests.py"))
_spec = importlib.util.spec_from_file_location("shard_tests", _SCRIPT)
shard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shard)

PKG = "com.acme.tests"


def java(name, body="@Test public void t() {}", abstract=False, junit5=False, package=PKG):
    imp = "import org.junit.jupiter.api.Test;" if junit5 else "import org.testng.annotations.Test;"
    mod = "abstract " if abstract else ""
    return f"package {package};\n{imp}\npublic {mod}class {name} {{\n  {body}\n}}\n"


SUITE = """<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="Acme Smoke" parallel="classes" thread-count="3">
  <listeners><listener class-name="com.acme.RetryListener"/></listeners>
  <test name="Acme Tests" preserve-order="true">
    <groups><run><include name="smoke"/></run></groups>
    <packages><package name="{pkg}"/></packages>
  </test>
</suite>
"""


class ShardTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.src = os.path.join(self.root, "src")
        self.pkgdir = os.path.join(self.src, *PKG.split("."))
        os.makedirs(self.pkgdir)
        self.suite = os.path.join(self.root, "acme-smoke.xml")
        self.out = os.path.join(self.root, "out")
        self.durations = os.path.join(self.root, "durations.json")

    def tearDown(self):
        self.tmp.cleanup()

    def add_class(self, name, **kw):
        with open(os.path.join(self.pkgdir, f"{name}.java"), "w", encoding="utf-8") as f:
            f.write(java(name, **kw))

    def write_suite(self, text=None):
        with open(self.suite, "w", encoding="utf-8") as f:
            f.write(text or SUITE.format(pkg=PKG))

    def write_history(self, classes, key="acme-smoke"):
        with open(self.durations, "w", encoding="utf-8") as f:
            json.dump({"version": 1, "suites": {key: classes}}, f)

    def run_cli(self, *argv):
        return shard.main(list(argv))

    def plan(self, shards, index=None):
        args = ["plan", "--suite", self.suite, "--shards", str(shards), "--source", self.src,
                "--durations", self.durations, "--out-dir", self.out]
        if index:
            args += ["--index", str(index)]
        self.assertEqual(self.run_cli(*args), 0)

    def shard_classes(self, i, n):
        path = os.path.join(self.out, f"acme-smoke-shard-{i}-of-{n}.xml")
        root = ET.parse(path).getroot()
        return sorted(c.get("name").rsplit(".", 1)[1] for c in root.iter("class")), root


class PackageScanTest(ShardTestBase):
    def test_only_concrete_testng_classes_are_found(self):
        self.add_class("LoginTest")
        self.add_class("CartTest")
        self.add_class("BaseTest", abstract=True)
        self.add_class("UnitOnlyTest", junit5=True)
        self.add_class("PageHelper", body="void helper() {}")
        found = shard.scan_package(self.src, PKG, recursive=False)
        self.assertEqual(found, [f"{PKG}.CartTest", f"{PKG}.LoginTest"])

    def test_sub_packages_only_with_dot_star(self):
        self.add_class("TopTest")
        sub = os.path.join(self.pkgdir, "deep")
        os.makedirs(sub)
        with open(os.path.join(sub, "DeepTest.java"), "w", encoding="utf-8") as f:
            f.write(java("DeepTest", package=PKG + ".deep"))
        self.assertEqual(len(shard.scan_package(self.src, PKG, recursive=False)), 1)
        self.assertEqual(len(shard.scan_package(self.src, PKG, recursive=True)), 2)

    def test_missing_package_is_a_clear_error(self):
        with self.assertRaises(shard.ShardError):
            shard.scan_package(self.src, "does.not.exist", False)


class PlanTest(ShardTestBase):
    def setUp(self):
        super().setUp()
        for n in ("AaaTest", "BbbTest", "CccTest", "DddTest"):
            self.add_class(n)
        self.write_suite()

    def test_every_class_runs_exactly_once_across_shards(self):
        self.plan(3)
        seen = []
        for i in (1, 2, 3):
            names, _ = self.shard_classes(i, 3)
            seen += names
        self.assertEqual(sorted(seen), ["AaaTest", "BbbTest", "CccTest", "DddTest"])

    def test_duration_history_balances_the_shards(self):
        self.write_history({f"{PKG}.AaaTest": 100, f"{PKG}.BbbTest": 60,
                            f"{PKG}.CccTest": 50, f"{PKG}.DddTest": 40})
        self.plan(2)
        s1, _ = self.shard_classes(1, 2)
        s2, _ = self.shard_classes(2, 2)
        # LPT: A(100)->1, B(60)->2, C(50)->2, D(40)->1  => 140 vs 110
        self.assertEqual(s1, ["AaaTest", "DddTest"])
        self.assertEqual(s2, ["BbbTest", "CccTest"])

    def test_one_slow_class_gets_a_shard_to_itself(self):
        self.write_history({f"{PKG}.AaaTest": 300, f"{PKG}.BbbTest": 10,
                            f"{PKG}.CccTest": 10, f"{PKG}.DddTest": 10})
        self.plan(2)
        self.assertEqual(self.shard_classes(1, 2)[0], ["AaaTest"])

    def test_unknown_classes_get_the_median_of_known(self):
        w, known = shard.weights_for(["a", "b", "c"], {"a": 10, "b": 30})
        self.assertEqual(known, 2)
        self.assertEqual(w["c"], 20)

    def test_plan_is_deterministic(self):
        self.write_history({f"{PKG}.AaaTest": 5, f"{PKG}.BbbTest": 5, f"{PKG}.CccTest": 5, f"{PKG}.DddTest": 5})
        self.plan(2)
        first = [self.shard_classes(i, 2)[0] for i in (1, 2)]
        self.plan(2)
        self.assertEqual(first, [self.shard_classes(i, 2)[0] for i in (1, 2)])

    def test_groups_listeners_and_parallel_settings_survive(self):
        self.plan(2, index=1)
        _, root = self.shard_classes(1, 2)
        self.assertEqual(root.get("parallel"), "classes")
        self.assertEqual(root.get("thread-count"), "3")
        self.assertIn("[shard 1/2]", root.get("name"))
        self.assertIsNotNone(root.find("listeners/listener"))
        self.assertEqual(root.find("test/groups/run/include").get("name"), "smoke")
        self.assertIsNone(root.find("test/packages"))

    def test_generated_file_keeps_the_testng_doctype(self):
        self.plan(2, index=2)
        with open(os.path.join(self.out, "acme-smoke-shard-2-of-2.xml"), encoding="utf-8") as f:
            self.assertTrue(f.read().startswith("<!DOCTYPE suite SYSTEM"))

    def test_more_shards_than_classes_is_refused_not_an_empty_shard(self):
        rc = self.run_cli("plan", "--suite", self.suite, "--shards", "9", "--source", self.src,
                          "--durations", self.durations, "--out-dir", self.out)
        self.assertEqual(rc, 2)

    def test_index_out_of_range_is_refused(self):
        rc = self.run_cli("plan", "--suite", self.suite, "--shards", "2", "--index", "3",
                          "--source", self.src, "--out-dir", self.out)
        self.assertEqual(rc, 2)

    def test_missing_or_corrupt_history_falls_back_to_class_count(self):
        with open(self.durations, "w", encoding="utf-8") as f:
            f.write("{not json")
        self.plan(2)
        self.assertEqual(len(self.shard_classes(1, 2)[0]), 2)

    def test_explicit_class_list_keeps_method_filters(self):
        self.write_suite("""<suite name="X"><test name="T"><classes>
            <class name="com.acme.tests.AaaTest"><methods><include name="t"/></methods></class>
            <class name="com.acme.tests.BbbTest"/></classes></test></suite>""")
        self.plan(2)
        with_methods = [i for i in (1, 2) if ET.parse(os.path.join(
            self.out, f"acme-smoke-shard-{i}-of-2.xml")).getroot().find(".//methods/include") is not None]
        self.assertEqual(len(with_methods), 1)

    def test_suite_files_are_rejected_loudly(self):
        self.write_suite('<suite name="X"><suite-files><suite-file path="a.xml"/></suite-files></suite>')
        self.assertEqual(self.run_cli("count", "--suite", self.suite, "--max", "2", "--source", self.src), 2)

    def test_two_test_elements_are_each_sharded_and_empty_ones_dropped(self):
        self.write_suite("""<suite name="X">
            <test name="T1"><classes><class name="com.acme.tests.AaaTest"/></classes></test>
            <test name="T2"><classes><class name="com.acme.tests.BbbTest"/></classes></test></suite>""")
        self.plan(2)
        for i in (1, 2):
            _, root = self.shard_classes(i, 2)
            self.assertEqual(len(root.findall("test")), 1)


class CountTest(ShardTestBase):
    def test_count_caps_at_class_count(self):
        self.add_class("OneTest")
        self.add_class("TwoTest")
        self.write_suite()
        import io
        import contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.run_cli("count", "--suite", self.suite, "--max", "8", "--source", self.src)
        self.assertEqual(buf.getvalue().strip(), "2")


class HistoryTest(ShardTestBase):
    def write_report(self, d, classname, *times):
        cases = "".join(f'<testcase classname="{classname}" name="m{i}" time="{t}"/>' for i, t in enumerate(times))
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, f"TEST-{classname}.xml"), "w", encoding="utf-8") as f:
            f.write(f'<testsuite name="{classname}">{cases}</testsuite>')

    def test_observe_sums_method_times_per_class(self):
        reports = os.path.join(self.root, "reports")
        self.write_report(reports, "com.acme.A", 1.5, 2.5)
        out = os.path.join(self.root, "obs.json")
        self.assertEqual(self.run_cli("observe", "--reports", reports, "--suite-key", "k", "--out", out), 0)
        with open(out, encoding="utf-8") as f:
            self.assertEqual(json.load(f)["classes"], {"com.acme.A": 4.0})

    def test_observe_writes_nothing_for_an_empty_run(self):
        out = os.path.join(self.root, "obs.json")
        self.run_cli("observe", "--reports", os.path.join(self.root, "none"), "--suite-key", "k", "--out", out)
        self.assertFalse(os.path.exists(out))

    def test_merge_uses_a_moving_average_so_one_outlier_does_not_dominate(self):
        self.write_history({"com.acme.A": 100}, key="k")
        obs = os.path.join(self.root, "obs.json")
        with open(obs, "w", encoding="utf-8") as f:
            json.dump({"version": 1, "suite": "k", "classes": {"com.acme.A": 200, "com.acme.B": 7}}, f)
        self.assertEqual(self.run_cli("merge", "--durations", self.durations, "--inputs", obs, "--alpha", "0.5"), 0)
        with open(self.durations, encoding="utf-8") as f:
            data = json.load(f)["suites"]["k"]
        self.assertEqual(data["com.acme.A"], 150.0)
        self.assertEqual(data["com.acme.B"], 7.0)

    def test_merge_skips_junk_inputs_and_leaves_file_alone(self):
        junk = os.path.join(self.root, "junk.json")
        with open(junk, "w", encoding="utf-8") as f:
            f.write("[]")
        self.assertEqual(self.run_cli("merge", "--durations", self.durations, "--inputs", junk,
                                      os.path.join(self.root, "missing.json")), 0)
        self.assertFalse(os.path.exists(self.durations))


if __name__ == "__main__":
    unittest.main()
