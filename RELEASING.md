# Builds and releases

Everything is built by GitHub Actions. Releases are signed with a key that never
touches the repository, and every release waits for the owner's approval.

## Workflows

| Workflow | Runs | Does |
|---|---|---|
| **Build** (`build.yml`) | every push to `main`, every pull request, on demand | debug APK, unit tests, lint, and UI tests on an emulator. The debug APK and all logs and reports are attached to the run. |
| **Release** (`release.yml`) | on demand, or when a `v*` tag is pushed | the whole Build workflow first; if it passes, and once you approve, a signed APK published as a GitHub release with a `.sha256` checksum |
| **Screenshots** (`screenshots.yml`) | after each release, or on demand | the app on an emulator with demo data, captured page by page |
| **CodeQL** (`codeql.yml`) | every push, every pull request, weekly | security scanning; see [SECURITY.md](SECURITY.md) |
| **Security report** (`security-report.yml`) | Mondays, or on demand | outdated and vulnerable dependencies; see [SECURITY.md](SECURITY.md) |

All third-party Actions are pinned to commit SHAs. Dependabot keeps those pins up to date.

## Making a release

Start it either way:

- **Actions → Release → Run workflow**, then enter a version like `v1.0.0`:
  - if the tag doesn't exist, it's created on the latest `main`;
  - running it for an existing tag rebuilds that release.
- or push a tag: `git tag v1.0.0 && git push origin v1.0.0`.

The run builds and tests everything, then **pauses for approval** (Actions → the run →
**Review deployments**) before it can read the signing key. After approval it:

1. signs the APK;
2. publishes the release with the APK and its `.sha256`;
3. starts the Screenshots workflow.

To install a release, open its `.apk` on your phone, and allow "Install unknown apps"
for your browser or file manager when Android asks.

## One-time setup: a signing key

Android only installs an update if it's signed with the same key as the installed app,
so releases need one permanent key. You create it once on your computer and store it as
secrets in a protected `release` environment. It never needs to be in the repository.

1. **Create the key.** `keytool` comes with Android Studio, in its `jbr/bin` folder.
   ```
   keytool -genkeypair -v -keystore gist-release.jks -alias gist \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
   It asks for a password and some name fields. The name fields can be anything.
2. **Base64-encode it:**
   - macOS / Linux: `base64 < gist-release.jks | tr -d '\n' > gist-release.b64`
   - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\gist-release.jks")) | Set-Content -NoNewline -Encoding ascii gist-release.b64`
3. **Create the environment.** On GitHub, open **Settings → Environments → New
   environment** and name it `release`.
   - Under **Deployment protection rules**, tick **Required reviewers**, add yourself, and
     **Save protection rules**. Every release run then waits for your approval before it
     can read the signing key.
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
   manager. If you lose them, future releases can't update the installed app. You'd have
   to uninstall it, which deletes your data, so export a backup first.

If a secret is missing, the release run stops with an error naming it.

## Screenshots

- **What it captures:** an Android emulator on GitHub opens the app with demo data, then
  captures:
  - the start page (plain, grouped and dark);
  - history;
  - a check-in's page and a step's page;
  - the library;
  - settings and About;
  - the alarm screen (math and questions).
- **Where they go:** the PNGs are attached to the release and shown in its notes, and
  published to the `screenshots` branch, which the README's images point to.
- **If it fails:** the release is still published.
- **Without releasing:** use **Actions → Screenshots → Run workflow** and leave the tag
  empty.

To make this possible, *debug* builds let these screens be opened directly from the
command line (`app/src/debug/AndroidManifest.xml`). The release APK you install doesn't.

## When something fails

- **Compile errors** appear as annotations on the run page and on the exact lines of
  code.
- **The job summary** shows Gradle's "What went wrong" section and the end of the log.
- **The build-logs / release-logs artifact** has the full Gradle logs plus the lint and
  build reports.
- **Failing tests:** see [TESTING.md](TESTING.md#when-a-test-fails).
