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
    external fun nativeStart(name: String, wifiIp: String): String

    external fun nativeAuthLogin(serverUrl: String, username: String, password: String): String
    external fun nativeAuthRegister(serverUrl: String, username: String, password: String): String
    external fun nativeSetEncryptionKey(passphrase: String): String
    external fun nativeUpdateEncryptionKey(newPassphrase: String, password: String): String
    external fun nativeGenerateKey(): String
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
}
