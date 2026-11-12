# Building Gist

The repository holds each platform's app in its own folder:

| Folder | What's in it |
|---|---|
| `android/` | the Android app (Kotlin, Gradle) |
| `ios/` | the iPhone app (Swift, SwiftUI): `Gist/` is the app, `GistCore/` its logic |
| `shared/` | files both apps use in their tests, such as sample backups |

This page covers the Android app. Most people should install a signed APK from
[Releases](https://github.com/gohenderson/gist-accountability/releases) (see the
[README](README.md#get-it)). This page covers building it yourself.

## Requirements

- **Android Studio** (any recent version), or **Gradle 9.9** with **JDK 17+** and the
  Android SDK (platform 36, build tools) if you build from the command line.
- A phone or emulator running **Android 10 (API 29) or newer**.

The toolchain: Android Gradle Plugin 9.5 with its built-in Kotlin 2.4, compileSdk 36,
targetSdk 35, minSdk 29. There is no Gradle wrapper in the repository; Android Studio
downloads Gradle itself, and CI installs the version it needs.

## With Android Studio

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** and choose the repository's **`android`** folder (not the repository
   root). Let Gradle sync. Android Studio downloads
   Gradle and the Android SDK itself. If it offers to upgrade the Android Gradle Plugin,
   accepting is fine.
3. On your phone: **Settings → About phone**, then tap **Build number** 7 times to enable
   Developer options. Then turn on **Developer options → USB debugging**.
4. Plug the phone in, pick it in the device dropdown, and press **Run ▶**.

## From the command line

From the repository root (`-p android` points Gradle at the Android project), or run the
same commands without `-p android` from inside `android/`:

```
gradle -p android assembleDebug       # android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Local builds are versioned `dev`. CI passes `-PversionCode=… -PversionName=…` for
releases.

A debug build and a release share the same application id (`dev.gist.checkin`) but are
signed with different keys, so Android won't install one over the other. Switching means
uninstalling first, which deletes your data, so **export a backup** first
(Settings → Export backup…).

## Dependencies

Two small libraries, both open-source plain Java (Apache-2.0):

- [ZXing core](https://github.com/zxing/zxing), a barcode decoder, for scanning;
- [SnakeYAML Engine](https://bitbucket.org/snakeyaml/snakeyaml-engine), a YAML parser,
  for backup files. It stays on 2.x on purpose: 3.x needs Java 11 library methods that
  Android 10–12 don't have.

Everything else (camera, UI, storage, video, text-to-speech, location, step counting)
uses Android's built-in APIs. There's no AndroidX and no Google Play services.

## The iPhone app

It needs a Mac with **Xcode 26** and runs on **iOS 26 or newer** (its alarms use
AlarmKit, new in iOS 26).

- `ios/GistCore`: the app's logic as a Swift package (models, the backup format,
  scheduling, math problems, scores, sorting), ported from the Android app. Its one
  dependency is [Yams](https://github.com/jpsim/Yams) (MIT), for YAML.
- `ios/Gist`: the SwiftUI app.
- `ios/GistUITests`: its UI tests (in Xcode: Product → Test).
- `ios/project.yml`: the Xcode project's definition, for
  [XcodeGen](https://github.com/yonaskolb/XcodeGen). The generated `Gist.xcodeproj` isn't
  committed.

```
brew install xcodegen
cd ios
xcodegen generate
open Gist.xcodeproj          # then pick an iPhone simulator and press Run
(cd GistCore && swift test)  # the logic's tests
```

It runs on iPhones and iPads.

### On your iPhone or iPad, without a Mac

Every run of the **iOS** workflow (Actions → iOS, for any change to `ios/` or `shared/`)
builds **`Gist.ipa`**, unsigned, for real devices. Download it from the run's **Gist-ipa**
artifact (it's a zip with `Gist.ipa` inside). Then sign and install it with your own Apple
ID from a Windows PC or a Mac:

1. On the computer, install [AltServer](https://altstore.io) (with AltStore) or
   [Sideloadly](https://sideloadly.io). On Windows, also install iTunes and iCloud from
   Apple's website (not the Microsoft Store versions), which these tools use to talk to
   the device.
2. Connect the iPhone or iPad with a cable and tap **Trust** on it.
3. Sideload `Gist.ipa`: in Sideloadly, drag it in and press Start; with AltServer, install
   AltStore first, then open AltStore on the device, go to **My Apps**, tap **+** and pick
   the file. Sign in with your Apple ID when asked (it's only used to sign the app; a
   separate Apple ID just for this is fine).
4. On the device, open **Settings → General → VPN & Device Management**, tap your Apple ID
   and **Trust** it. On iOS 16 and newer, also turn on **Developer Mode** (Settings →
   Privacy & Security) when asked, and restart.
5. Open Gist and tap **Allow alarms**.

With a free Apple ID, apps signed this way stop opening after **7 days** until they're
re-signed (AltStore does it in the background while AltServer runs on the same Wi-Fi), and
at most three sideloaded apps can be active at once. The Apple Developer Program
($99/year) removes both limits and allows TestFlight.

### With a Mac

Select the **Gist** target in Xcode, choose your Apple ID as the team under **Signing &
Capabilities**, plug in the device and press Run. The same 7-day limit applies to a free
Apple ID.

## Next

- Run the tests: [TESTING.md](TESTING.md).
- How the code is laid out: [ARCHITECTURE.md](ARCHITECTURE.md).
- Signed builds and releases: [RELEASING.md](RELEASING.md).
