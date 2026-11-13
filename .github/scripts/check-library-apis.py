#!/usr/bin/env python3
"""Checks that the libraries in the APK only call Java APIs the app's oldest Android has.

Libraries (SnakeYAML Engine, ZXing) are plain Java, built for a Java version, not for an
Android version: a newer Java API they call (say Optional.isEmpty, Android 13) would crash
the app on older phones, and lint doesn't look inside libraries. D8 rewrites many such
calls when it builds the APK ("backports"), so this reads the APK's own code (dexdump)
after that, and looks every remaining java.* / javax.* call up in the SDK's API database.

Usage: check-library-apis.py <apk> <min sdk>
Needs ANDROID_HOME (dexdump from build-tools, api-versions.xml from a platform).
Prints a table to the job summary; exits 1 if a library calls something too new.
"""
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import tempfile
import zipfile

OWN_PACKAGE = "Ldev/gist/"  # the app's own code: lint checks it, with its version checks


def tool(pattern):
    found = sorted(glob.glob(os.path.join(os.environ["ANDROID_HOME"], pattern)))
    if not found:
        sys.exit(f"Not found: $ANDROID_HOME/{pattern}")
    return found[-1]


def level(value):
    """An API level from api-versions.xml: "33", or newer files' "36.1" (major.minor)."""
    return int(str(value).split(".")[0])


def load_api(path):
    """{class: (since, {member: since}, [supertypes])} from api-versions.xml."""
    api = {}
    for c in ET.parse(path).getroot().iter("class"):
        since = level(c.get("since", "1"))
        members = {}
        for m in list(c.iter("method")) + list(c.iter("field")):
            members[m.get("name")] = level(m.get("since", since))
        supers = [e.get("name") for e in list(c.iter("extends")) + list(c.iter("implements"))]
        api[c.get("name")] = (since, members, supers)
    return api


def since(api, cls, member, seen=None):
    """When [member] of [cls] (or a supertype that declares it) appeared; None if unknown."""
    seen = seen or set()
    if cls in seen or cls not in api:
        return None
    seen.add(cls)
    _, members, supers = api[cls]
    if member in members:
        return members[member]
    for s in supers:
        found = since(api, s, member, seen)
        if found is not None:
            return found
    return None


# invoke-virtual {v0}, Ljava/util/Optional;.isEmpty:()Z // method@1234
CALL = re.compile(r"invoke-\w+(?:/range)? \{[^}]*\}, L((?:java|javax)/[^;]+);\.([^:]+):(\S+)")
FIELD = re.compile(r"[si]get(?:-\w+)? [^,]+, L((?:java|javax)/[^;]+);\.([^:]+):")
CLASS = re.compile(r"Class descriptor\s*:\s*'(L[^']+;)'")


def main():
    apk, min_sdk = sys.argv[1], int(sys.argv[2])
    dexdump = tool("build-tools/*/dexdump")
    api = load_api(tool("platforms/android-*/data/api-versions.xml"))
    problems = {}  # (api, since) -> set of library classes
    checked = 0
    newest = 0  # the newest API level any call needs, to show the check is really looking
    unknown = 0
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            path = z.extract(name, tmp)
            out = subprocess.run([dexdump, "-d", path], capture_output=True, check=True).stdout.decode("utf-8", "replace")
            cls = ""
            for line in out.splitlines():
                m = CLASS.search(line)
                if m:
                    cls = m.group(1)
                    continue
                if not cls or cls.startswith(OWN_PACKAGE):
                    continue
                m = CALL.search(line)
                if m:
                    owner, method, sig = m.groups()
                    member = method + sig  # as api-versions.xml names it: isEmpty()Z
                    s = since(api, owner, member)
                else:
                    m = FIELD.search(line)
                    if not m:
                        continue
                    owner, field = m.groups()
                    member = field
                    s = since(api, owner, field)
                if s is None:
                    cs = api.get(owner)
                    s = cs[0] if cs else None
                checked += 1
                if s is None:
                    unknown += 1
                    continue
                newest = max(newest, s)
                if s > min_sdk:
                    pretty = owner.replace("/", ".") + "." + member
                    problems.setdefault((pretty, s), set()).add(cls[1:-1].replace("/", "."))

    lines = ["### Library API levels", ""]
    if problems:
        lines += [f"Libraries call Java APIs newer than Android API {min_sdk} (the app's oldest):", "",
                  "| API | Added in API | Called from |", "|---|---|---|"]
        for (pretty, s), classes in sorted(problems.items(), key=lambda kv: (-kv[0][1], kv[0][0])):
            where = ", ".join(sorted(classes)[:3]) + (f" (+{len(classes) - 3})" if len(classes) > 3 else "")
            lines.append(f"| `{pretty}` | {s} | {where} |")
    else:
        lines.append(f"✓ {checked} calls from libraries into Java APIs, all available on API {min_sdk} "
                     f"(the newest needs API {newest}; {unknown} not in the API database).")
    report = "\n".join(lines) + "\n"
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(report)
    if problems:
        print(f"::error title=Library API levels::Libraries call {len(problems)} Java API(s) newer than API {min_sdk}; see the job summary.")
        sys.exit(1)


if __name__ == "__main__":
    main()
