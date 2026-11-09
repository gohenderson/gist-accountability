#!/usr/bin/env python3
"""Writes demo data for screenshots as an Android SharedPreferences XML file (gist.xml).

Usage: seed_prefs.py [--pending none|start|evening] [--theme system|light|dark] [--view ungrouped|category] > gist.xml

The JSON layout matches Store.kt. Answers are random but reproducible (fixed seed),
with a couple of days left unanswered so the history grid shows gaps.
"""
import argparse
import datetime
import json
import random
from xml.sax.saxutils import escape


def q(qid, text, yes_is_good=False):
    return {"id": qid, "text": text, "yesIsGood": yes_is_good}


CHECKINS = [
    {
        "id": "start", "name": "Starting the Day", "times": ["07:00"],
        "enabled": True, "aboutYesterday": True,
        "category": "Morning",
        "steps": [
            {"id": "start-math", "kind": "math", "mathCount": 3, "mathLevel": 3},
            {"id": "start-scan", "kind": "scan", "scanCode": "GIST-DEMO-SINK", "scanLabel": "Bathroom sink"},
            {"id": "start-q", "kind": "questions", "questions": [
                q("latefood", "Did you eat anything after 9 PM last night?"),
                q("alcohol", "Did you drink alcohol last night?"),
            ]},
        ],
        "repeat": {"kind": "weekly", "days": [1, 2, 3, 4, 5]},
        "background": {"kind": "none", "intervalSec": 10, "caption": "Rise and shine. Future you says thanks."},
    },
    {
        "id": "evening", "name": "Evening", "times": ["21:30"],
        "enabled": True, "aboutYesterday": False,
        "category": "Evening",
        "steps": [
            {"id": "evening-q", "kind": "questions", "questions": [
                q("sweetdrink", "Did you drink any sweetened beverages today?"),
                q("fastfood", "Did you eat fast food today?"),
                q("sweets", "Did you eat anything sweet today?"),
                q("veg", "Did you eat vegetables or fruit today?", True),
                q("homecooked", "Did you eat a home-cooked meal today?", True),
            ]},
        ],
    },
    {
        "id": "teeth", "name": "Brush Teeth", "times": ["07:30", "22:15"],
        "enabled": True, "aboutYesterday": False,
        "category": "Morning",
        "steps": [
            {"id": "teeth-scan", "kind": "scan", "scanCode": "GIST-DEMO-BRUSH", "scanLabel": "Toothbrush holder"},
        ],
    },
]


def questions(c):
    """All questions in a check-in's question steps."""
    return [qq for st in c["steps"] if st["kind"] == "questions" for qq in st["questions"]]


def entries(today):
    """day → check-in → time → {"s": status, "a": answers}; a few missed and skipped times."""
    rnd = random.Random(7)
    out = {}
    for back in range(1, 22):
        day = (today - datetime.timedelta(days=back)).isoformat()
        d = {}
        for c in CHECKINS:
            slots = {}
            for t in c["times"]:
                if back in (4, 11):
                    slots[t] = {"s": "skipped", "a": {}}
                elif back == 9 and questions(c):
                    slots[t] = {"s": "rang", "a": {}}
                elif not questions(c):
                    slots[t] = {"s": "done", "a": {}, "early": back % 5 == 0}
                else:
                    a = {}
                    for question in questions(c):
                        on_track = rnd.random() < 0.8
                        a[question["id"]] = question["yesIsGood"] if on_track else not question["yesIsGood"]
                    slots[t] = {"s": "answered", "a": a}
            d[c["id"]] = slots
        out[day] = d
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pending", choices=["none", "start", "evening"], default="none")
    ap.add_argument("--theme", choices=["system", "light", "dark"], default="system")
    ap.add_argument("--view", choices=["ungrouped", "category"], default="ungrouped")
    args = ap.parse_args()

    today = datetime.date.today()
    pending = [] if args.pending == "none" else [{"c": args.pending, "d": today.isoformat()}]
    values = {
        "checkins": json.dumps(CHECKINS),
        "results": json.dumps(entries(today)),
        "pendingList": json.dumps(pending),
    }
    print("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>")
    print("<map>")
    for name, value in values.items():
        print(f'    <string name="{name}">{escape(value)}</string>')
    theme = {"system": 0, "light": 1, "dark": 2}[args.theme]
    print(f'    <int name="theme" value="{theme}" />')
    print(f'    <string name="view">{args.view}</string>')
    # Groups in a sensible order: Morning above Evening.
    print('    <string name="groupOrder">morning&#10;evening</string>')
    print("</map>")


if __name__ == "__main__":
    main()
