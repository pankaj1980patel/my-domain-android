plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.mozilla.rust-android-gradle.rust-android")
}

android {
    namespace = "com.mydomain.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mydomain.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

// Locate the Rust toolchain. Android Studio's Gradle daemon often doesn't
// inherit your shell PATH (esp. when launched from the macOS Dock), so point
// at the rustup shims directly. Honors CARGO_HOME, else defaults to ~/.cargo.
// Override per-machine with -Prust.cargoBin=/path/to/cargo/bin if needed.
val cargoBin: String =
    (project.findProperty("rust.cargoBin") as String?)
        ?: System.getenv("CARGO_HOME")?.let { "$it/bin" }
        ?: "${System.getProperty("user.home")}/.cargo/bin"

// Build the Rust crate (../rust) into per-ABI .so files and bundle them.
cargo {
    module = "../rust"
    libname = "mydomain_net"
    // rust-android-gradle target names -> Android ABIs:
    //   arm64 -> arm64-v8a, x86_64 -> x86_64, arm -> armeabi-v7a, x86 -> x86
    targets = listOf("arm64", "x86_64", "arm", "x86")
    profile = "release"
    cargoCommand = "$cargoBin/cargo"
    rustcCommand = "$cargoBin/rustc"
    // The plugin's generated linker-wrapper.sh invokes `python`; modern macOS
    // only ships `python3`, so point it there (override with -Prust.python=...).
    pythonCommand = (project.findProperty("rust.python") as String?) ?: "python3"
}

// Make sure the Rust libs are built before Gradle packages the JNI libs.
tasks.whenTaskAdded {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn("cargoBuild")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // Material XML theme used by the activity (Theme.Material3.*).
    implementation("com.google.android.material:material:1.12.0")
}
