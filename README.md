# my-domain Android

A secure, multi-protocol LAN messenger for Android, featuring end-to-end encryption (E2EE) and hybrid discovery.

## Project Overview

The "my-domain" Android app is designed for private communication over local networks. It leverages a high-performance Rust core to handle networking and cryptography, providing a robust foundation for secure messaging.

### Key Features

*   **End-to-End Encryption (E2EE):** All messages are encrypted/decrypted using `XChaCha20Poly1305`. Keys are derived locally using Argon2 from a user-defined passphrase, ensuring that even the registry server cannot read the messages.
*   **Hybrid Peer Discovery:**
    *   **Registry-Based:** Authenticated users register their IP and ports with a central server and fetch other registered devices.
    *   **LAN Discovery:** Uses UDP Multicast (239.255.42.98:45678) and subnet-wide broadcasting to find peers on the same network without requiring a server.
*   **Multi-Protocol Messaging:**
    *   **UDP:** Low-overhead encrypted packets.
    *   **TCP:** Reliable one-off encrypted connections.
    *   **WebSocket (WS):** Persistent, bidirectional encrypted channels for low-latency LAN communication.
*   **Privacy First:** The registry server only acts as a directory; it never sees plaintext messages or encryption keys.

## Technical Architecture

### 1. Android UI (Kotlin & Jetpack Compose)
The frontend is a modern Jetpack Compose application.
*   **`MainActivity.kt`**: Manages the application lifecycle, UI state, and network connectivity monitoring. It acquires a `WifiManager.MulticastLock` to enable reliable LAN discovery.
*   **`RustNet.kt`**: Acts as the JNI (Java Native Interface) bridge, exposing the Rust core's functionality to the Kotlin layer.

### 2. Rust Core (`rust/src/lib.rs`)
The "brain" of the application, written in Rust for safety and performance.
*   **State Management:** Maintains `NetState`, tracking local identity, peer lists, and active connections.
*   **Networking Loops:** Spawns dedicated threads for:
    *   UDP Multicast discovery.
    *   TCP listener for incoming messages.
    *   UDP listener for incoming packets.
    *   WebSocket server for persistent peer connections.
*   **Cryptography:** Implements the E2EE logic using the `chacha20poly1305` and `argon2` crates.

### 3. JNI Bridge
Data is exchanged between Kotlin and Rust primarily through JSON strings, allowing for flexible and type-safe communication of complex structures like peer lists and message logs.

## Security Model

1.  **Key Derivation:** A user's encryption key is derived from their `username` and a `passphrase` using Argon2.
2.  **Message Encryption:** Each message is wrapped in an `Envelope` containing a random 24-byte nonce and the ciphertext.
3.  **Authentication:** The app uses JWT-based authentication with a central registry for global identity, but also supports a "LAN-only" mode that skips the server entirely.

## Development

### Prerequisites
*   Android Studio
*   Rust toolchain (with `aarch64-linux-android`, `armv7-linux-androideabi`, etc., targets)
*   `cargo-ndk` for building the Rust JNI library.

### Building
The Rust module is automatically built as part of the Gradle build process (configured in `app/build.gradle.kts`).

```bash
./gradlew assembleDebug
```

## Contributing
The project is divided into the Android frontend (`/app`) and the Rust backend (`/rust`). Ensure any changes to the JNI interface in `lib.rs` are reflected in `RustNet.kt`.
