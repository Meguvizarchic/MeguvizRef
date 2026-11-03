"""Opt-in mutation check for CodeScope admission/close, the cache-hit memo and the member-table memo (not in verifyHost/CI).

Each mutation is a plausible broken variant of ref-core's CodeScope or ReflectionResolver. The script copies ref-core sources and the test
fixtures to build/mutation/<id>/, applies one textual mutation, compiles with javac --release 17 and runs
CoreContractTest. A mutation is "caught" when the contract run fails. The unmutated control must pass.

    python3 tools/mutation_check.py [--only id,id] [--timeout seconds]

With --jcstress the mutated ref-core classes are prepended to the stress-jvm classpath and the jcstress tests run
instead (first: bash gradlew -Pmeguviz.jcstress=true :stress-jvm:jcstressClasspath). A mutation is caught when any
jcstress test fails.

    python3 tools/mutation_check.py --jcstress [--jcstress-args "-m quick -jvmArgs -Xmx1g -sc false"]
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
SCOPE = "ref-core/src/main/java/io/github/jichuo1/meguviz/ref/scope/CodeScope.java"

RESOLVER = "ref-core/src/main/java/io/github/jichuo1/meguviz/ref/runtime/resolve/ReflectionResolver.java"
# (id, description, anchor, replacement[, file[, jcstress test regex]])
MUTATIONS = [
    ("close-skips-cells", "close sets CLOSING only on the base word and treats every cell as idle",
     "for (int cell = 1; cell <= CELLS; cell++) if (table.getAndAdd(cell * STRIDE, CLOSING) == 0) idle++;",
     "idle += CELLS;"),
    ("cells-before-base", "close closes the cells before the base word",
     "int idle = admission.getAndAdd(CLOSING) == 0 ? 1 : 0;\n                for (int cell = 1; cell <= CELLS; cell++) if (table.getAndAdd(cell * STRIDE, CLOSING) == 0) idle++;",
     "int idle = 0;\n                for (int cell = 1; cell <= CELLS; cell++) if (table.getAndAdd(cell * STRIDE, CLOSING) == 0) idle++;\n                if (admission.getAndAdd(CLOSING) == 0) idle++;"),
    ("cell-skips-base-check", "a cell admission does not check the base word first",
     "        if (admission.get() < 0) throw stale();\n        @SuppressWarnings", "        @SuppressWarnings"),
    ("no-close-share", "the hand-off counter omits close's own share",
     "table.lazySet(PENDING, CELLS + 2);", "table.lazySet(PENDING, CELLS + 1);"),
    ("pending-after-closing", "the hand-off counter is initialized after the CLOSING bits are set",
     "                table.lazySet(PENDING, CELLS + 2);\n", ""),
    ("pending-after-closing-placed", "(companion edit of pending-after-closing)",
     "                // With every word idle no releaser can take a share",
     "                table.lazySet(PENDING, CELLS + 2);\n                // With every word idle no releaser can take a share"),
    ("increment-then-check", "a cell admission increments first and undoes the increment when closing",
     "            int index = (cell + 1) * STRIDE, value = table.get(index);\n            if (value < 0) throw stale();\n            if (table.compareAndSet(index, value, value + 1)) return cell + 1;\n            cell = (cell + 1) & (CELLS - 1); // collided with another thread: probe the next cell",
     "            int index = (cell + 1) * STRIDE;\n            if (table.getAndIncrement(index) >= 0) return cell + 1;\n            release(cell + 1); throw stale();"),
    ("release-to-base", "every release decrements the base word",
     "int value = word == BASE ? admission.decrementAndGet() : cells.decrementAndGet(word * STRIDE);",
     "int value = admission.decrementAndGet();"),
    ("inflate-without-lock", "cells are created without the scope lock and state check",
     "synchronized (lock) { if (cells == null && state == State.OPEN) cells = new AtomicIntegerArray((CELLS + 2) * STRIDE); }",
     "if (cells == null) { Thread.yield(); cells = new AtomicIntegerArray((CELLS + 2) * STRIDE); }"),
    ("idle-shortcut-always", "close always treats the hand-off as its own",
     "drained = idle == CELLS + 1 || table.addAndGet(PENDING, -(idle + 1)) == 0;", "drained = true;"),
    ("memo-survives-put", "a cache put keeps the most-recent memo (no longer the LRU tail)",
     "cache.put(query, result); if (recent != null) recent = null;", "cache.put(query, result);", SCOPE, "MemoStress"),
    ("memo-survives-close", "close keeps the most-recent memo",
     "bindings = Bindings.CLOSED; cache.clear(); if (recent != null) recent = null;", "bindings = Bindings.CLOSED; cache.clear();", SCOPE, "MemoStress"),
    ("memo-survives-epoch", "advanceCodeEpoch keeps the most-recent memo",
     "cache.clear(); if (recent != null) recent = null; memberSlots.clear(); bindings = new Bindings", "cache.clear(); memberSlots.clear(); bindings = new Bindings", SCOPE, "MemoStress"),
    ("table-memo-stale-after-hit", "a member-table map hit does not move the table memo",
     "if (cached != null) { lastTable = new LastTable(owner, shape, cached); return cached; }", "if (cached != null) return cached;", RESOLVER),
    ("table-memo-survives-clear", "clear keeps the member-table memo",
     "tableMembers = 0; lastTable = null; }", "tableMembers = 0; }", RESOLVER),
]

# Mutations applied together under one id.
GROUPS = {"pending-after-closing": ["pending-after-closing", "pending-after-closing-placed"]}
HIDDEN = {"pending-after-closing-placed"}


def run(command, cwd, timeout):
    started = time.monotonic()
    try:
        done = subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=timeout)
        return done.returncode, done.stdout + done.stderr, time.monotonic() - started
    except subprocess.TimeoutExpired as expired:
        output = (expired.stdout or b"").decode() if isinstance(expired.stdout, bytes) else (expired.stdout or "")
        return None, output, time.monotonic() - started


def prepare(name, edits):
    target = ROOT / "build/mutation" / name
    if target.exists(): shutil.rmtree(target)
    shutil.copytree(ROOT / "ref-core/src", target / "ref-core/src")
    shutil.copytree(ROOT / "test-fixtures", target / "test-fixtures")
    for file, old, new in edits:
        source = target / file
        text = source.read_text(encoding="utf-8")
        if text.count(old) != 1: raise SystemExit(f"{name}: mutation anchor found {text.count(old)} times: {old!r}")
        source.write_text(text.replace(old, new), encoding="utf-8")
    return target


def check_jcstress(name, edits, timeout, extra, tests):
    root = prepare(name, edits)
    classpath_file = ROOT / "stress-jvm/build/jcstress-classpath.txt"
    if not classpath_file.is_file(): raise SystemExit("run :stress-jvm:jcstressClasspath with -Pmeguviz.jcstress=true first")
    main = [str(p) for p in (root / "ref-core/src/main/java").rglob("*.java")]
    out = root / "out"
    code, log, _ = run(["javac", "--release", "17", "-nowarn", "-d", str(out / "main"), *main], root, timeout)
    if code != 0: return "compile-error", log, 0.0
    classpath = os.pathsep.join([str(out / "main"), classpath_file.read_text(encoding="utf-8").strip()])
    work = root / "jcstress"; work.mkdir()
    code, log, seconds = run(["java", "-cp", classpath, "org.openjdk.jcstress.Main", "-r", str(work / "report"), *extra.split(), "-t", tests], work, timeout)
    if code is None: return "timeout", log, seconds
    totals = re.findall(r"Results: (\d+) planned; (\d+) passed, (\d+) failed", log)
    failed = int(totals[-1][2]) if totals else 0
    return ("failed" if code != 0 or failed else "passed"), log, seconds


def check(name, edits, timeout):
    root = prepare(name, edits)
    main = [str(p) for p in (root / "ref-core/src/main/java").rglob("*.java")]
    tests = [str(p) for p in (root / "ref-core/src/test/java").rglob("*.java")]
    out = root / "out"
    code, log, _ = run(["javac", "--release", "17", "-nowarn", "-d", str(out / "main"), *main], root, timeout)
    if code != 0: return "compile-error", log, 0.0
    code, log, _ = run(["javac", "--release", "17", "-nowarn", "-cp", str(out / "main"), "-d", str(out / "test"), *tests], root, timeout)
    if code != 0: return "compile-error", log, 0.0
    code, log, seconds = run(["java", "-cp", os.pathsep.join([str(out / "main"), str(out / "test")]),
                              "io.github.jichuo1.meguviz.ref.CoreContractTest"], root, timeout)
    if code is None: return "timeout", log, seconds
    return ("passed" if code == 0 else "failed"), log, seconds


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--jcstress", action="store_true")
    parser.add_argument("--jcstress-args", default="-m quick -jvmArgs -Xmx1g -sc false")
    args = parser.parse_args()
    def runner(name, edits, timeout, tests="AdmissionStress|MemoStress"):
        return check_jcstress(name, edits, timeout, args.jcstress_args, tests) if args.jcstress else check(name, edits, timeout)
    by_id = {m[0]: m for m in MUTATIONS}
    selected = [m[0] for m in MUTATIONS if m[0] not in HIDDEN]
    if args.only: selected = args.only.split(",")
    status, log, seconds = runner("control", [], args.timeout)
    print(f"control: {status} ({seconds:.0f}s)")
    if status != "passed":
        print(log[-4000:]); return 1
    caught = True
    for name in selected:
        parts = [by_id[part] for part in GROUPS.get(name, [name])]
        edits = [(m[4] if len(m) > 4 else SCOPE, m[2], m[3]) for m in parts]
        status, log, seconds = runner(name, edits, args.timeout, parts[0][5] if len(parts[0]) > 5 else "AdmissionStress")
        (ROOT / "build/mutation" / name / "run.log").write_text(log, encoding="utf-8")
        failed = sorted(set(re.findall(r"^FAIL (\S+)", log, re.M)))
        if args.jcstress: failed = sorted(set(re.findall(r"^\s+\[FAILED\]\s+(\S+)", log, re.M)))
        summary = re.findall(r"TESTS=\d+ FAILURES=\d+|Results: \d+ planned; \d+ passed, \d+ failed", log)
        print(f"{name}: {status} ({seconds:.0f}s) {summary[-1] if summary else ''} {by_id[name][1]}")
        for test in failed: print(f"    FAIL {test}")
        caught &= status in ("failed", "timeout")
    print("all mutations caught" if caught else "some mutations survived")
    return 0


if __name__ == "__main__":
    sys.exit(main())
