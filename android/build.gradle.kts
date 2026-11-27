plugins {
    // AGP 9 compiles Kotlin itself, with the Kotlin version it ships with. (Pinning one with
    // org.jetbrains.kotlin.android 2.4.21 broke once that plugin couldn't be resolved.)
    id("com.android.application") version "9.5.2" apply false
}
