# my-domain · Android

Native Android client for the **my-domain** LAN messenger. The **networking is
written in Rust** (the `rust/` crate) and compiled to a `.so` that the Kotlin /
Jetpack Compose UI calls over JNI — so this app speaks the *exact same wire
protocol* as the [desktop](https://github.com/pankaj1980patel/my-domain-desktop)
app and the two discover and message each other on the same LAN.

## Architecture

```
app/  (Kotlin + Jetpack Compose)         rust/  (Rust, crate-type = cdylib)
  MainActivity.kt  ── UI + polling          src/lib.rs
  RustNet.kt       ── JNI bindings ───────►   Java_com_mydomain_android_RustNet_*
                                              ├─ UDP multicast discovery (beacons)
                                              ├─ TCP listener  (recv messages)
                                              ├─ UDP socket    (recv messages)
                                              └─ peer table + inbox queue
```

The Kotlin side calls four native functions (all exchange JSON strings):

| Kotlin                       | Purpose                                            |
|------------------------------|----------------------------------------------------|
| `nativeStart(name)`          | start discovery + listeners (idempotent); returns identity |
| `nativeGetPeers()`           | current discovered peers                           |
| `nativePollMessages()`       | drain newly received messages                      |
| `nativeSend(nodeId, proto, text)` | send a message over `"UDP"` or `"TCP"`        |

The UI polls `nativeGetPeers` / `nativePollMessages` once a second.

## Shared wire protocol (must match desktop)

- **Beacon** — UDP multicast `239.255.42.98:45678`, every ~2s:
  `{"node_id":"…","name":"…","tcp_port":<int>,"udp_port":<int>}`
- **Message** — JSON body of a TCP connection, or a single UDP datagram to the
  peer's advertised port: `{"from":"…","text":"…"}`

> Android requires a `WifiManager.MulticastLock` to receive multicast — it is
> acquired in `MainActivity.onCreate()` before `nativeStart()` and released in
> `onDestroy()`. Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`,
> `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.

## Prerequisites

1. **Android Studio** (Ladybug+), with the **Android SDK** and **NDK** installed
   (SDK Manager → SDK Tools → *NDK (Side by side)*).
2. **Rust** + the Android targets:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi \
                     i686-linux-android x86_64-linux-android
   ```
3. Point Gradle at your NDK — either set `ANDROID_NDK_HOME`, or add to
   `local.properties` (Android Studio usually writes `sdk.dir` for you):
   ```
   sdk.dir=/Users/you/Library/Android/sdk
   ndk.dir=/Users/you/Library/Android/sdk/ndk/<version>
   ```

## Build & run

Easiest path — **open the `android/` folder in Android Studio** and press Run.
On first open, Android Studio provisions the Gradle wrapper JAR
(`gradle/wrapper/gradle-wrapper.jar`, not committed here) and downloads Gradle
8.10.2. The `rust-android-gradle` plugin builds the Rust crate automatically as
part of the Android build.

From the command line (after the wrapper JAR exists — `gradle wrapper` or the
first Android Studio sync creates it):

```bash
./gradlew assembleDebug          # builds the .so for all ABIs + the APK
./gradlew installDebug           # install on a connected device
# or build just the native libs:
./gradlew cargoBuild
```

## Testing desktop ⇄ Android

1. Run the [desktop](https://github.com/pankaj1980patel/my-domain-desktop) app on
   a computer.
2. Put the Android device on the **same Wi-Fi** and launch this app.
3. Each appears in the other's **Peers** list; send messages either way over UDP
   or TCP.

> Many guest / corporate / "client-isolation" Wi-Fi networks block UDP multicast
> and peer-to-peer traffic. Use a normal home network or a phone hotspot.

## Notes

- Package / `applicationId`: `com.mydomain.android`. The JNI symbol names in
  `rust/src/lib.rs` (`Java_com_mydomain_android_RustNet_*`) are derived from this
  package + the `RustNet` class name — keep them in sync if you rename.
- `cargo` target names in `app/build.gradle.kts` map to ABIs: `arm64`→arm64-v8a,
  `x86_64`→x86_64, `arm`→armeabi-v7a, `x86`→x86.
