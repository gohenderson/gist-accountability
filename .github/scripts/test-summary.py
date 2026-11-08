#!/usr/bin/env python3
"""Summarizes JUnit XML results for a GitHub Actions run.

Usage: test-summary.py <title> <dir> [<dir> ...]

- Writes a table of passed / failed / skipped counts and each failure's message to the
  job summary ($GITHUB_STEP_SUMMARY).
- Emits an ::error annotation per failure, pointing at the test's source line when the
  stack trace contains it (e.g. "CheckinFlowTest.kt:42").
- Never fails the step itself; Gradle's exit code decides that.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def find_source(file_name):
    for root in ("app/src/test", "app/src/androidTest"):
        for path in glob.glob(f"{root}/**/{file_name}", recursive=True):
            return path
    return None


def first_line(text):
    for line in (text or "").splitlines():
        if line.strip():
            return line.strip()
    return ""


def main():
    title, dirs = sys.argv[1], sys.argv[2:]
    files = sorted({f for d in dirs for f in glob.glob(f"{d}/**/TEST-*.xml", recursive=True)})
    summary = os.environ.get("GITHUB_STEP_SUMMARY", "/dev/null")

    total = failed = skipped = 0
    failures = []
    for f in files:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            print(f"::warning title=Unreadable test report::{f}: {e}")
            continue
        for case in root.iter("testcase"):
            total += 1
            if case.find("skipped") is not None:
                skipped += 1
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                failed += 1
                cls = case.get("classname", "")
                name = case.get("name", "")
                detail = problem.text or problem.get("message", "")
                failures.append((cls, name, first_line(problem.get("message") or detail), detail))

    with open(summary, "a") as out:
        if not files:
            out.write(f"### {title}: no test results found\n\n")
            print(f"::warning title={title}::No JUnit XML results found in {dirs}")
            return
        icon = "❌" if failed else "✅"
        out.write(f"### {icon} {title}: {total - failed - skipped} passed, {failed} failed, {skipped} skipped\n\n")
        for cls, name, msg, detail in failures:
            simple = cls.rsplit(".", 1)[-1]
            out.write(f"- **{simple} › {name}**: `{msg[:300]}`\n")
            m = re.search(rf"\(({re.escape(simple)}\.kt):(\d+)\)", detail or "")
            src = find_source(m.group(1)) if m else None
            loc = f"file={src},line={m.group(2)}," if src else ""
            safe = msg.replace("%", "%25").replace("\r", "").replace("\n", " ")[:500]
            print(f"::error {loc}title=Test failed: {simple} › {name}::{safe}")
        out.write("\n")


if __name__ == "__main__":
    main()
