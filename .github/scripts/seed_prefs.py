#!/usr/bin/env python3
"""Writes demo data for screenshots as an Android SharedPreferences XML file (gist.xml).

Usage: seed_prefs.py [--pending none|start|evening] > gist.xml

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
        "id": "start", "name": "Starting the Day", "hour": 7, "minute": 0,
        "enabled": True, "aboutYesterday": True,
        "mathCount": 3, "mathLevel": 3,
        "scanCode": "GIST-DEMO-SINK", "scanLabel": "Bathroom sink",
        "questions": [
            q("latefood", "Did you eat anything after 9 PM last night?"),
            q("alcohol", "Did you drink alcohol last night?"),
        ],
    },
    {
        "id": "evening", "name": "Evening", "hour": 21, "minute": 30,
        "enabled": True, "aboutYesterday": False,
        "mathCount": 0, "mathLevel": 3, "scanCode": "", "scanLabel": "",
        "questions": [
            q("sweetdrink", "Did you drink any sweetened beverages today?"),
            q("fastfood", "Did you eat fast food today?"),
            q("sweets", "Did you eat anything sweet today?"),
            q("veg", "Did you eat vegetables or fruit today?", True),
            q("homecooked", "Did you eat a home-cooked meal today?", True),
        ],
    },
    {
        "id": "teeth", "name": "Brush Teeth", "hour": 22, "minute": 15,
        "enabled": True, "aboutYesterday": False,
        "mathCount": 0, "mathLevel": 3,
        "scanCode": "GIST-DEMO-BRUSH", "scanLabel": "Toothbrush holder",
        "questions": [],
    },
]


def entries(today):
    rnd = random.Random(7)
    out = {}
    skip = {4, 11}  # unanswered days
    for back in range(1, 22):
        if back in skip:
            continue
        day = (today - datetime.timedelta(days=back)).isoformat()
        answers = {}
        for c in CHECKINS:
            for question in c["questions"]:
                on_track = rnd.random() < 0.75
                answers[question["id"]] = question["yesIsGood"] if on_track else not question["yesIsGood"]
        out[day] = answers
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pending", choices=["none", "start", "evening"], default="none")
    args = ap.parse_args()

    today = datetime.date.today()
    pending = [] if args.pending == "none" else [{"c": args.pending, "d": today.isoformat()}]
    values = {
        "checkins": json.dumps(CHECKINS),
        "entries": json.dumps(entries(today)),
        "pendingList": json.dumps(pending),
    }
    print("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>")
    print("<map>")
    for name, value in values.items():
        print(f'    <string name="{name}">{escape(value)}</string>')
    print("</map>")


if __name__ == "__main__":
    main()
