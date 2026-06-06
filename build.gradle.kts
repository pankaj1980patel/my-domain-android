// Top-level build file. Plugin versions are declared here and applied in :app.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Compiles the Rust crate to per-ABI .so files and bundles them into the APK.
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6" apply false
}
