package com.mydomain.android

/**
 * Thin JNI bridge to the Rust networking core (libmydomain_net.so).
 *
 * All methods exchange JSON strings:
 *  - [nativeStart] returns the local identity:
 *      {"node_id","name","ip","tcp_port","udp_port"}
 *  - [nativeGetPeers] returns an array of discovered peers.
 *  - [nativePollMessages] drains and returns newly received messages.
 *  - [nativeSend] returns "" on success or "ERROR: ..." on failure.
 */
object RustNet {
    init {
        System.loadLibrary("mydomain_net")
    }

    /** Idempotent: starts discovery + listeners once, returns identity JSON. */
    external fun nativeStart(name: String): String

    external fun nativeGetPeers(): String

    external fun nativePollMessages(): String

    /** protocol is "UDP" or "TCP". */
    external fun nativeSend(nodeId: String, protocol: String, text: String): String
}
