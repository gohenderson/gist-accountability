#!/usr/bin/env python3
"""Weekly dependency & security report.

Checks, and writes a Markdown report:
  1. Outdated build tools and libraries: Gradle, the Android Gradle Plugin, Kotlin, and every
     library in app/build.gradle.kts, against the latest stable versions on Google Maven /
     Maven Central / services.gradle.org.
  2. Outdated GitHub Actions: each `uses: owner/repo@vN` against the action's latest release,
     plus actions still running on an old Node.js runtime (node16/node20).
  3. Known vulnerabilities: every resolved dependency (from `gradle dependencies` output
     files given with --deps) checked against the OSV database (osv.dev, free, no key).
  4. Open code-scanning (CodeQL) alerts in this repository.

Usage: deps-report.py --out report.md [--deps deps1.txt --deps deps2.txt]
Writes `findings=<n>` to $GITHUB_OUTPUT. Never fails the step for findings.
"""
import argparse
import glob
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

REPO = os.environ.get("GITHUB_REPOSITORY", "")
TOKEN = os.environ.get("GITHUB_TOKEN", "")
UNSTABLE = re.compile(r"(alpha|beta|rc|dev|eap|snapshot|-m\d|preview)", re.I)


def http_get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": "gist-deps-report", **(headers or {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def gh_api(path):
    headers = {"Accept": "application/vnd.github+json"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    return json.loads(http_get(f"https://api.github.com{path}", headers))


def vkey(v):
    """Sort key for versions like 8.10.1, 2.1.21, 1.6.1."""
    return [int(p) if p.isdigit() else -1 for p in re.split(r"[.\-]", v)]


def latest_maven(group, artifact):
    path = group.replace(".", "/") + "/" + artifact
    for base in ("https://dl.google.com/android/maven2", "https://repo1.maven.org/maven2"):
        try:
            root = ET.fromstring(http_get(f"{base}/{path}/maven-metadata.xml"))
        except Exception:
            continue
        versions = [v.text for v in root.iter("version") if v.text and not UNSTABLE.search(v.text)]
        if versions:
            return max(versions, key=vkey)
    return None


def latest_gradle():
    try:
        return json.loads(http_get("https://services.gradle.org/versions/current"))["version"]
    except Exception:
        return None


def read(path):
    try:
        with open(path) as f:
            return f.read()
    except FileNotFoundError:
        return ""


def check_build_tools(findings, rows):
    root = read("build.gradle.kts")
    app = read("app/build.gradle.kts")
    wf = "\n".join(read(f) for f in glob.glob(".github/workflows/*.yml"))
    wrapper = read("gradle/wrapper/gradle-wrapper.properties")

    items = []
    m = re.search(r'GRADLE_VERSION:\s*"([^"]+)"', wf)
    if m:
        items.append(("Gradle", m.group(1), latest_gradle()))
    m = re.search(r'gradle-([\d.]+)-bin\.zip', wrapper)
    if m:
        items.append(("Gradle (wrapper)", m.group(1), latest_gradle()))
    for plugin_id, (g, a) in {
        "com.android.application": ("com.android.tools.build", "gradle"),
        "org.jetbrains.kotlin.android": ("org.jetbrains.kotlin", "kotlin-gradle-plugin"),
    }.items():
        m = re.search(rf'id\("{re.escape(plugin_id)}"\)\s+version\s+"([^"]+)"', root)
        if m:
            items.append((f"{plugin_id} ({g}:{a})", m.group(1), latest_maven(g, a)))
    for m in re.finditer(r'(?:implementation|Implementation)\("([\w.\-]+):([\w.\-]+):([\w.\-]+)"\)', app):
        g, a, v = m.groups()
        items.append((f"{g}:{a}", v, latest_maven(g, a)))

    for name, current, latest in items:
        if latest is None:
            rows.append((name, current, "?", "couldn't check"))
        elif vkey(latest) > vkey(current):
            rows.append((name, current, latest, "⬆ update available"))
            findings.append(f"{name}: {current} → {latest}")
        else:
            rows.append((name, current, latest, "✓ current"))


def check_actions(findings, rows):
    seen = {}
    for f in sorted(glob.glob(".github/workflows/*.yml")):
        for m in re.finditer(r"uses:\s*([\w.\-]+/[\w.\-]+)(/[\w./\-]+)?@([\w.\-]+)", read(f)):
            repo, sub, ref = m.group(1), m.group(2) or "", m.group(3)
            seen[(repo, sub, ref)] = True
    for repo, sub, ref in seen:
        name = f"{repo}{sub}@{ref}"
        latest = None
        try:
            latest = gh_api(f"/repos/{repo}/releases/latest")["tag_name"]
        except Exception:
            pass
        runtime = "?"
        try:
            meta = read_action_yml(repo, sub, ref)
            mm = re.search(r"using:\s*['\"]?([\w\-]+)", meta)
            runtime = mm.group(1) if mm else "?"
        except Exception:
            pass
        notes = []
        cur_major = re.match(r"v?(\d+)", ref)
        new_major = re.match(r"v?(\d+)", latest or "")
        if cur_major and new_major and int(new_major.group(1)) > int(cur_major.group(1)):
            notes.append(f"⬆ newer major: {latest}")
            findings.append(f"Action {name}: newer release {latest}")
        if runtime in ("node12", "node16", "node20"):
            notes.append(f"⚠ runs on {runtime}")
            findings.append(f"Action {name} runs on {runtime}")
        rows.append((name, runtime, latest or "?", ", ".join(notes) or "✓ current"))


def read_action_yml(repo, sub, ref):
    for fname in ("action.yml", "action.yaml"):
        try:
            data = gh_api(f"/repos/{repo}/contents{sub}/{fname}?ref={ref}")
            import base64
            return base64.b64decode(data["content"]).decode()
        except Exception:
            continue
    return ""


def resolved_deps(files):
    coords = set()
    for f in files:
        for line in read(f).splitlines():
            m = re.search(r"([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?", line)
            if m and ("---" in line):
                g, a, v, override = m.groups()
                coords.add((g, a, override or v))
    return sorted(coords)


def check_osv(findings, files):
    coords = resolved_deps(files)
    if not coords:
        return [], 0
    vulns = []
    for i in range(0, len(coords), 500):
        chunk = coords[i:i + 500]
        body = json.dumps({"queries": [
            {"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in chunk
        ]}).encode()
        req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=body,
                                     headers={"Content-Type": "application/json", "User-Agent": "gist-deps-report"})
        with urllib.request.urlopen(req, timeout=60) as r:
            results = json.loads(r.read())["results"]
        for (g, a, v), res in zip(chunk, results):
            for vuln in res.get("vulns", []) or []:
                vulns.append((f"{g}:{a}:{v}", vuln["id"]))
                findings.append(f"Vulnerability {vuln['id']} in {g}:{a}:{v}")
    return vulns, len(coords)


def check_code_scanning(findings):
    if not REPO:
        return None
    try:
        alerts = gh_api(f"/repos/{REPO}/code-scanning/alerts?state=open&per_page=100")
    except urllib.error.HTTPError as e:
        return f"couldn't read code-scanning alerts ({e.code})"
    out = []
    for a in alerts:
        rule = a.get("rule", {})
        loc = a.get("most_recent_instance", {}).get("location", {})
        out.append((rule.get("security_severity_level") or rule.get("severity") or "?",
                    rule.get("description") or rule.get("id"), f"{loc.get('path')}:{loc.get('start_line')}",
                    a.get("html_url")))
        findings.append(f"Code scanning: {rule.get('id')} at {loc.get('path')}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="report.md")
    ap.add_argument("--deps", action="append", default=[])
    args = ap.parse_args()

    findings = []
    tool_rows, action_rows = [], []
    check_build_tools(findings, tool_rows)
    check_actions(findings, action_rows)
    try:
        vulns, scanned = check_osv(findings, args.deps)
        osv_error = None
    except Exception as e:
        vulns, scanned, osv_error = [], 0, str(e)
    alerts = check_code_scanning(findings)

    lines = ["# Dependency & security report", ""]
    lines.append(f"**{len(findings)} finding(s).**" if findings else "**All clear.** Nothing outdated or vulnerable found.")
    lines += ["", "## Build tools and libraries", "", "| Name | Current | Latest stable | Status |", "|---|---|---|---|"]
    lines += [f"| `{n}` | {c} | {l} | {s} |" for n, c, l, s in tool_rows]
    lines += ["", "## GitHub Actions", "", "| Action | Runtime | Latest release | Status |", "|---|---|---|---|"]
    lines += [f"| `{n}` | {rt} | {l} | {s} |" for n, rt, l, s in action_rows]
    lines += ["", "## Known vulnerabilities (OSV)", ""]
    if osv_error:
        lines.append(f"⚠ Couldn't query OSV: {osv_error}")
    elif not scanned:
        lines.append("No resolved dependency list was provided.")
    elif vulns:
        lines += [f"- **{vid}** in `{coord}`: https://osv.dev/vulnerability/{vid}" for coord, vid in vulns]
    else:
        lines.append(f"None in {scanned} resolved dependencies.")
    lines += ["", "## Code scanning (CodeQL)", ""]
    if alerts is None:
        lines.append("Not checked.")
    elif isinstance(alerts, str):
        lines.append(alerts)
    elif alerts:
        lines += [f"- **{sev}**: {desc} at `{loc}` ({url})" for sev, desc, loc, url in alerts]
    else:
        lines.append("No open alerts.")

    report = "\n".join(lines) + "\n"
    with open(args.out, "w") as f:
        f.write(report)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(report)
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as f:
            f.write(f"findings={len(findings)}\n")


if __name__ == "__main__":
    sys.exit(main())
