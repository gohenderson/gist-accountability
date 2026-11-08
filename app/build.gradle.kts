import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.gist.checkin"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.gist.checkin"
        minSdk = 29          // Android 10+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The only dependency: ZXing's pure-Java barcode decoder (Apache-2.0,
    // https://github.com/zxing/zxing). No network access, no Google Play services.
    implementation("com.google.zxing:core:3.5.3")
}
