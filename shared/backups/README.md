# Sample backups

Backup files in Gist's format (`format: gist-backup`, `version: 3`), shared by the apps'
test suites so every platform reads the same files the same way:

- the Android app imports them in `SharedBackupTest`
  (`android/app/src/androidTest`; Gradle adds this folder to the test's assets);
- the iOS app will import them in its tests too.

A backup made on one phone has to import on the other. Change the format here first:
add or update a sample, and the tests on both platforms say what broke.

| File | What it covers |
|---|---|
| `every-feature.yaml` | One of everything: per-time days, a place condition, monthly dates, all step kinds, place and run triggers, soft and voice check-ins, a caption, history with every status, a soft result |
