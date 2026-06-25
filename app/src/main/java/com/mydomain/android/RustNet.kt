package com.mydomain.android

/**
 * JNI bridge to the Rust core (libmydomain_net.so). All methods exchange JSON
 * strings or short status strings ("" on success, "ERROR: ..." on failure).
 */
object RustNet {
    init {
        System.loadLibrary("mydomain_net")
    }

    /** Start networking (LAN listeners + WS server). Returns identity JSON. */
    external fun nativeStart(deviceId: String, name: String, wifiIp: String): String

    external fun nativeAuthLogin(serverUrl: String, username: String, password: String): String
    external fun nativeAuthRegister(serverUrl: String, username: String, password: String): String
    external fun nativeSetEncryptionKey(passphrase: String): String
    external fun nativeUpdateEncryptionKey(newPassphrase: String, password: String): String
    external fun nativeGenerateKey(): String
    /** DEV / LAN-only: set a local username + encryption key, skipping the registry login. */
    external fun nativeDevLogin(username: String, passphrase: String): String
    external fun nativeLogout(): String
    /** "true" / "false". */
    external fun nativeIsReady(): String

    /** Register this device + fetch peers from the registry. */
    external fun nativeRefresh(): String
    external fun nativeNetworkChanged(wifiIp: String): String
    external fun nativeScanLan(): String
    external fun nativeConnectWs(nodeId: String): String

    /** protocol is "UDP", "TCP", or "WS". */
    external fun nativeSend(nodeId: String, protocol: String, text: String): String

    external fun nativeGetPeers(): String
    external fun nativePollMessages(): String

    /** JSON array of node_ids with a live WebSocket (true on both ends). */
    external fun nativeConnectedPeers(): String

    // ---- feature sharing ----
    external fun nativeShareNotification(title: String, body: String, app: String): String
    external fun nativeShareCallNotification(caller: String, number: String, state: String): String
    external fun nativeShareCallHistory(entriesJson: String): String
    /** Drain feature events (notification / call-*) as a JSON array. */
    external fun nativePollEvents(): String

    // ---- signaling / connection setup ----
    external fun nativeSetFcmToken(token: String): String
    external fun nativeOnSignal(from: String, payload: String): String
    external fun nativeConnect(nodeId: String): String
    /** JSON {outbound_ok, inbound_blocked} or "ERROR: ...". */
    external fun nativeFirewallCheck(): String

    // ---- app-notification pub/sub ----
    /** Publish this device's shareable app list (JSON array of {pkg,label}). */
    external fun nativeSetInstalledApps(appsJson: String): String
    /** Consumer: request a producer's app list (reply via nativePollEvents). */
    external fun nativeRequestApps(nodeId: String): String
    /** Consumer: set enabled packages (JSON array of pkg strings) on a producer. */
    external fun nativeSubscribeApps(nodeId: String, appsJson: String): String
    /** Producer: forward a captured app notification to subscribers of pkg. */
    external fun nativeShareAppNotification(pkg: String, app: String, title: String, body: String): String
}
