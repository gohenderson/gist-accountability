# Building Gist

The repository holds each platform's app in its own folder:

| Folder | What's in it |
|---|---|
| `android/` | the Android app (Kotlin, Gradle) |
| `ios/` | the iPhone app (Swift), coming next |
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

## Next

- Run the tests: [TESTING.md](TESTING.md).
- How the code is laid out: [ARCHITECTURE.md](ARCHITECTURE.md).
- Signed builds and releases: [RELEASING.md](RELEASING.md).
