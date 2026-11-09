# Architecture

How Gist is put together, for anyone reading or changing the code. To build it, see
[BUILDING.md](BUILDING.md).

## Design choices

- **Platform APIs only.** No AndroidX, no Google Play services, no Compose. Layouts are
  built in Kotlin code with a few small helpers (`Ui.kt`). That keeps the APK small and
  the dependency list to two libraries (ZXing and SnakeYAML Engine), and it means nothing
  needs Google's servers.
- **No internet permission.** Location uses Android's own `LocationManager` proximity
  alerts. Text-to-speech uses whatever engine the phone has. Scanning decodes on the
  device.
- **Alarms are alarm clocks.** Every ring is scheduled with
  `AlarmManager.setAlarmClock`. That's exact, survives Doze, and lets the ringing service
  start from the background. Place and running triggers ring through a one-off alarm
  clock alarm for the same reason.
- **Simple storage.** Check-ins, answers and settings live in one `SharedPreferences`
  file as JSON (`org.json`). Backups are the same JSON written as YAML through a small
  bridge (`Yaml.kt`). Background photos and video, and voice clips, are files in the
  app's private storage.
- **The alarm screen has its own task** (`taskAffinity` in the manifest). It shows over
  the lock screen, and closing it never disturbs whatever Gist screen was open.
- **Logic is kept testable.** Scheduling, triggers, run detection, scores, grouping,
  repeat rules and text are plain functions on plain data, covered by fast JVM unit
  tests. Everything that needs a device is covered by UI tests ([TESTING.md](TESTING.md)).

## How a ring flows

1. `AlarmScheduler` arms each check-in's next timed ring. `LocationTriggers` and
   `MovementWatch` watch places and runs.
2. When it's due, `AlarmReceiver` checks the trigger's conditions (days, where, already
   done or skipped).
3. What happens next depends on the commitment:
   - **Strict and soft:** the check-in joins the pending list, and `AlarmService` starts
     ringing (with a volume ramp, a volume guard, bring-back-to-front for strict, and a
     60-minute safety stop).
   - **Voice:** `VoiceService` speaks the line or plays the clip, and nothing waits.
4. `CheckinActivity` shows the ringing screen and runs the steps in order, pausing the
   sound while you work.
5. Answers are saved to `Store`. `Score` and `Stats` read them back for the start page
   and History.

## Files

All code is in `app/src/main/java/dev/gist/checkin/`.

**Screens**

| File | What it is |
|---|---|
| `MainActivity.kt` | the start page: setup card, check-in list, grouping, folding, scores |
| `CheckinEditActivity.kt` | a check-in's page: name, triggers, sound, background, commitment, steps, voice |
| `StepEditActivity.kt` | one step's page: math level, scan code, questions |
| `CheckinActivity.kt` | the alarm screen: ringing, pause, the steps in order; also for editing a past day and "Do it now" |
| `HistoryActivity.kt` | one check-in's last 30 days: stats, per-question counts, day grid |
| `LibraryActivity.kt` | "Add a check-in": the template library, or start blank |
| `ScanActivity.kt` | registers a code for a scan step |
| `SettingsActivity.kt` | appearance, pause length, backup export / import, CSV |
| `AboutActivity.kt` | version, privacy, source code, Donate |
| `BaseActivity.kt` | shared theme handling (light / dark override) |

**Ringing and triggers**

| File | What it is |
|---|---|
| `AlarmScheduler.kt` | next occurrences and arming them with `setAlarmClock`; test rings; snooze |
| `Receivers.kt` | alarm fired → check conditions → ring or speak; reboot or clock change → re-arm |
| `AlarmService.kt` | sound, vibration, volume ramp and guard, pause, bring-back-to-front, safety stop |
| `SoftAlarm.kt` | Snooze and Dismiss for soft alarms (ringing screen and notification) |
| `VoiceService.kt` | voice check-ins: text-to-speech or a clip, through headphones, ducking other audio |
| `Notif.kt` | notification channels, the full-screen alarm notification, reminders |
| `Trigger.kt` | trigger kinds (time, arrive, leave, run start / finish / partway) and their conditions; places |
| `LocationTriggers.kt` | place watching with proximity alerts, and "where am I?" checks |
| `RunDetector.kt` | notices runs starting and finishing from step-counter readings |
| `MovementWatch.kt` | the step-watching service, and ringing on run start, finish and milestones |
| `Repeat.kt` | day rules: every day, weekdays, chosen days, monthly dates |

**Steps**

| File | What it is |
|---|---|
| `Steps.kt` | steps (any number, any kind, in order) and what each needs |
| `MathGen.kt` | math problems at four levels |
| `ScannerView.kt` | camera preview with on-device QR and barcode decoding (Camera2 + ZXing) |

**Data**

| File | What it is |
|---|---|
| `Store.kt` | check-ins, results, pending rings and settings (SharedPreferences + JSON); the default check-ins (`DEFAULT_CHECKINS`) |
| `Backup.kt`, `Yaml.kt` | backup export and import: selective, with history, media and voice clips; YAML via SnakeYAML Engine |
| `Backgrounds.kt` | stored background photos and video, and voice clips |
| `Background.kt` | background type (none, photo, slideshow, video) and caption |
| `SafeUri.kt` | rejects picked files that point into the app's private storage |
| `Days.kt` | date helpers, including the before-4 AM rollover |

**Start page logic and text**

| File | What it is |
|---|---|
| `Score.kt` | on-track scores and their red → green colors |
| `Stats.kt` | logged and on-track counts, day marks |
| `Grouping.kt` | ungrouped or grouped by category, group order, folding |
| `CheckinText.kt` | short descriptions: times, triggers, steps, next ring |
| `Library.kt` | the ready-made check-in templates |
| `Links.kt` | the web pages the app opens (repository, donate, issues) |

**UI helpers**

| File | What it is |
|---|---|
| `Ui.kt` | small helpers for building layouts, buttons, cards and styled dialogs in code |
| `ReorderList.kt` | drag-to-reorder lists (steps, check-ins, groups) |

## Tests and tooling

| Path | What it is |
|---|---|
| `app/src/test/` | JVM unit tests ([TESTING.md](TESTING.md)) |
| `app/src/androidTest/` | Espresso and UI Automator tests on an emulator |
| `app/src/debug/AndroidManifest.xml` | lets the screenshot script open screens directly (debug builds only) |
| `.github/workflows/` | Build, Release, Screenshots, CodeQL, Security report ([RELEASING.md](RELEASING.md), [SECURITY.md](SECURITY.md)) |
| `.github/scripts/` | Gradle runner, UI test runner, screenshot script and demo data, test and dependency reports |
