#!/usr/bin/env python3
"""
Duration-balanced test sharding for the TestNG suites in testng-suites/.

WHY
  CI used to split work only by site x browser. One slow site therefore set the
  length of the whole pipeline while other legs sat idle. This script splits ONE
  suite file into N smaller suite files so that every shard is expected to take
  about the same wall-clock time, using how long each test class took in earlier
  runs (greedy "longest processing time first" bin packing).

  Nothing changes unless you ask for it: shard count defaults to 1, and with one
  shard the pipelines run the original suite file exactly as before.

COMMANDS (stdlib only - no pip install, same convention as notify_chat.py)

  plan     Write the shard suite XML and print its path on stdout (everything
           else goes to stderr, so  SUITE=$(... plan ...)  is safe).
             shard-tests.py plan --suite testng-suites/saucedemo-regression.xml \\
                                 --shards 3 --index 2
           Leave --index off to write every shard and print all paths.

  count    Print how many shards are actually useful: min(--max, class count).
           Used by CI so it never creates an empty shard (an empty shard would
           look like "zero tests" to the test gate).

  observe  Read surefire's TEST-*.xml and write how long each class took.
             shard-tests.py observe --reports target/surefire-reports \\
                                    --suite-key saucedemo-regression --out t.json

  merge    Fold one or more observe files into the committed history file
           (exponential moving average, so one slow run does not wreck the plan).
             shard-tests.py merge --durations testng-suites/shard-durations.json \\
                                  --inputs t1.json t2.json

  learn    observe + merge in one go, for local use after a normal run:
             shard-tests.py learn --suite-key saucedemo-regression

HISTORY FILE  testng-suites/shard-durations.json
  { "version": 1, "suites": { "<suite file name without .xml>": { "<FQCN>": seconds } } }
  Keyed per suite because the same class runs far fewer methods in "smoke" than
  in "regression". A missing/empty file is fine: every class then weighs the
  same and shards are balanced by class count. Classes with no history get the
  median of the known ones.

HOW A SHARD IS BUILT
  The original suite is copied; in every <test>, <packages>/<classes> are
  replaced by an explicit <classes> list holding only that shard's classes.
  <groups>, <listeners>, <parameter>, parallel/thread-count are kept as they are,
  so group filtering (smoke/regression) behaves exactly as in the full suite.
  The plan is deterministic: every CI job runs the same computation on the same
  inputs and gets the same answer, so shards never overlap and never miss a class.

LIMITS (stated plainly)
  - A class is the smallest unit; one huge class cannot be split.
  - <suite-files> and non-class <test> contents are not supported (the script
    exits with an error rather than silently dropping tests).
  - Package scanning is by source file under --source (default src/test/java):
    non-abstract public classes that contain @Test and do not use JUnit 5.
"""
import argparse
import json
import os
import re
import statistics
import sys
import xml.etree.ElementTree as ET

DEFAULT_SOURCE = "src/test/java"
DEFAULT_DURATIONS = "testng-suites/shard-durations.json"
DEFAULT_OUT_DIR = "target/shards"
DOCTYPE = '<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">'
DEFAULT_EMA_ALPHA = 0.5

_PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
_ABSTRACT_RE = re.compile(r"\babstract\s+class\b")
_PUBLIC_CLASS_RE = re.compile(r"\bpublic\s+(?:final\s+)?class\s+(\w+)")


class ShardError(Exception):
    """Anything the user must fix; printed without a traceback."""


def log(msg):
    print(f"shard-tests: {msg}", file=sys.stderr)


# --------------------------------------------------------------------------
# Suite parsing
# --------------------------------------------------------------------------
def parse_suite(path):
    if not os.path.isfile(path):
        raise ShardError(f"suite file not found: {path}")
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    try:
        tree = ET.parse(path, parser=parser)
    except ET.ParseError as e:
        raise ShardError(f"{path} is not valid XML: {e}")
    root = tree.getroot()
    if root.tag != "suite":
        raise ShardError(f"{path}: root element is <{root.tag}>, expected <suite>")
    if root.find("suite-files") is not None:
        raise ShardError(f"{path} uses <suite-files>, which sharding does not support; shard the referenced suites instead")
    return tree


def _is_testng_class(text):
    if "org.junit.jupiter" in text:
        return False
    if "@Test" not in text:
        return False
    return True


def scan_package(source_root, package, recursive):
    """FQCNs of TestNG test classes in `package` (and sub-packages if recursive)."""
    base = os.path.join(source_root, *package.split("."))
    if not os.path.isdir(base):
        raise ShardError(f"package {package} not found under {source_root} (looked in {base})")
    found = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames.sort()
        for fn in sorted(filenames):
            if not fn.endswith(".java"):
                continue
            with open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace") as f:
                text = f.read()
            if not _is_testng_class(text):
                continue
            m_pkg = _PKG_RE.search(text)
            m_cls = _PUBLIC_CLASS_RE.search(text)
            if not m_pkg or not m_cls or m_cls.group(1) != fn[:-5]:
                continue
            # "abstract class" anywhere before the public class declaration
            head = text[: m_cls.end()]
            if _ABSTRACT_RE.search(head[max(0, m_cls.start() - 40):]):
                continue
            found.append(f"{m_pkg.group(1)}.{m_cls.group(1)}")
        if not recursive:
            break
    return sorted(set(found))


def collect_units(tree, source_root):
    """
    Returns (units, test_elements).
      units: list of (test_index, fqcn, class_element_or_None) in a stable order.
      class_element is the original <class> element (kept so <methods> filters
      survive); None for classes discovered by scanning a <package>.
    """
    root = tree.getroot()
    tests = root.findall("test")
    if not tests:
        raise ShardError("suite has no <test> element")
    units = []
    for ti, test in enumerate(tests):
        seen = set()
        classes_el = test.find("classes")
        if classes_el is not None:
            for c in classes_el.findall("class"):
                name = c.get("name")
                if name and name not in seen:
                    seen.add(name)
                    units.append((ti, name, c))
        packages_el = test.find("packages")
        if packages_el is not None:
            for p in packages_el.findall("package"):
                pname = p.get("name", "")
                recursive = pname.endswith(".*")
                pname = pname[:-2] if recursive else pname
                if not pname:
                    raise ShardError("<package> without a name")
                for fqcn in scan_package(source_root, pname, recursive):
                    if fqcn not in seen:
                        seen.add(fqcn)
                        units.append((ti, fqcn, None))
        if classes_el is None and packages_el is None:
            raise ShardError(f"<test name=\"{test.get('name')}\"> has neither <classes> nor <packages>")
    return units, tests


# --------------------------------------------------------------------------
# History + planning
# --------------------------------------------------------------------------
def load_history(path):
    if not path or not os.path.isfile(path):
        return {"version": 1, "suites": {}}
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        log(f"WARNING: could not read {path} ({e}); planning by class count instead.")
        return {"version": 1, "suites": {}}
    if not isinstance(data, dict) or not isinstance(data.get("suites"), dict):
        log(f"WARNING: {path} has an unexpected shape; planning by class count instead.")
        return {"version": 1, "suites": {}}
    return data


def suite_key_for(path):
    return os.path.splitext(os.path.basename(path))[0]


def weights_for(unit_names, history_suite):
    """name -> seconds. Unknown classes get the median of known ones (or 1.0)."""
    known = {}
    for n in unit_names:
        v = history_suite.get(n)
        if isinstance(v, (int, float)) and v > 0:
            known[n] = float(v)
    fallback = statistics.median(known.values()) if known else 1.0
    return {n: known.get(n, fallback) for n in unit_names}, len(known)


def assign(units, weights, shards):
    """
    Greedy LPT. Deterministic: heaviest first, ties broken by (test index, name),
    each unit goes to the currently lightest shard (lowest index on ties).
    Returns list of shards, each a list of unit tuples.
    """
    order = sorted(units, key=lambda u: (-weights[u[1]], u[0], u[1]))
    loads = [0.0] * shards
    buckets = [[] for _ in range(shards)]
    for u in order:
        i = min(range(shards), key=lambda k: (loads[k], k))
        buckets[i].append(u)
        loads[i] += weights[u[1]]
    return buckets, loads


def build_shard_tree(tree, tests_in_order, bucket, shard_no, shards):
    """Deep-copy the suite and keep only this bucket's classes."""
    import copy
    new_root = copy.deepcopy(tree.getroot())
    wanted = {}
    for ti, name, el in bucket:
        wanted.setdefault(ti, []).append((name, el))
    new_tests = new_root.findall("test")
    for ti, test in enumerate(new_tests):
        for tag in ("packages", "classes"):
            for old in test.findall(tag):
                test.remove(old)
        mine = wanted.get(ti)
        if not mine:
            new_root.remove(test)
            continue
        classes_el = ET.SubElement(test, "classes")
        for name, el in sorted(mine, key=lambda x: x[0]):
            classes_el.append(copy.deepcopy(el) if el is not None else ET.Element("class", {"name": name}))
    base_name = new_root.get("name", "Suite")
    new_root.set("name", f"{base_name} [shard {shard_no}/{shards}]")
    return new_root


def write_suite(root, out_path):
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    ET.indent(root, space="  ")
    body = ET.tostring(root, encoding="unicode")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(DOCTYPE + "\n")
        f.write("<!-- GENERATED by Scripts/shard-tests.py - do not edit; re-created on every run. -->\n")
        f.write(body + "\n")


# --------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------
def cmd_plan(args):
    if args.shards < 1:
        raise ShardError("--shards must be >= 1")
    if args.index is not None and not (1 <= args.index <= args.shards):
        raise ShardError(f"--index must be between 1 and --shards ({args.shards}), got {args.index}")
    tree = parse_suite(args.suite)
    units, tests = collect_units(tree, args.source)
    if not units:
        raise ShardError(f"no test classes found for {args.suite}")
    if args.shards > len(units):
        raise ShardError(
            f"{args.suite} has only {len(units)} class(es) but {args.shards} shards were requested - "
            f"an empty shard would run zero tests. Lower the shard count to {len(units)} or less."
        )
    key = args.suite_key or suite_key_for(args.suite)
    history = load_history(args.durations).get("suites", {}).get(key, {})
    names = [u[1] for u in units]
    weights, known = weights_for(names, history if isinstance(history, dict) else {})
    buckets, loads = assign(units, weights, args.shards)

    mode = "duration history" if known else "class count (no history for this suite yet)"
    log(f"{key}: {len(units)} classes -> {args.shards} shards, balanced by {mode} "
        f"({known}/{len(units)} classes have a recorded duration)")
    for i, (b, load) in enumerate(zip(buckets, loads), start=1):
        log(f"  shard {i}: {len(b):3d} classes, ~{load:7.1f}s expected")

    base = os.path.splitext(os.path.basename(args.suite))[0]
    indexes = [args.index] if args.index else list(range(1, args.shards + 1))
    paths = []
    for i in indexes:
        out = os.path.join(args.out_dir, f"{base}-shard-{i}-of-{args.shards}.xml")
        write_suite(build_shard_tree(tree, tests, buckets[i - 1], i, args.shards), out)
        paths.append(out)
    for p in paths:
        print(p)
    return 0


def cmd_count(args):
    tree = parse_suite(args.suite)
    units, _ = collect_units(tree, args.source)
    print(max(1, min(args.max, len(units))))
    return 0


def _class_times(reports_dir):
    import glob
    times = {}
    for path in sorted(glob.glob(os.path.join(reports_dir, "**", "TEST-*.xml"), recursive=True)):
        if os.path.basename(path) in ("TEST-TestSuite.xml",):
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        if root.tag != "testsuite":
            continue
        for tc in root.findall("testcase"):
            cls = tc.get("classname")
            if not cls:
                continue
            try:
                t = float(tc.get("time", "0") or 0)
            except ValueError:
                continue
            times[cls] = times.get(cls, 0.0) + t
    return times


def cmd_observe(args):
    times = _class_times(args.reports)
    # A class that only produced skips/zero-time rows says nothing about its cost.
    times = {k: round(v, 3) for k, v in times.items() if v > 0}
    if not times:
        log(f"no timed test classes found under {args.reports} - nothing to record.")
        return 0
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "suite": args.suite_key, "classes": times}, f, indent=2, sort_keys=True)
        f.write("\n")
    log(f"{args.suite_key}: recorded {len(times)} class durations -> {args.out}")
    return 0


def _merge_into(history, suite_key, observed, alpha):
    suite = history.setdefault("suites", {}).setdefault(suite_key, {})
    for cls, secs in observed.items():
        if not isinstance(secs, (int, float)) or secs <= 0:
            continue
        old = suite.get(cls)
        if isinstance(old, (int, float)) and old > 0:
            suite[cls] = round(alpha * secs + (1 - alpha) * old, 3)
        else:
            suite[cls] = round(float(secs), 3)
    return len(observed)


def _write_history(path, history):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    history["version"] = 1
    for s in history["suites"].values():
        for k in [k for k, v in s.items() if not isinstance(v, (int, float))]:
            del s[k]
    ordered = {"version": 1, "suites": {k: dict(sorted(v.items())) for k, v in sorted(history["suites"].items())}}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(ordered, f, indent=2)
        f.write("\n")


def cmd_merge(args):
    history = load_history(args.durations)
    merged = 0
    for p in args.inputs:
        if not os.path.isfile(p):
            log(f"skipping missing input {p}")
            continue
        try:
            with open(p, encoding="utf-8") as f:
                obs = json.load(f)
        except (OSError, json.JSONDecodeError) as e:
            log(f"skipping unreadable input {p}: {e}")
            continue
        if not isinstance(obs, dict):
            log(f"skipping {p}: not an observe file")
            continue
        key, classes = obs.get("suite"), obs.get("classes")
        if not key or not isinstance(classes, dict):
            log(f"skipping {p}: not an observe file")
            continue
        merged += _merge_into(history, key, classes, args.alpha)
    if not merged:
        log("nothing to merge - history file left untouched.")
        return 0
    _write_history(args.durations, history)
    log(f"merged {merged} class timings into {args.durations}")
    return 0


def cmd_learn(args):
    tmp = os.path.join("target", "shard-timing", f"{args.suite_key}.json")
    ns = argparse.Namespace(reports=args.reports, suite_key=args.suite_key, out=tmp)
    cmd_observe(ns)
    if os.path.isfile(tmp):
        cmd_merge(argparse.Namespace(durations=args.durations, inputs=[tmp], alpha=args.alpha))
    return 0


def build_parser():
    p = argparse.ArgumentParser(description="Duration-balanced TestNG suite sharding.")
    sub = p.add_subparsers(dest="cmd", required=True)

    pl = sub.add_parser("plan", help="write shard suite file(s), print their path(s)")
    pl.add_argument("--suite", required=True)
    pl.add_argument("--shards", type=int, required=True)
    pl.add_argument("--index", type=int, help="1-based shard to write; omit for all")
    pl.add_argument("--durations", default=DEFAULT_DURATIONS)
    pl.add_argument("--suite-key", help="history key (default: suite file name without .xml)")
    pl.add_argument("--source", default=DEFAULT_SOURCE)
    pl.add_argument("--out-dir", default=DEFAULT_OUT_DIR)
    pl.set_defaults(fn=cmd_plan)

    ct = sub.add_parser("count", help="print min(--max, number of classes in the suite)")
    ct.add_argument("--suite", required=True)
    ct.add_argument("--max", type=int, required=True)
    ct.add_argument("--source", default=DEFAULT_SOURCE)
    ct.set_defaults(fn=cmd_count)

    ob = sub.add_parser("observe", help="record per-class durations from surefire reports")
    ob.add_argument("--reports", default="target/surefire-reports")
    ob.add_argument("--suite-key", required=True)
    ob.add_argument("--out", required=True)
    ob.set_defaults(fn=cmd_observe)

    mg = sub.add_parser("merge", help="merge observe files into the history file")
    mg.add_argument("--durations", default=DEFAULT_DURATIONS)
    mg.add_argument("--inputs", nargs="+", required=True)
    mg.add_argument("--alpha", type=float, default=DEFAULT_EMA_ALPHA, help="weight of the new observation (0-1)")
    mg.set_defaults(fn=cmd_merge)

    ln = sub.add_parser("learn", help="observe + merge for a local run")
    ln.add_argument("--reports", default="target/surefire-reports")
    ln.add_argument("--suite-key", required=True)
    ln.add_argument("--durations", default=DEFAULT_DURATIONS)
    ln.add_argument("--alpha", type=float, default=DEFAULT_EMA_ALPHA)
    ln.set_defaults(fn=cmd_learn)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    if hasattr(args, "alpha") and not (0 < args.alpha <= 1):
        print("shard-tests: --alpha must be in (0, 1]", file=sys.stderr)
        return 2
    try:
        return args.fn(args)
    except ShardError as e:
        print(f"shard-tests: ERROR: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
