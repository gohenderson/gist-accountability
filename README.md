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

- **Every push to `main`** (and every pull request) runs **Build**: a debug APK plus lint.
  The debug APK and all logs/reports are attached to the run as artifacts.
- **Pushing a version tag** runs **Release**. It builds a signed APK and publishes it as a
  GitHub release, with a `.sha256` checksum next to it:
  ```
  git tag v1.0.0
  git push origin v1.0.0
  ```
  You can also re-run a release for an existing tag from **Actions → Release → Run workflow**.
  Each release run pauses until you approve it (see the signing key setup below).

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
  2. **Scan:** Register any QR code or product barcode with the card's **Scan** button
     and name the spot (e.g. "Bathroom sink"). When the alarm rings, only that exact code
     stops it. If you can't scan (you're traveling, the camera fails), a **"Can't scan?"**
     button appears after 2 minutes. It swaps the scan for 5 very hard problems.
  3. **Questions:** the yes/no survey. These are the only answers that get logged.
  A check-in with no steps at all just shows a Dismiss button.
- **Several check-ins:** By default you get **Morning** (7:00 AM, 2 questions about last
  night) and **Evening** (9:30 PM, 5 questions about today). Add more with
  **+ Add check-in**. Each one has its own on/off switch, time, questions, Test button,
  and history grid. Rename or delete one under **Settings**.
- **"About yesterday":** A check-in with this setting (Morning by default) logs its answers
  to the previous day, so last night's late snack lands on the right date. Separately,
  any alarm set before 4 AM also counts as the previous day.
- **Two at once:** If two check-ins are waiting (say you slept through one), the alarm
  screen goes through them one after the other. It stops ringing only after the last one.
- **Fixing mistakes:** Tap any day in a check-in's 30-day grid to fill it in or change it.
- **Questions:** Add, edit, reorder, or delete them. Each one says whether "Yes" or "No"
  is on track. That drives the ✓/✗ grid and the "fully on track" counts.
- **Honest limits:** Like Alarmy, it can't stop you from force-stopping or uninstalling
  it in Settings. The friction is the point.

## Files

```
app/src/main/java/dev/gist/checkin/
  MainActivity.kt    settings, question editor, summary, 30-day grid, CSV export
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
