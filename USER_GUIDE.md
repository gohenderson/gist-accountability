# Gist user guide

Everything Gist does, in detail. For what Gist is and why it exists, see the
[README](README.md).

- [First run](#first-run)
- [The start page](#the-start-page)
- [A check-in's page](#a-check-ins-page)
- [Triggers](#triggers)
- [Places](#places)
- [Running](#running)
- [Steps](#steps)
- [Commitment: strict, soft or voice](#commitment-strict-soft-or-voice)
- [When it rings](#when-it-rings)
- [Testing a check-in](#testing-a-check-in)
- [History, scores and logging](#history-scores-and-logging)
- [Settings, backups and export](#settings-backups-and-export)
- [About](#about)
- [Honest limits](#honest-limits)

## First run

The start page shows a **Finish setup** card until everything alarms need is granted:

| Permission | Why |
|---|---|
| Notifications | The alarm screen is launched through a notification. |
| Full-screen alarms (Android 14+) | Lets the check-in appear over the lock screen. |
| Alarms & reminders | Rings at the exact minute (usually already granted). |
| Display over other apps | Lets a strict alarm come straight back to the front if you leave it. Gist never draws anything over other apps. |
| Battery optimization off | Samsung, Xiaomi, OnePlus and others sometimes kill background alarms. Also see <https://dontkillmyapp.com>. |
| Location, "all the time" (only if you use places) | Noticing an arrival or departure while Gist is closed. |
| Physical activity (only if you use running) | Reading the step counter. |
| Camera (asked when you register a code) | Scanning. Grant it ahead of time, because the alarm screen can't ask for it. |
| Microphone (asked when you record a voice clip) | Recording a voice check-in's clip. |

Then tap a check-in's **Test** button (it rings in 10 seconds) and lock the phone.

## The start page

- **Your check-ins.** You start with **Morning** (7:00 AM, 2 questions about last night)
  and **Evening** (9:30 PM, 5 questions about today). Rename, change or delete them.
- **+ Add check-in** opens the **library**: ready-made check-ins grouped by
  - **Food:** diet gist, last night, water;
  - **Wake-up:** a weekday math alarm, a gentler weekend one;
  - **Health:** medication, brush teeth, bedtime;
  - **Fitness:** workout Mon/Wed/Fri, stand & stretch on work days, daily steps, Sunday
    weigh-in, after a run, 30 minutes into a run;
  - **Mind:** journal, reading;
  - **Money:** a monthly money check on the 1st, bills on the 15th and the last day.

  Tap **Add** to copy one into your list and open it to adjust, or **Create your own** to
  start blank.
- **Each check-in's card** shows its name, an on/off switch, when it rings next (with
  **Skip** and **Do it now**), its triggers and steps, its last-7-days stat, and
  **History** / **Edit**.
  - **Tap a check-in's title to fold it** to one line: its name, next ring and switch. Tap
    again to unfold it. Each check-in remembers how you left it.
  - **Drag a card by its ≡ handle** to put your check-ins in your own order.
- **View** (above the list, once you have two or more check-ins) holds both how the list is
  grouped and how it's sorted. Each choice applies right away. The button shows the
  current sort.
  - **By category** (a checkbox): a section per category, A–Z, with uncategorized
    check-ins under "Other". Unticked, it's one list.

  Set a check-in's **Category** on its page, either one you've used or a new one.
  Check-ins from the library come with the library's category. When grouped:
  - you can drag cards within a section;
  - drag a **group** by the ≡ on its header to move it above or below the others;
  - tap a group's header to collapse or expand it.
- **Sort** (in the same View menu) orders the check-ins, within each group when grouped:
  - **My order** (the default): as you've dragged them.
  - **Next alarm:** soonest ring first.
  - **Needs attention:** lowest on-track score first, so what's slipping comes to the top.
  - **Name:** A to Z.

  Check-ins without a next ring (switched off, or only place or run triggers) or without
  a score go last, in your order. While sorted, cards can't be dragged; switch back to My
  order to rearrange them. Your order is kept underneath, and the choice is remembered.
- **Collapse all / Expand all** folds or unfolds everything: groups and check-ins.
- **On-track colors.** Each check-in's card is outlined in a color from red (0%) through
  amber to green (100%) by its score (see [History, scores and
  logging](#history-scores-and-logging)), and group headers are colored by their average.
  The numbers stay out of the list. The overall score is at the top of the page, and each
  check-in's own score is at the top of its page. Screen readers read the numbers out.
- **Settings** and **About** are at the bottom.

## A check-in's page

Tap **Edit** on a card. Changes save as you make them.

- **Name**, **on/off**, **Category**, and **"About yesterday"**, which logs answers to the
  previous day (see below).
- **Schedule:** its triggers (see [Triggers](#triggers)). The page shows when it rings
  next.
- **While it rings:** sound, background and caption (see [When it rings](#when-it-rings)).
- **When it rings:** its commitment, and its steps (for a voice check-in, what it says).
- **Try it:** a 10-second test ring, or **Play it now** for a voice check-in.
- **Delete this check-in.**

Once it has history, its on-track score is shown at the top.

## Triggers

Under **Schedule**, a check-in has a list of triggers, each in its own card. It rings
whenever any of them fires. **+ Add trigger** adds one: a time, which you can change into
anything else. Each card has:

- **When:**
  - **At a set time**;
  - **When I arrive** or **When I leave** a place;
  - **When I start running** or **When I finish running**;
  - **Partway into a run**: 5 minutes to 4 hours in, once per run.
- **Time:** the time of day (for set times), or **Anytime** / **Only between…** two times
  for the others. A window can cross midnight.
- **Days:** every day, weekdays, weekends, specific days ("every day but Tue"), or monthly
  dates (the 1st, the 15th, the last day; months without that date are skipped).
- **Where:**
  - for set times: **Anywhere**, or only when you're **at** or **away from** a place when
    it's due. That's checked at ring time. If it doesn't hold, that ring is marked skipped
    (–); if your location can't be found, it rings anyway.
  - for arrive or leave: the place.
- **Minimum run duration** (finish running only): any length, or at least 5–60 minutes, so
  a short dash for the bus doesn't count.
- **Run time** (partway into a run): how far in.
- **At most once a day** (for arrive, leave and running).
- **✕** removes it. A check-in with no triggers never rings on its own.

So one check-in can ring at 4:00 AM on Tuesdays and 4:45 AM every other day, and also
when you arrive at the gym on Mon/Wed/Fri mornings. Days it isn't scheduled show **·** on
History and don't count against you.

**Several times a day:** a check-in can ring at more than one time (say, brushing teeth at
7:30 AM and 10:15 PM). Each time keeps its own result; see [History, scores and
logging](#history-scores-and-logging) for how they roll up.

## Places

- **Picking a place:**
  - **Use my current location**;
  - **Enter coordinates** (long-press a spot in any maps app and copy them);
  - or one you've already used.
- **How close counts:** 100 m to 1 km. Accuracy is typically 50–200 m and an arrival can
  take a few minutes to register, so a 200 m circle is a good start.
- **Privacy:** Gist uses Android's own location service. There's no Google Play services
  and no internet, and your location never leaves the phone.
- **Permission:** Android needs location allowed **all the time** to notice an arrival
  while Gist is closed. The check-in's page and the start page's Finish setup card ask for
  it.
- **Limit:** each check-in can watch up to 10 places.

## Running

- **How a run is noticed:** from the phone's **step counter**. About 140+ steps a minute
  for a minute starts a run. About 1½ minutes below that (stopped, or walking) ends it, so
  a red light doesn't.
- **Where it works:** in a pocket, armband or on a treadmill, with no GPS.
- **What it needs:** Android's "physical activity" permission and a step sensor (most
  phones have one).
- **The notification:** while a check-in uses running, a quiet "Watching for runs"
  notification shows, because Android requires one. During a run the phone's processor
  stays awake so the end is noticed on time.
- **Privacy:** steps never leave the phone.
- **Start running** rings about a minute into any run, before its length is known.
- **Partway into a run** counts from when the run is noticed, so "30 minutes in" lands
  about a minute after your real 30. A walk break longer than about 1½ minutes ends the
  run, and the next one starts the count again.

## Steps

On a check-in's page, **When it rings** lists its steps in the order they run.

- **+ Add step** picks a type. Add as many as you like, of any kind, repeated if you
  want: up to 100 steps, such as ten very hard math steps of 100 problems each.
- **Drag a step by its ≡ handle** to change the order.
- **Tap a step** to open its own page, where you set it up, **Duplicate** it, or remove
  it.
- A step that isn't set up yet (a scan without a code, questions without any) is shown in
  orange and skipped when the alarm rings.

The types:

- **Math problems:** 1–100 problems at one of four levels:

  | Level | Example |
  |---|---|
  | Easy | `47 + 38` |
  | Medium | `47 × 6 + 23` |
  | Hard | `47 × 38 + 512` |
  | Very hard | `347 × 38 + 47 × 26` |

  A wrong answer gives you a new problem.
- **Scan a code:** **Register a code** (any QR code or product barcode) and name the spot,
  e.g. "Bathroom sink". When the alarm rings, only that exact code stops it. If you can't
  scan (you're traveling, or the camera fails), a **"Can't scan?"** button appears after 2
  minutes. It swaps the scan for 5 very hard problems.
- **Questions:** yes/no questions. Add, edit, reorder and delete them. Each one says
  whether "Yes" or "No" is on track. These are the only answers that get logged. With
  several question steps, History shows all of their questions. **Submit** stays disabled
  until every question is answered.

A check-in with no steps just shows a **Dismiss** button.

## Commitment: strict, soft or voice

Set per check-in, at the top of **When it rings**.

- **Strict** (the default) is the whole point of Gist. Only finishing the steps stops it.
  There's no snooze, Back does nothing, and if you leave it, it comes back to the front.
  It counts in your stats.
- **Soft** is for things that don't need forcing.
  - The ringing screen and the alarm notification have **Snooze** (5, 10, 15, 20 or 30
    minutes; 10 by default) and **Dismiss**.
  - The steps are optional: start them, or dismiss part-way.
  - Back or Home leaves it ringing in the background instead of pulling you back.
- **Voice** isn't an alarm at all. When a trigger fires, it says a line through your
  headphones, like a navigation app: your music or podcast dips while it talks. There's no
  screen and nothing to tap.
  - The line is **typed text**, read by the phone's text-to-speech voice, or a **recorded
    clip**. Record it in the app (up to 30 seconds) or pick an audio file (up to 10 MB).
  - **Only through headphones** (on by default) keeps it quiet when none are connected,
    so it never talks out of your pocket.
  - Pair it with **Partway into a run** for encouragement, or heckling, mid-run.

Soft and voice check-ins are **left out of every stat**: no score, no on-track line or
History, not in group or overall averages, and not in the CSV. Results logged while a
check-in is soft or voice stay out even if you later make it strict.

## When it rings

- **Sound:** the check-in's chosen sound (any of the phone's sounds, or "Silent" to only
  vibrate), on the alarm stream, with vibration. While it rings, the alarm volume can't
  go below 80%; turn it down and it goes straight back up. Your volume is restored
  afterwards.
- **Gentle start:** the first ring starts quietly and swells to full volume over about 30
  seconds (vibration starts right away). If a pause runs out later, it comes back at full
  volume, since you're already awake.
- **Background and caption:** set under **While it rings**.
  - **None.**
  - **One photo.**
  - **A slideshow:** up to 20 photos, cross-fading every 3, 5, 10, 30 or 60 seconds.
  - **A video:** up to 30 MB. It plays muted and loops, so you still hear the alarm.

  Media is copied into the app, so deleting the originals doesn't break it. A **caption**
  shows in large type, e.g. "Future you says thanks", with or without a background.
- **The ringing screen** shows the time, the check-in's name and caption at the top and
  one big **Start check-in** button at the bottom, so the background stays visible.
- **Pause while you work.** **Start check-in** silences the sound and vibration and shows
  the steps, with a countdown bar at the top.
  - Every tap or keystroke restarts the countdown. Its length is a setting: 15, 30, 45
    (the default) or 60 seconds.
  - If the countdown runs out, even with the app in the background, the alarm rings again.
    Your progress (solved problems, answers) is kept.
- **Can't dismiss it (strict).**
  - The check-in shows over the lock screen and Back does nothing.
  - Leaving with Home or the app switcher doesn't stop the sound, and the check-in comes
    straight back to the front within a second.
  - Coming back needs **Display over other apps**. Without it, the alarm keeps ringing,
    and opening the app takes you straight back to it.
- **Two at once:** if two check-ins are waiting (say you slept through one), the alarm
  screen goes through them one after the other. It stops ringing only after the last one.
- **Safety valve:** after 60 minutes the sound stops so it can't drain your battery. The
  check-in stays pending as a notification until you finish it.

## Testing a check-in

**Test: ring in 10 seconds** on a check-in's page is a practice run.

- It rings and runs every step, but **nothing is logged**, and the next real alarm still
  rings as scheduled.
- **End test** (or Back) stops it at any point, and leaving it with Home doesn't pull you
  back.
- Lock the phone after tapping Test to see the full-screen version.

A voice check-in has **Play it now** instead.

## History, scores and logging

- **History** (on a check-in's card) shows its last 30 days:
  - logged and on-track counts;
  - how often each question went off track;
  - a ✓/✗ grid. Tap a day to fill it in or fix it.
- **Several times a day** roll up per day:
  - **✗** if any time that day was answered off track, **or rang and was never finished**
    (missed);
  - **✓** if every time that rang was answered on track;
  - **–** if every time that day was skipped;
  - **·** if nothing rang.

  Tapping a day asks which time to fix, and a missed time can be filled in afterwards.
- **On-track score:** the share of the last 7 logged days that were fully on track.
  - Missed rings count as off track.
  - Skipped days, and days it didn't ring, don't count.
  - Check-ins that are switched off, or have nothing logged in the last week, have no
    score.
  - Group scores and the overall score are averages.
- **Skip and Do it now** (on the card's next ring):
  - **Skip** moves past the next ring (Undo restores it).
  - **Do it now** runs the check-in early, and that time's alarm then stays quiet.
- **"About yesterday":** a check-in with this setting (Morning, by default) logs its
  answers to the previous day, so last night's late snack lands on the right date.
  Separately, any alarm set before 4 AM counts as the previous day.

## Settings, backups and export

**Settings** is at the bottom of the start page.

- **Appearance:** same as the phone, Light or Dark.
- **While you check in:** the pause length (15, 30, 45 or 60 seconds).
- **Export backup…**
  - **Choosing what to include:** it asks which check-ins to include (all are ticked) and,
    for each, whether to **include its history**.
  - **What's in the file:** one `.yaml` file with their settings, backgrounds and voice
    clips (as base64), and the history you chose.
  - **Editing it by hand:** it's readable, and you can edit it (times are `"HH:mm"`; a
    comment at the top says how to import it).
  - **Naming:** with one check-in, the file is named after it, which is handy for sharing
    a check-in.
- **Import backup…** lists the check-ins in the file. For each, choose whether to import
  it and whether to **import its history**. If you already have it (same check-in, or same
  name), choose:
  - **Update it:** settings and background from the file, and your history kept. The
    file's history is added too, if you import it; the file wins for the same time on the
    same day.
  - **Replace it:** the file's version, history included. Yours is deleted.
  - **Keep it and add this as a copy**, named e.g. "Wake (imported)".

  Check-ins you don't pick, and ones on the phone that aren't in the file, are left alone.
  Alarms are set right away. A bad or unrelated file is rejected without changing
  anything. Alarm sounds are saved by reference, so if the new phone lacks a sound, that
  check-in uses the default.
- **Export answers (CSV)…:** one row per day, check-in and time, with its status, for
  spreadsheets. Soft and voice check-ins aren't included.

## About

**About** (bottom of the start page, or Settings → About) shows:
- the version;
- privacy;
- links to the source code and to report a problem;
- **Donate**, which opens the [Donate](README.md#donate) section of the README.

## On iPhone and iPad

The iPhone app (iOS 26 or newer, [still in progress](README.md)) works the same way, with
these differences:

- **Alarms** are iOS alarms (AlarmKit): they sound in Silent mode and Focus, and show on
  the Lock Screen with **Stop** and **Check in**. iOS doesn't let an app lock the screen
  until you're done, so a strict check-in rings again every 3 minutes (up to 5 times)
  instead, and opens full screen when you open Gist.
- **Soft alarms** show **Stop** and **Snooze** (5 to 30 minutes, set on the check-in's
  page). To do the steps, open Gist: the check-in waits on the start page with
  **Check in** and **Dismiss**.
- **Scan steps** use the camera. On the step's page, tap **Scan the code to use**; when it
  rings, scan the same code. A product's barcode works whether it reads as 12 or 13 digits.
  After 2 minutes, **Can't scan?** swaps the scan for 5 very hard problems.
- **Add check-in** opens the same library of ready-made check-ins. Ones that need places
  are coming; ones that need run tracking or voice are Android only.
- **Not on iPhone yet:** places (arrive and leave), choosing what goes in a backup,
  photo, video and voice-clip backgrounds, run triggers and voice check-ins. A backup from
  Android imports with those left out or off.

## Honest limits

- Like Alarmy, Gist can't stop you from force-stopping or uninstalling it in Android's
  Settings. The friction is the point.
- Uninstalling deletes your logged answers. Export a backup first.
- Some phone makers stop background apps aggressively. If alarms are late or missing,
  turn off battery optimization for Gist and see <https://dontkillmyapp.com>.
