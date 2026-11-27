import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") // AGP 9 has Kotlin support built in
}

// Release signing comes from environment variables (set by the release workflow from
// repository secrets). Without them, release builds are simply left unsigned.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")

// -Ppeer=true builds "Gist B": a second copy with its own app id and data, installed beside
// Gist, so two copies on one phone can try out connections through a shared folder.
val peer = findProperty("peer") == "true"

android {
    namespace = "dev.gist.checkin"
    compileSdk = 36

    // Sample backups shared with the iOS app (repo root shared/backups): the UI tests
    // import them (SharedBackupTest), so both apps read the same files the same way.
    sourceSets["androidTest"].assets.srcDir("../../shared/backups")
    // Sync test files shared with the iOS app (shared/sync): SyncVectorsTest.
    sourceSets["androidTest"].assets.srcDir("../../shared/sync/files")
    // Syncthing's program (Easy Connect): built by CI (.github/scripts/build-syncthing.sh) into
    // android/syncthing/jniLibs/<abi>/. Without it the app still builds, and Easy Connect isn't
    // offered (the release workflow checks the signed APK has it).
    sourceSets["main"].jniLibs.srcDir("../syncthing/jniLibs")

    // Unpacked on install (not run from inside the APK): Android only lets an app run a
    // program from its native library folder.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = if (peer) "dev.gist.checkin.peer" else "dev.gist.checkin"
        manifestPlaceholders["appLabel"] = if (peer) "Gist B" else "Gist"
        minSdk = 29          // Android 10+
        targetSdk = 35
        // CI passes these (-PversionCode=… -PversionName=…); local builds use the defaults.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Phones only: Syncthing for 64-bit ARM. (Debug builds also have x86_64, for the
            // emulator.)
            ndk { abiFilters += "arm64-v8a" }
            if (signingStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        animationsDisabled = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Lint runs as its own CI step and its report is uploaded; it doesn't block builds.
        abortOnError = false
        checkReleaseBuilds = false
        // Play Store target-SDK deadlines don't apply to a sideloaded app.
        disable += "ExpiredTargetSdkVersion"
        // Plain-text report; CI prints it into the build log.
        textReport = true
        textOutput = file("build/reports/lint-results-debug.txt")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Two small pure-Java libraries, no network access, no Google Play services:
    // ZXing's barcode decoder (Apache-2.0, https://github.com/zxing/zxing) for scanning,
    implementation("com.google.zxing:core:3.5.4")
    // and SnakeYAML Engine (Apache-2.0, https://bitbucket.org/snakeyaml/snakeyaml-engine),
    // a YAML 1.2 parser/emitter for backup files. It stays on 2.x: 3.x targets Java 11,
    // whose library methods older Android versions (this app supports 10+) don't have.
    implementation("org.snakeyaml:snakeyaml-engine:3.2")

    // Test-only libraries: used to build and run the tests, never included in the app APK.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.1")
}
