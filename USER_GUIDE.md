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
- [Profiles (Android)](#profiles-android)
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
- **+** (on the **Check-ins** tab) opens the **library**: ready-made check-ins grouped by
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
  - **Cards open one at a time.** Folded, a card is one line: its name, next ring (or
    **Waiting**, for a no-alarm one that came due) and switch. Tap a card (its title or anywhere
    that isn't a button or switch) to open it; whichever was open folds. Tap the open one to
    fold it too. New ones start folded, and switching tabs folds whichever was open. An
    open card shows the first few lines of its **Description**.
  - **Drag a card by its ≡ handle** to put your check-ins in your own order.
- **View** (above the list, on every tab) holds both how the list is
  grouped and how it's sorted. Each choice applies right away. The button shows the
  current sort. **Each tab has its own View** (and its own folded groups),
  so check-ins can sort by **Next alarm** while tasks stay in **My order**: drag the most
  important to the top.
  - **By category** (a checkbox): a section per category, A–Z, with uncategorized
    check-ins under "Other". Unticked, it's one list.

  Set a check-in's **Category** on its page, either one you've used or a new one.
  Check-ins from the library come with the library's category. When grouped:
  - you can drag cards within a section;
  - drag a **group** by the ≡ on its header to move it above or below the others;
  - tap a group's header to collapse or expand it.
- **Check-ins · Tasks · Backlog** are tabs at the top of the start page; the lit one is
  shown. **Check-ins** are everything that isn't a task (they still ring while another tab
  is shown); **Tasks** are active tasks, with the **Done tasks** button; **Backlog** is
  tasks that are off. The tab is remembered.
- **+**, to the right of the tabs, adds to the tab you're on: a check-in from the library,
  a new active task, or a new backlog task.
- **Sort** (in the same View menu) orders the check-ins, within each group when grouped:
  - **My order** (the default): as you've dragged them.
  - **Next alarm:** soonest ring first.
  - **Needs attention:** lowest on-track score first, so what's slipping comes to the top.
  - **Name:** A to Z.

  Check-ins without a next ring (switched off, or only place or run triggers) or without
  a score go last, in your order. While sorted, cards can't be dragged; switch back to My
  order to rearrange them. Your order is kept underneath, and the choice is remembered.
- **Collapse all / Expand all** (when grouped) folds or unfolds the groups.
- **Dots.** A check-in has a **dot** left of its name. Tasks have none: they're on their own
  tabs.
- **On-track colors.** Each check-in's dot is filled with a color from red (0%) through
  amber to green (100%) by its score (an empty ring until it has one) (see [History, scores and
  logging](#history-scores-and-logging)), and group headers are colored by their average.
  The numbers stay out of the list. The overall score is at the top of the page, and each
  check-in's own score is at the top of its page. Screen readers read the numbers out.
- **The number at the top** follows the tab: on **Check-ins**, the overall score (a dash
  until you've answered some); on **Tasks**, how many tasks you've done in the last 7 days;
  on **Backlog**, how many went into the backlog in the last 7 days (new ones, or ones you
  turned off), even if you've picked them since.
- **Settings** (☰) is at the top left, before the title; **About** and **Donate** are in Settings. (On the iPhone it's still ⚙️ at the top right, for now.)

## A check-in's page

Tap **Edit** on a card. Changes save as you make them. The page is a stack of cards that
open one at a time, like the start page: tap a card's title to open it (the open one folds).
**Details** is open to start.

- **Details:** **Name**, **Description** (as many lines as you like), **Time commitment**
  (from **A few minutes** to **A few months**, or not set) and **Category**.
- **Schedule:** on/off, **One-time task**, its triggers (see [Triggers](#triggers)), and
  **"About yesterday"**, which logs answers to the previous day (see below). It shows when
  it rings next.
- **While it rings:** sound, background and caption (see [When it rings](#when-it-rings)).
- **When it rings:** its commitment, and its steps (for a voice check-in, what it says).
- **Try it:** a 10-second test ring, or **Play it now** for a voice check-in.
- **Delete** removes it (after asking).

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
- **Pick today's tasks:** have a set number of active tasks (1–20) for today. Tasks that
  are already active count, so you only pick the rest from your backlog: with 1 active and
  3 to have, it asks for 2 more (all of the backlog if there's less). Tick exactly that
  many and tap **Add to today**: they're turned on, so they show on the start page. With
  enough active already, the step is skipped; with an empty backlog it says so and moves
  on. A test ring changes nothing.
- **Questions:** add, edit, reorder and delete them. Each is one of:
  - **Yes / No:** says whether "Yes" or "No" is on track. **Submit** stays disabled until
    every yes/no question is answered.
  - **A number** (your weight, say) or **text** (a short note): you type the answer.
    These are always optional, since you can't always answer: answering is on track,
    leaving it empty is off track. A number can use a comma or a point ("182,4" is
    182.4); anything that isn't a number has to be fixed before you can submit.

  Changing a yes/no question to a number or text keeps its history: days answered before
  still count as they did. These are the only answers that get logged. With several
  question steps, History shows all of their questions.

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

- **Sound:** the check-in's choice: **Ring with a sound** (any of the phone's sounds), on
  the alarm stream, with vibration; **Vibrate only**; or **No alarm (just a reminder)**: no
  sound, no vibration and no alarm screen, just a silent notification, and the check-in
  waits on its card on the start page (**Do it**, and **Dismiss** if it's soft) until it's
  done. While a ringing alarm rings, the alarm volume can't go below 80%; turn it down and
  it goes straight back up. Your volume is restored afterwards.
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

## One-time tasks

For things you only need to do once ("Call John"): **+** on the **Tasks** or **Backlog** tab (or **New task** in the library), or turn
on **One-time task** on any check-in's page.

- A task is a check-in in every way (triggers, steps, questions, strict or soft,
  background), with one difference: **doing it once completes it**. That's finishing it (or
  dismissing it, if it's soft), from the alarm or with **Do it now**. It turns off and
  leaves the start page.
- **On or off: active or in the backlog.** A task that's on is active and shows on the
  start page. One that's off waits in the **backlog**, on the **Backlog**
  tab; turn it on (its switch, or a **Pick today's tasks** step) to make it active.
- **A new task starts in the backlog:** no triggers, its alarm off, soft, and set to **No
  alarm (just a reminder)**. On the **Backlog** tab it shows **In the backlog**,
  with **Do it now**.
- **To be reminded,** add a time trigger (its **Days** default to **On a specific date**)
  and turn **Alarm on**. It then reminds you quietly; pick a sound on its page for a real
  alarm. Arrive / leave and run triggers ("when I get to the store: buy batteries") work
  any day until it's done.
- **Done tasks** (at the bottom of the start page) lists them, most recent first, with when
  you did them and what you answered, so you can check whether you did something. **Put
  back** returns one to the list (pick a new date if it has passed); **Delete** removes it.
- **Follow-ups** keep a job going one step at a time (sand the pool patch, then replace the
  liner), so you don't have to plan every task up front. When you finish a task, a **Task
  done** screen offers **Create follow-up** (or **Close**); it's also on each task's page
  (Schedule) and on each one in **Done tasks**. You choose **Add to backlog** or **Add to
  today**, and the new task's page opens. It starts as a copy, named **(Follow-Up) Sand the
  pool patch** so you can tell you're on the new one, with the same description, category,
  time commitment, commitment, sound and steps, but no set time. Its page shows **Follows:**
  the task before it, and the earlier one shows **Followed by:** (tap either to open it).
  Give the job's tasks one category (say "Pool") to keep them together when grouped.
- A task whose times have passed without being done stays on the list with **Do it now**,
  and its page says so.
- Tasks don't count toward the overall score (each still has its own History).

**On a specific date** works on any time trigger, not just tasks: a daily check-in can have
one extra time that rings once.

Backups with a task, a time on a specific date or a pick-tasks step are version 5 (both
apps read them); backups without them stay version 4.

## History, scores and logging

- **History** (on a check-in's card) shows its last 30 days:
  - logged and on-track counts;
  - how often each question went off track;
  - a ✓/✗ grid, with what you entered for number and text questions. Tap a day to fill
    it in or fix it;
  - **how long it takes** (Android for now): **getting started**, from the alarm to
    **Start check-in**, and **doing it**, from there to done. It shows the middle values
    (medians) for the last 7 and 30 days, a bar per day and the last few days. A day with
    several times shows their average. Strict check-ins only: soft ones, tests and early
    check-ins' "getting started" (they didn't ring) aren't timed. The CSV export has both,
    in seconds.
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

**Settings** is the ☰ at the top left of the start page (⚙️ at the top right on the iPhone, for now).

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

## Connections (Android, early)

Share check-ins and tasks with another copy of Gist, your partner's say, **read-only**:
they see what you share, but only you can change it, and the other way round.

- **Your Gist folder.** On Android, Gist keeps everything it syncs in
  **/storage/emulated/0/PublicAppData/Gist**, with a folder for each profile:

  ```
  PublicAppData/
    Gist/
      Personal - 7xq2/   one per profile: its name and a short code
        My devices/      shared with your own devices
        People/
          Jess/          shared with Jess only (one folder per connection)
      Work - k3f9/
  ```

  You don't pick it. Android needs you to **allow access to PublicAppData** once on each
  device (Settings → Connections → **Allow access to PublicAppData…**, or when you first
  invite or join). If it isn't there yet, make it in the picker that opens: a new folder
  at the top of your storage, named exactly **PublicAppData**, then **Use this folder** and
  **Allow**. Any other folder is refused. After that Gist makes everything inside it, for
  every profile, with nothing more to do. A profile's folder is named when it's first
  needed and keeps its name if you rename the profile (the app you share folders with
  points there). Don't share a profile's folder itself; share each folder in it.
- **Settings → Connections.** Add **your name** (what they see). One side taps **Invite…**
  and says who it's for: Gist makes People/Jess and shows an **invite**, a QR code with a
  code under it (like `AFYW-…`). Share People/Jess with them. The other taps
  **Join…** and **scans** the QR code (or taps **Type the code**, for a device without a
  camera). Gist finds their folder under People once it has arrived; until then
  it says it's waiting, and joins by itself as soon as it's there. Share it into People
  in your profile's folder.
- **The invite is the key to the door.** It never goes in the folder, so someone who can
  see the folder but not your screen can't join. It works **once**, for **15 minutes**;
  **Show the invite** (on a connection still waiting) shows it again, or a new one if it
  ran out. Pass it in person or over a channel you trust, not through the shared folder.
  Both phones then show the same **code** (like `4F7A-91C2`), which you can still compare.
- **Between phones** the folders have to be kept in step by an app that shares folders
  between devices, such as [Syncthing](https://syncthing.net). Gist never goes online
  itself, and works with whatever keeps the folders in step. **To try it on one phone**, install **Gist B** (the `gist-b-…apk` on the release
  page): a second copy with its own data, which needs access to PublicAppData too. Its
  first profile has the same folder as Gist's (PublicAppData/Gist/Personal - …), so the
  two meet there just as two phones sharing the folder would.
- **Share with…** on a check-in's or task's page (Details) picks which connections see
  it. Changes go out the next time Gist is opened or you leave the page.
- **What they share** shows on your start page under **From Jess**, on the tab it belongs
  to (check-ins, active tasks, or the backlog), read-only. Turn **Show on the start page**
  off on the Connections page to hide a connection for a while.
- **Encrypted:** everything shared is encrypted with a key only the two phones can work
  out (it's never written down), and a file that's been changed or swapped is ignored.
  The folder also holds each side's name and public key, which aren't secret.
- **Add to my list** (on something they share) puts it on your own list: its alarms,
  times, steps, commitment and category are yours to set, while its **name and
  description stay theirs** (they change when they change them, and can't be edited
  here). It's marked **From Jess**; if they stop sharing it, it stays, marked as removed.
- **Who can do a shared task:** on your task's page, **Who can do it** is **Anyone** (you
  or anyone it's shared with: the first to finish it finishes it for everyone), **Only
  you**, or **Only Jess**. They see "anyone can do it", "assigned to you" or "only Alex can
  do it", and can only add the ones they may do. When they finish one of yours, it's done
  on your side too, **by Jess** in Done tasks; when you finish one they added, it's done
  on theirs.
- **Tidy folders:** each side notes how far it has read the other's changes, and changes
  everyone has read are cleaned out of the folder (a snapshot covers anyone new).

### Your devices

**Settings → Connections → Your devices** keeps everything on your phone and tablet (say)
in step: check-ins, tasks and answers. **Set up…** on one: Gist makes **My devices** in your
Gist folder; share it with your other devices (into their Gist folders, as My
devices). Then, for each of the others: tap **Add a device…** on one that's in, which shows
an invite (a QR code and a code to type, good once for 15 minutes); on the new one tap
**Join…** and scan or type it; Gist finds My devices by itself (waiting for it if
it hasn't arrived yet). The new
device waits to be let in until Gist opens on the one that showed the invite; only that
device lets it in, and the new one takes the shared key only from it. Each pair then shows
the same code.

- **Edit on any.** The later edit of a check-in wins, and a delete reaches them all (unless
  it's been changed since).
- **Answer once.** Answering (or dismissing or skipping) on one counts on all: once the
  others hear, they stop waiting and ringing for it. An alarm screen checks every 20
  seconds; other times, devices catch up when Gist is opened.
- **Ring on this device** (on the same page) turns ringing off for one device: check-ins
  then remind quietly there and wait on the start page, where you can still **Check in**
  or **Dismiss** them.
- **Your list and settings.** Dragging check-ins or groups into a new order, folding a
  group, and the settings for each tab's view and sort, the theme and the pause after a
  tap all reach your other devices. The later change wins, one setting at a time, so
  changing the theme on one and the sort on another keeps both. Joining takes the
  others' order and settings. **Ring on this device**, the tab you're on and the card
  that's open stay per device.
- **Photos, videos and voice clips.** A check-in's background photos or video and its
  voice clip reach your other devices too (the later change wins). Each file goes in the
  folder once, encrypted, so it's copied between devices once; a device that gets the change
  before the file waits for it. They're as big as Gist lets them be anywhere: photos are
  shrunk when you pick them, and videos and clips have a size limit.
- **Stopping or removing a device.** **Stop syncing on this device** keeps everything on it
  and takes its files out of the folder, so your other devices stop listing it. For a
  device that's lost or that you can't open, tap **Remove** next to it on any of your
  others: your devices switch to a new key it doesn't have, so it can't read anything
  synced from then on (what's already on it stays on it).
- **Connections with other people.** Each connection is **kept on** one of your devices,
  the one you invited or joined on: it reads what they share and shares for you. Your
  other devices show the connection and what they share, and anything you do there
  (share an item, add one of theirs, finish a task for them) reaches the home device
  through your devices and goes out from there. Your name is the same on all of them. On
  a connection kept elsewhere, **Keep it on this device instead…** takes it over (pick
  its folder as this device reaches it): for when the old device is gone or won't be
  opening Gist, since two sharing at once could clash. Remove a connection on the device
  it's kept on; your others forget it too.
- **Coming next:** the iPhone.

## Profiles (Android)

A profile is a whole separate setup on the same phone, say **Personal** and **Work**. Each
has its own check-ins and tasks, answers and history, settings (theme, pause length,
whether this device rings), Gist folder, devices and connections, and photos and clips.
Nothing is shared between them.

- **Making one.** Settings → Profiles → **New profile…**. It starts empty, and Gist
  switches to it. Your first profile is called Personal; rename any of them there.
- **Switching.** Once you have more than one, every page shows the profile you're in at
  its top right: tap it to switch (or to make a new one). Settings → Profiles has
  **Switch** too.
- **Every profile rings.** Alarms, place and running triggers, and quiet reminders keep
  working for all your profiles, whichever one you're on. When another profile's alarm
  rings, the alarm screen says which profile it's from, your answers are saved in that
  profile, and you stay on the one you were on. Its notifications are titled with the
  profile's name.
- **Waiting elsewhere.** If another profile has check-ins waiting, the start page shows
  it, with **Switch** to go there.
- **Deleting one.** Settings → Profiles → **Delete**, on any profile but your first and
  the one you're on. Its alarms stop and everything it kept on this phone is deleted;
  what it already synced to its Gist folder stays there.
- **Coming next:** profiles on the iPhone.

## About

**About** (Settings → About) shows:
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
- **Add check-in** opens the same library of ready-made check-ins. Ones that need run
  tracking or voice are Android only.
- **Places** work as on Android: **Also rings → Add arrive or leave**, and each time's
  **Where** (anywhere, or only when you're at or away from a place). Pick a place with
  your current location, coordinates from a maps app, or one you've used. iOS itself
  watches the places, even with Gist closed, so allow location **Always** (the start page
  asks). A place you just added waits about 90 seconds before it can ring. iOS watches up
  to 18 places across all check-ins.
- **Backups** let you choose what goes in and comes in, as on Android.
- **Backgrounds and captions** (a photo, a slideshow or a muted looping video) are set
  under **While it rings**, as on Android. iOS shows its own alarm screen, so the
  background appears once you open the check-in, with **Start check-in** at the bottom.
  Videos are converted to MP4 so they play on Android too, and backups carry the media
  both ways.
- **Run triggers** (starting a run, finishing one, or so many minutes into one) work as
  on Android: Gist counts steps all day while any check-in has one. iOS only lets an app
  keep counting in the background with location on, so Gist keeps location on at its
  lowest accuracy (cell towers and Wi-Fi, no GPS) and ignores where you are. Allow
  **Motion & Fitness** and location **Always** (the start page asks). It uses some battery;
  turn off the check-ins with run triggers to stop it.
- **Voice check-ins** speak (or play your clip) through your headphones, with your music
  dipping, as on Android. Runs and places set them off any time. Their set times speak
  only while Gist is awake: open, or watching for runs.
- **Tasks, the backlog and picking today's tasks** work as on Android, with the same start
  page: **Check-ins · Tasks · Backlog** as a segmented control with **+** beside it, a
  View per tab, the number above for each tab, and cards that open one at a time. The
  gear at the top right opens Settings (with About and Donate). **My order** is changed
  with **View → Change my order…**, for the tab you're on.
- **No alarm** (under **While it rings → Sound**) reminds with a quiet notification (no
  sound, and it doesn't light the screen); the first time, iOS asks to allow
  notifications. It then waits on its card on the start page.
- **A check-in's page** has the same cards (Details, Schedule, While it rings, When it
  rings, Try it), opening one at a time; **History** shows how long it takes as a chart.
- **Follow-ups** work as on Android: finishing a task opens a **Task done** sheet with
  **Follow-up to the backlog**, **Follow-up for today** or **Close**; **Create follow-up**
  is also on a task's page and in **Done tasks**. An open card shows the first lines of
  its description, and switching tabs folds it.

## Honest limits

- Like Alarmy, Gist can't stop you from force-stopping or uninstalling it in Android's
  Settings. The friction is the point.
- Uninstalling deletes your logged answers. Export a backup first.
- Some phone makers stop background apps aggressively. If alarms are late or missing,
  turn off battery optimization for Gist and see <https://dontkillmyapp.com>.
