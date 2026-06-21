package com.mydomain.android.core

import com.mydomain.android.Peer
import org.json.JSONArray
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.collections.iterator

object NetworkingUtils {

    fun parsePeers(json: String): List<Peer> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Peer(o.getString("node_id"), o.getString("name"), o.getString("ip"),
                o.getInt("tcp_port"), o.getInt("udp_port"), o.optInt("ws_port"), o.optString("source", "registry"))
        }
    }


    fun wifiIpv4(): String {
        fun scan(preferWlan: Boolean): String? {
            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (preferWlan && !(name.startsWith("wlan") || name.startsWith("ap"))) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null && (preferWlan || addr.isSiteLocalAddress)) return host
                    }
                }
            }
            return null
        }
        return scan(true) ?: scan(false) ?: ""
    }
}