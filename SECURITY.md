# Security

## Reporting a vulnerability

Please **don't open a public issue** for a security problem. Use GitHub's private
reporting instead: the repository's **Security** tab → **Report a vulnerability**. Include
what you found, how to reproduce it, and which version you tested (it's on the app's
About page).

I'll acknowledge the report as soon as I can, fix confirmed problems in a new release,
and credit you if you'd like.

Only the latest release is supported.

## What Gist has access to

Gist is designed to have as little to protect as possible:

- **No internet permission.** It can't send anything anywhere. There are no accounts,
  analytics, ads or crash reporting.
- **Data stays in the app's private storage:** check-ins, answers, and background and voice
  media. It leaves the phone only when you export it, as a backup file or CSV, to a
  place you choose.
- **Your location** (place triggers) and **step counts** (running triggers) are read on the
  phone, used only to decide whether to ring, and never sent anywhere. The only locations
  stored are the places you save.
- **Files you pick** (photos, videos, audio clips, backups) go through a check that
  rejects the app's own private files, and are copied in with size limits.
- **Every component is private** (not exported), except the start page and the boot
  receiver, which only re-arms alarms after a restart or a clock change.
- **Two dependencies**, both small, open-source and pure Java: ZXing core and SnakeYAML
  Engine. Backups are parsed as plain data only.

## Automated checks

- **Dependabot** (`.github/dependabot.yml`) opens a pull request each week when a library
  or GitHub Action has an update or a security fix. Each PR runs the full test suite.
- **CodeQL** (`.github/workflows/codeql.yml`) scans the Kotlin code and the workflows for
  security issues on every push, every pull request, and weekly. Results are under
  **Security → Code scanning**.
- **Security report** (`.github/workflows/security-report.yml`) runs on Mondays and
  checks:
  - outdated build tools, libraries and Actions, including Actions on old Node.js
    versions;
  - known vulnerabilities in every resolved dependency, using the free OSV database;
  - open CodeQL alerts.

  If it finds anything, it opens (or updates) an issue labeled `security-report`,
  assigned to the repo owner, and GitHub emails you. It closes the issue when everything
  is clear. Run it any time from **Actions → Security report → Run workflow**.
- **Workflows:** all third-party Actions are pinned to commit SHAs, and workflows get
  read-only permissions unless a job needs more.
- **Releases:**
  - signed with a key kept only in a protected `release` environment;
  - every release run waits for the owner's approval;
  - every APK ships with a SHA-256 checksum.

  See [RELEASING.md](RELEASING.md).

## For the repository owner

- Keep GitHub's notification emails on (Settings → Notifications → Email).
- Turn on **Dependabot alerts** and **Private vulnerability reporting** under the repo's
  **Settings → Code security**. Only you can change those settings.
