# Sample backups

Backup files in Gist's format (`format: gist-backup`, `version: 4` or `5`), shared by the apps'
test suites so every platform reads the same files the same way:

- the Android app imports them in `SharedBackupTest`
  (`android/app/src/androidTest`; Gradle adds this folder to the test's assets);
- the iOS app imports them in `SharedBackupTests` (`ios/GistCore/Tests`).

A backup made on one phone has to import on the other. Change the format here first:
add or update a sample, and the tests on both platforms say what broke.

| File | What it covers |
|---|---|
| `every-feature.yaml` | One of everything: per-time days, a place condition, monthly dates, all step kinds, yes/no, number and text questions (answered, and left empty), place and run triggers, soft and voice check-ins, a caption, history with every status, a soft result |
| `tasks.yaml` | Version 5: an active task with a time on a specific date and No alarm, a backlog task, a done task, a pick-tasks step, a description and time commitments, and check-in timing (`rangAt`, `took` with and without `start`) |
