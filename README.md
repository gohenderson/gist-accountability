# Gist — an un-dismissable daily diet check-in

Android alarms that ring every day and won't stop until you answer a few yes/no
questions about what you ate. You can have several check-ins, each with its own time
and its own questions. It keeps a simple log so you can see the *gist* of your
diet without counting calories.

- **No dependencies.** The app uses only Android's built-in APIs: no AndroidX, no
  third-party libraries.
- **No internet permission.** Nothing ever leaves your phone. Answers live in the app's
  private storage, and the only way out is the **Export CSV** button.
- About 1,250 lines of Kotlin in 9 files. You can read all of it in one sitting.

## Build & install

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** → choose this `Gist` folder. Let Gradle sync. Android Studio downloads
   Gradle and the Android SDK itself. If it offers to upgrade the Android Gradle Plugin,
   accepting is fine.
3. On your phone: **Settings → About phone → tap "Build number" 7 times** to enable
   Developer options. Then turn on **Developer options → USB debugging**.
4. Plug the phone in, pick it in the device dropdown, and press **Run ▶**.

Requires Android 10 or newer.

> I wrote this without being able to compile it (my sandbox couldn't download the
> Android SDK). If the first build shows an error, paste it to me and I'll fix it.

## First run

The main screen shows a **Setup needed** section until everything is granted:

| Permission | Why |
|---|---|
| Notifications | The alarm screen is launched through a notification. |
| Full-screen alarms (Android 14+) | Lets the check-in appear over the lock screen. |
| Alarms & reminders | Rings at the exact minute (usually already granted). |
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
  SurveyActivity.kt  the check-in screen (lock-screen, no Back, submit stops alarm)
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
