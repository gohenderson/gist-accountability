# Testing

This page covers the Android app. There are two suites. GitHub Actions runs both on every
push to `main` and every pull request that touches `android/`, `shared/` or `.github/`
(other pull requests, like docs-only ones, skip them), and **Release** runs them first and won't publish if any fail
([RELEASING.md](RELEASING.md)). The test libraries are test-only; they're never
included in the APK you install.

## Running them locally

From Android Studio, or:

From the repository root:

```
gradle -p android testDebugUnitTest            # unit tests, no device needed (seconds)
gradle -p android connectedDebugAndroidTest    # UI tests, with a phone or emulator connected (minutes)
```

To run one UI test class:

```
gradle -p android connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.gist.checkin.LockScreenTest
```

The UI tests change device state while they run: they lock and unlock the screen, set
and clear a PIN, and grant permissions. Run them on an emulator or a spare phone, not
the phone you rely on for alarms.

## Unit tests

`android/app/src/test`, on the plain JVM. They cover:

- **Next alarm:** with several times, the next alarm is the soonest one ahead and wraps to
  tomorrow's first. Daylight-saving changes are handled.
- **Math:** problems always have the right answer.
- **Dates:** the before-4 AM rollover, and "previous day" check-ins across months and
  years.
- **Repeat rules:**
  - weekdays skip the weekend;
  - monthly on the 1st or the last day, including February and leap years;
  - missing dates are skipped;
  - the next ring after Friday evening is Monday;
  - the descriptions read right.
- **Place triggers:** time windows across midnight, days, once a day, at / away, unknown
  location, distances, and parsing coordinates.
- **Running:** the detector's start and end (cadence, red lights, walk breaks),
  minimum-duration runs, and "partway into a run" milestones (timing, once a day vs every
  run, wording).
- **Text and descriptions:** stats, step descriptions, background descriptions, and what a
  voice check-in says.
- **Scores and grouping:** on-track scores and colors, categories and their order,
  folding and Expand all / Collapse all.
- **Sorting:** My order, Next alarm (no next ring last), Needs attention (no score last;
  ties keep your order), Name (ignoring case).
- **Commitment:** strict, soft and voice, and which ones count.
- **Library:** the templates are well formed, and copies get free names.
- **README:** keeps the `## Donate` heading the app's Donate button links to.

## UI tests

`android/app/src/androidTest`: Espresso and UI Automator on an Android 15 emulator. By area:

- **Shared backups:** every sample in `shared/backups/` imports with every setting intact
  (times with their own days, a place condition, all step kinds, place and run triggers,
  soft and voice check-ins, history with every status), and survives an export and
  re-import. The iOS app's tests will import the same files.

- **End to end:** a real alarm fires while the screen is off, the alarm screen takes
  over, the alarm rings until the question is answered, and the answer is saved.
- **The alarm screen:**
  - **Getting out:** it can't be escaped with Back. Leaving a ringing alarm with Home
    brings it back to the front, both while ringing and after Start check-in.
  - **Steps:** Submit stays disabled until every question is answered; a wrong math
    answer gives a new problem; steps run in order; with no camera, the scan step offers
    the math fallback; a check-in with no steps only needs Dismiss.
  - **More than one:** two waiting check-ins run back to back.
- **Ringing:**
  - **Volume:** the first ring starts soft and ramps up to full volume; after a pause it
    resumes at full volume.
  - **Sound:** a real alarm plays the check-in's chosen sound; Silent plays nothing; a
    missing sound falls back to a default.
  - **Backgrounds:** a photo, a cycling slideshow and a playing (muted) video show behind
    the ringing screen, with the caption; caption-only works.
  - **Layout:** Start check-in sits in the bottom half.
- **Pausing:**
  - Start check-in silences a real ringing alarm and shows the countdown bar.
  - Each tap restarts the countdown; when it runs out the alarm rings again, and progress
    is kept.
  - The pause defaults to 45 s and can be changed in Settings.
- **Test rings:**
  - A test ring is marked as a test, logs nothing, and leaves the next real ring alone.
    End test stops it part-way.
  - A test rung from a check-in's page, with the phone locked and ended on the lock
    screen, leaves that page working after unlocking. Three variants: swipe lock, PIN lock
    with "display over other apps", video background.
- **Soft alarms:**
  - **Dismiss** stops it without the steps (logged as dismissed, soft). With no steps,
    Dismiss is the main button. The steps can still be done or abandoned.
  - **Snooze** rings the same time again later. The notification's Snooze / Dismiss work.
    Home doesn't pull it back.
  - **Strict alarms** have no Snooze, Dismiss or notification buttons.
  - **Stats:** soft results stay out of scores and averages, even after switching to
    strict.
  - **Settings:** commitment and snooze length are set on the check-in's page and kept in
    backups.
- **Voice check-ins:**
  - **Playing:** they speak instead of ringing (no alarm, nothing waiting, not counted).
    They stay quiet without headphones unless that's turned off. A recorded clip plays.
  - **Runs:** "partway into a run" fires at its time, once per run.
  - **Backups:** the clip goes into backups.
  - **Setup:** they're set up on the check-in's page (no steps or background; the run
    time is picked). The start page shows what one says, and no "Do it now".
- **Steps:**
  - **Order:** they're listed in their order; dragging Questions above Math reorders them,
    and the alarm runs the custom order.
  - **Repeats:** the same kind can run several times (math → questions → math); Math can
    be added again and again, and + Add step never disappears.
  - **Setting up:** a step that isn't set up says so and is skipped; adding Math opens its
    page and the difficulty saves; the scan spot name saves; up to 100 problems per step.
  - **Editing:** Duplicate copies a step; removing a step keeps the others' order.
  - **Old data:** check-ins saved before steps could repeat keep their steps.
- **Triggers:**
  - **Removing:** removing the last trigger leaves the check-in silent and says so.
  - **Adding:** + Add trigger, then turning it into "when I leave" with typed coordinates,
    keeps the time and adds the event.
  - **Places:** two places on one check-in are both watched, and either can ring.
  - **Old data:** a check-in saved with the old single trigger is converted.
  - **Per-time days:** they're saved and loaded. 4:00 AM "Tue" plus 4:45 AM "every day but
    Tue" set on the edit page arm the real alarm, and the next rings follow each time's
    days. Changing a time keeps its days.
- **Places:**
  - **Arriving:** arriving rings (once a day); leaving, or being outside the time window,
    doesn't.
  - **Conditions:** "only when at" skips when elsewhere and rings when there; "only when
    away" rings when elsewhere.
  - **Watching:** the place is watched while the check-in is on, and not when it's off.
  - **Setup:** choosing "arrive" and typing coordinates on the edit page saves the place
    and starts watching it.
- **Running:**
  - **Start and finish:** a run starting rings a start trigger; a finish rings only after
    a long enough run.
  - **The step watcher:** it runs only while needed (or says the phone has no step
    sensor).
  - **Setup:** choosing "finish running" and a minimum on the edit page saves them.
- **Several times a day:**
  - **Results:** each time's results are saved separately. History shows ✗ for a day if
    any time was off track or missed, and – for skipped days; fixing one time updates it.
  - **Removing times:** removing a time re-arms the alarm for the rest, and the last time
    can't be removed.
- **Do it now / Skip:**
  - **Skip** moves the alarm past that ring, and Undo restores it.
  - A skipped or early-resolved ring stays quiet when its time comes.
  - Early scanning has no fallback.
- **The start page:**
  - **Switch:** a card's switch turns its alarm off and on.
  - **Grouping:** grouped view shows a section per category, with "Other" last. The View
    menu holds By category and the four sorts; sorting keeps the grouping, and unticking
    By category ungroups.
  - **Dragging:** dragging a card moves it. Dragging a group moves it (your order first,
    then A–Z, "Other" last).
  - **Folding:** a group collapses and expands. Tapping a check-in's title folds it and
    back. Collapse all / Expand all folds groups and check-ins, also when ungrouped. The
    old Compact setting starts everything folded.
  - **Card styles:** folded and open cards share their background, corners and name size.
  - **Sorting:** Next alarm and Name reorder the list (switched-off check-ins last) and
    hide the drag handles, and your own order is kept. Needs attention sorts within each
    group. The choice is remembered.
  - **Scrolling:** folding while scrolled down never draws a frame at the top.
  - **Scores:** check-ins and groups show their score as color only (screen readers read
    the number); the overall score is at the top; a check-in's page shows its own; no
    history means no score.
- **A check-in's page:**
  - **Editing:** renaming saves as you type; questions can be added and reordered; a
    category can be set.
  - **On / off:** turning the alarm off and on cancels and reschedules the real alarm.
  - **Repeat rules:** Weekdays and Monthly-on-the-1st move the real alarm to the right
    day.
  - **Delete** works.
- **History:**
  - **Stats:** they're correct.
  - **Fixing a day:** fixing a day from the grid updates them.
- **Storage and backups:**
  - **Old data:** saving and loading work, including data from the first version.
  - **Round trip:** export, then import on a "fresh phone", restores check-ins, step
    order, answers, slideshow photos and video.
  - **Choosing:** only the chosen check-ins (and history) are exported.
  - **Existing check-ins:** importing one you already have can update it (history merged
    or kept), replace it (history too, or none) or add a copy ("Wake (imported)").
    Unchosen check-ins are left alone.
  - **Dialogs:** the export and import dialogs pass on what you tick.
  - **Hand-edited files:** hand-edited YAML imports; bad files are rejected without
    changing anything.
- **Library:**
  - **Opening:** + Add check-in opens it.
  - **Adding:** adding a template copies it (new id, schedule, questions), arms its alarm
    and opens it. A second copy gets "Water 2".
  - **Create your own** starts blank.
- **Settings and About:**
  - **Appearance:** Light and Dark override the phone's setting.
  - **About:** it opens from the start page and shows the version, Donate and the source
    link.

## iPhone app

The **iOS** workflow runs on a macOS runner for pull requests and pushes that touch
`ios/` or `shared/`:

- **App (simulator build):** generates the Xcode project, builds the app for the
  simulator, then opens each screen with demo data (debug builds only) and takes
  screenshots: the start page (light and dark), a check-in's page, a test ring, History,
  Settings and the Add check-in library, and the start page and a test ring on an iPad. They're attached to the run
  and published to the `ios-screenshots` branch. It also builds the unsigned **Gist.ipa**
  for real devices (the Gist-ipa artifact).
- **GistCore (swift test):** the logic's tests, in `ios/GistCore/Tests`.

The GistCore tests cover:

- **Shared backups:** every sample in `shared/backups/` parses, `every-feature.yaml`
  imports with every setting intact (the same checks as Android's `SharedBackupTest`), and
  import → export → import loses nothing.
- **YAML:** the YAML 1.2 core schema (`20:09` and `2026-11-08` stay text), quoting text
  that would read back as something else, key order, comments, bad or duplicate-key files.
- **Backups:** the error messages, choosing what to export, media, and every import mode
  (new, update, replace, copy as "… (imported)", matching by id then name).
- **Logic** (ports of the Android unit tests): the 4 AM rollover, repeat rules, each time's
  own days, the next alarm, daylight saving, monthly dates, math answers, day marks,
  scores and colors, the logging rules, sorting and grouping.
- **Alarm plan:** a strict check-in's next ring plus its re-rings every 3 minutes; soft
  ones ring once; off and voice ones don't; done or skipped rings move to the next one;
  waiting strict check-ins keep re-ringing until done; test rings; which rings are
  waiting; snoozed soft alarms ring again (unless dismissed or already past).
- **Library and scanning:** the 19 templates (like Android's), which work on iPhone, adding
  a copy ("Water 2"), and matching scanned codes (UPC-A as 12 or 13 digits).

Camera scanning needs a real device, so check it by hand: register a code on a scan step,
test-ring the check-in, scan a different code (it says so), then the right one.

## When a test fails

- **The run page** lists each failure with its message, and links to the exact line of
  test code.
- **The ui-test-logs artifact** has:
  - the HTML report;
  - a screenshot of the screen at the moment each UI test failed;
  - the emulator's full log.
