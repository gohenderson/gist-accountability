# Gist — an un-dismissable daily diet check-in

Android alarms that ring every day and won't stop until you complete the check-in's
steps: **solve math problems → scan a QR code / barcode → answer yes/no questions**
about what you ate. Each step is optional, so a check-in can be a plain hard-to-dismiss
wake-up alarm, a diet survey, or both. You can have as many check-ins as you like. It keeps a simple log so you can see the *gist* of your
diet without counting calories.

- **One dependency:** [ZXing core](https://github.com/zxing/zxing) (Apache-2.0), an
  open-source barcode decoder written in plain Java, for scanning. Everything else
  (camera, UI, storage) uses Android's built-in APIs. There's no AndroidX and no Google
  Play services.
- **No internet permission.** Nothing ever leaves your phone. Answers live in the app's
  private storage, and the only way out is the **Export CSV** button.
- About 1,900 lines of Kotlin in 12 files. You can read all of it in one sitting.

## Build & install

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** → choose this `Gist` folder. Let Gradle sync. Android Studio downloads
   Gradle and the Android SDK itself. If it offers to upgrade the Android Gradle Plugin,
   accepting is fine.
3. On your phone: **Settings → About phone → tap "Build number" 7 times** to enable
   Developer options. Then turn on **Developer options → USB debugging**.
4. Plug the phone in, pick it in the device dropdown, and press **Run ▶**.

Requires Android 10 or newer.


## Builds & releases (GitHub Actions)

- **Every push to `main`** (and every pull request) runs **Build**: a debug APK, unit
  tests, lint, and UI tests on an emulator (see Tests below). The debug APK and all
  logs and reports are attached to the run as artifacts.
- **Release** runs the full Build workflow (all tests) first, then builds a signed APK and
  publishes it as a GitHub release, with a `.sha256` checksum next to it. Start it either way:
  - **Actions → Release → Run workflow**, then enter a version like `v1.0.0`. The tag is
    created on the latest `main` if it doesn't exist. Running it for an existing tag
    rebuilds that release.
  - or push a tag: `git tag v1.0.0 && git push origin v1.0.0`
  Each release run pauses until you approve it (see the signing key setup below).

- **Screenshots** run automatically after each release. An Android emulator on GitHub
  opens the app with demo data and captures the start page, history, a check-in's edit
  page, and the alarm screen (math and questions). The PNGs are attached to the release
  and shown in its notes. If this step fails, the release is still published. To make
  screenshots without releasing, use **Actions → Screenshots → Run workflow** and leave
  the tag empty. To make this possible, *debug* builds let these screens be opened
  directly from the command line (`app/src/debug/AndroidManifest.xml`). The release
  APK you install doesn't.

**When something fails:** compile errors appear as annotations on the run page and on
the exact lines of code. The job summary shows Gradle's "What went wrong" section and
the end of the log. The **build-logs** / **release-logs** artifact has the full Gradle
logs plus the lint and build reports.

### One-time setup: a signing key

Android only installs an update if it's signed with the same key as the installed app,
so releases need one permanent key. You create it once, on your computer, and store it
as secrets in a protected `release` environment. It never needs to be in the repo.

1. Create the key. `keytool` comes with Android Studio, in its `jbr/bin` folder.
   ```
   keytool -genkeypair -v -keystore gist-release.jks -alias gist \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
   It asks for a password and some name fields. The name fields can be anything.
2. Base64-encode it:
   - macOS / Linux: `base64 < gist-release.jks | tr -d '\n' > gist-release.b64`
   - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\gist-release.jks")) | Set-Content -NoNewline -Encoding ascii gist-release.b64`
3. On GitHub, open **Settings → Environments → New environment** and name it `release`.
   - Under **Deployment protection rules**, tick **Required reviewers**, add yourself, and
     **Save protection rules**. Every release run then waits for you to approve it
     (Actions → the run → **Review deployments**) before it can read the signing key.
   - Optionally, under **Deployment branches and tags**, choose **Selected branches and
     tags** and add a tag rule `v*`.
   - Under **Environment secrets → Add environment secret**, add:

   | Secret | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | contents of `gist-release.b64` |
   | `ANDROID_KEYSTORE_PASSWORD` | the password you chose |
   | `ANDROID_KEY_ALIAS` | `gist` |
   | `ANDROID_KEY_PASSWORD` | the same password, unless you set a separate key password |

   Add them **only** as environment secrets, not as repository secrets. Any workflow can
   read repository secrets without approval.

4. **Back up `gist-release.jks` and the password** somewhere safe, such as a password
   manager. If you lose them, future releases can't update the installed app, and you'd
   have to uninstall it, which deletes your logged answers. Export a CSV first.

To install a release, open its `.apk` on your phone and allow "Install unknown apps" for
your browser or file manager when Android asks.

## Tests

Two kinds, both run by GitHub Actions on every push to `main` and every pull request.
**Release** runs all of them first and won't publish if any fail.

- **Unit tests** (`app/src/test`, plain JVM, seconds): math problems always have the right
  answer; date rules (before-4 AM rollover, "previous day" check-ins across months and
  years); next-alarm time, including daylight-saving changes; stats; step descriptions.
- **UI tests** (`app/src/androidTest`, Espresso + UI Automator on an Android 15
  emulator, a few minutes):
  - pausing: Start check-in silences a real ringing alarm and shows the countdown bar;
    each tap restarts the countdown; when it runs out the alarm rings again and progress
    is kept
  - the alarm screen can't be escaped with Back; Submit stays disabled until every
    question is answered; a wrong math answer gives a new problem; steps run in order;
    two waiting check-ins run back to back; with no camera, the scan step offers the math
    fallback; a check-in with no steps only needs Dismiss
  - edit page: rename saves as you type; add and reorder questions; turning Ring daily
    off/on cancels and reschedules the real alarm; delete
  - History: correct stats, and fixing a day from the grid updates them
  - saving and loading, including data from the first version
  - **end to end:** a real alarm fires while the screen is off, the alarm screen takes
    over, the alarm rings until the question is answered, and the answer is saved

The test libraries are test-only: they're never included in the APK you install.

**When a test fails:** the run page lists it with its message and links to the exact
line of test code. The **ui-test-logs** artifact has the HTML report, a screenshot of
the screen at the moment each UI test failed, and the emulator's full log.

Run them locally from Android Studio, or: `gradle testDebugUnitTest` (no device needed)
and `gradle connectedDebugAndroidTest` (with a phone or emulator connected).

## First run

The main screen shows a **Setup needed** section until everything is granted:

| Permission | Why |
|---|---|
| Notifications | The alarm screen is launched through a notification. |
| Full-screen alarms (Android 14+) | Lets the check-in appear over the lock screen. |
| Alarms & reminders | Rings at the exact minute (usually already granted). |
| Camera (asked when you register a code) | Scanning. Grant it ahead of time, because the alarm screen can't ask for it. |
| Battery optimization off | Samsung, Xiaomi, OnePlus, etc. sometimes kill background alarms. Also see <https://dontkillmyapp.com>. |

Then press a check-in's **Test** button (it rings in 10 seconds) and lock the phone.

## How it works

- **Ringing:** At the chosen time it plays your phone's default alarm sound on the alarm
  stream and vibrates. While it rings, the alarm volume can't go below 80%; if you turn
  it down, it goes straight back up. The original volume is restored afterwards.
- **Pause while you work:** While it rings, the alarm screen shows one big **Start
  check-in** button. Tapping it silences the sound and vibration for 30 seconds and shows
  the steps, with a countdown bar fixed at the top of the screen. Every tap or keystroke
  restarts the 30 seconds. If the countdown runs out, even with the app in the
  background, the alarm rings again and the Start screen comes back. Your progress
  (solved problems, answers) is kept.
- **Can't dismiss it:** The check-in screen shows over the lock screen, the Back button
  does nothing, and **Submit** only unlocks once every question is answered. Leaving
  with Home doesn't stop the sound, and opening the app takes you straight back to the
  questions.
- **Safety valve:** After 60 minutes the sound stops so it can't drain your battery.
  The check-in stays pending as a sticky notification until you answer.
- **Steps (per check-in, always in this order):**
  1. **Math:** 0–10 problems at one of four levels: Easy `47 + 38`, Medium
     `47 × 6 + 23`, Hard `47 × 38 + 512`, Very hard `347 × 38 + 47 × 26`. A wrong
     answer gives you a new problem.
  2. **Scan:** Register any QR code or product barcode with the **Scan** button on the check-in's page
     and name the spot (e.g. "Bathroom sink"). When the alarm rings, only that exact code
     stops it. If you can't scan (you're traveling, the camera fails), a **"Can't scan?"**
     button appears after 2 minutes. It swaps the scan for 5 very hard problems.
  3. **Questions:** the yes/no survey. These are the only answers that get logged.
  A check-in with no steps at all just shows a Dismiss button.
- **Check-ins:** You get **Morning** (7:00 AM, 2 questions about last night) and
  **Evening** (9:30 PM, 5 questions about today) to start with. Rename, change or delete
  them, and add as many more as you like with **+ Add check-in**. The start page lists
  each check-in with its time, steps and last-7-days stat, plus two buttons:
  - **Edit** opens its own page: name, on/off, time, "previous day" option, math, scan,
    questions, a 10-second test, and delete. Changes save as you make them.
  - **History** opens its last 30 days: logged / on-track counts, how often each question
    went off track, and a ✓/✗ grid. Tap a day to fill it in or fix it.
  Export all answers as CSV from the bottom of the start page.
- **"About yesterday":** A check-in with this setting (Morning by default) logs its answers
  to the previous day, so last night's late snack lands on the right date. Separately,
  any alarm set before 4 AM also counts as the previous day.
- **Two at once:** If two check-ins are waiting (say you slept through one), the alarm
  screen goes through them one after the other. It stops ringing only after the last one.
- **Fixing mistakes:** Tap any day on a check-in's History page to fill it in or change it.
- **Questions:** Add, edit, reorder, or delete them. Each one says whether "Yes" or "No"
  is on track. That drives the ✓/✗ grid and the "fully on track" counts.
- **Honest limits:** Like Alarmy, it can't stop you from force-stopping or uninstalling
  it in Settings. The friction is the point.

## Files

```
app/src/main/java/dev/gist/checkin/
  MainActivity.kt    start page: setup warnings, check-in list, 7-day stats, CSV export
  HistoryActivity.kt one check-in's last 30 days: stats, per-question counts, day grid
  CheckinEditActivity.kt  one check-in's page: name, time, steps, questions, test, delete
  CheckinText.kt     short descriptions of a check-in (time, steps, next ring)
  CheckinActivity.kt the alarm screen: runs math → scan → questions, then stops alarm
  ScannerView.kt     camera preview + on-device QR/barcode decoding (Camera2 + ZXing)
  ScanActivity.kt    registers a code for a check-in
  MathGen.kt         math problem generator
  AlarmService.kt    plays sound/vibration, guards volume, 60-min safety stop
  AlarmScheduler.kt  schedules each check-in's alarm with AlarmManager.setAlarmClock
  Receivers.kt       alarm fired → start ringing; reboot/time change → reschedule
  Notif.kt           notification channels + full-screen alarm notification
  Store.kt           check-ins, questions, and answers (SharedPreferences + JSON)
  Days.kt            date helpers
  Ui.kt              tiny helpers for building layouts in code
```

The default check-ins and questions are in `Store.kt` (`DEFAULT_CHECKINS`). You can
change all of it in the app.
