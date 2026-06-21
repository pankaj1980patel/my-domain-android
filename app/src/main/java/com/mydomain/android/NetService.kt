package com.mydomain.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mydomain.android.core.NetworkingUtils.wifiIpv4
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

class NetService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private lateinit var clipboard: ClipboardManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): NetService = this@NetService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("mydomain").apply { 
            setReferenceCounted(true)
            acquire() 
        }

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { RustNet.nativeNetworkChanged(wifiIpv4()) }
            override fun onLost(network: Network) { RustNet.nativeNetworkChanged(wifiIpv4()) }
        }
        runCatching { connectivityManager?.registerDefaultNetworkCallback(netCallback!!) }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, createNotification())
        }

        // Restore the clipboard auto-sync ("active" mode) toggle.
        clipboardSyncEnabled = getSharedPreferences("md", MODE_PRIVATE).getBoolean("clip_sync", false)

        // Initialize Rust core if not already done. Prefer the IMEI as a stable
        // device id; on Android 10+ that's unreadable for normal apps, so fall
        // back to ANDROID_ID (also stable across restarts/reinstalls).
        val ip = wifiIpv4()
        val identityJson = RustNet.nativeStart(resolveDeviceId(), Build.MODEL ?: "android", ip)
        runCatching { myNodeId = JSONObject(identityJson).optString("node_id", "") }

        startNetworking()
    }

    /**
     * Best-effort stable device id. Tries the IMEI (requires READ_PHONE_STATE and
     * is only obtainable by privileged apps on API 29+), and falls back to
     * ANDROID_ID — Google's official, permission-free device-id replacement.
     */
    private fun resolveDeviceId(): String {
        imei()?.takeIf { it.isNotBlank() }?.let { return it }
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    }

    @Suppress("HardwareIds", "DEPRECATION", "MissingPermission")
    private fun imei(): String? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.imei else tm.deviceId
        }.getOrNull()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "net_service", "Networking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, "net_service")
            .setContentTitle("LAN Messenger Active")
            .setContentText("Listening for messages and clipboard sync...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startNetworking() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val msgs = RustNet.nativePollMessages()
                    if (msgs != "[]") {
                        val arr = JSONArray(msgs)
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            handleIncomingMessage(o)
                            
                            // Broadcast for UI
                            val intent = Intent("com.mydomain.android.NEW_MESSAGE")
                            intent.putExtra("msg", o.toString())
                            sendBroadcast(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NetService", "Error polling messages", e)
                }
                delay(1000)
            }
        }
        
        // Polling peers and connecting WS in background if needed
        serviceScope.launch {
            while (isActive) {
                if (RustNet.nativeIsReady() == "true") {
                    // Try to maintain WS connections with known peers
                    val peersJson = RustNet.nativeGetPeers()
                    val peers = JSONArray(peersJson)
                    val connectedJson = RustNet.nativeConnectedPeers()
                    val connectedIds = JSONArray(connectedJson)
                    val connectedSet = mutableSetOf<String>()
                    for (i in 0 until connectedIds.length()) connectedSet.add(connectedIds.getString(i))

                    for (i in 0 until peers.length()) {
                        val p = peers.getJSONObject(i)
                        val nodeId = p.getString("node_id")
                        val wsPort = p.optInt("ws_port", 0)
                        if (wsPort != 0 && !connectedSet.contains(nodeId)) {
                            // Try to connect WS in background
                            RustNet.nativeConnectWs(nodeId)
                        }
                    }
                }
                delay(10000) // Every 10 seconds
            }
        }
        
        // Track the local clipboard. We always remember the latest value (so we
        // can answer passive "get clipboard" pulls), but only broadcast changes
        // to peers while ACTIVE auto-sync is enabled.
        Handler(Looper.getMainLooper()).post {
            clipboard.addPrimaryClipChangedListener {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (text != null && !text.startsWith("SYNC_IGNORE:")) { // Avoid loops if we use a prefix
                        currentLocalClip = text
                        if (clipboardSyncEnabled) syncClipboard(text)
                    }
                }
            }
        }
    }

    private var lastReceivedClipboard: String? = null
    /** Latest known local clipboard text, used to answer passive pull requests. */
    private var currentLocalClip: String = ""

    private fun handleIncomingMessage(msg: JSONObject) {
        val text = msg.optString("text", "")
        val json = try { JSONObject(text) } catch (e: Exception) { return } // regular chat
        when (json.optString("type")) {
            // Unsolicited push from a peer in active mode: only apply if we also
            // have auto-sync enabled.
            "clipboard" -> {
                if (!clipboardSyncEnabled) return
                val content = json.optString("content")
                if (content != lastReceivedClipboard) {
                    lastReceivedClipboard = content
                    updateLocalClipboard(content)
                }
            }
            // A peer is pulling our clipboard: reply with the latest known value.
            "clipboard_request" -> {
                val requester = json.optString("from")
                if (requester.isNotEmpty()) {
                    val resp = JSONObject().apply {
                        put("type", "clipboard_response")
                        put("content", currentLocalClip)
                        put("from", myNodeId)
                    }.toString()
                    sendClip(requester, resp)
                }
            }
            // Reply to a pull we initiated: always apply (it was solicited).
            "clipboard_response" -> {
                val content = json.optString("content")
                lastReceivedClipboard = content
                updateLocalClipboard(content)
            }
        }
    }

    /** Send a clipboard control message to one peer (WS if connected, else UDP). */
    private fun sendClip(nodeId: String, json: String) {
        serviceScope.launch {
            val proto = if (connectedPeerSet().contains(nodeId)) "WS" else "UDP"
            RustNet.nativeSend(nodeId, proto, json)
        }
    }

    private fun connectedPeerSet(): Set<String> {
        val set = mutableSetOf<String>()
        runCatching {
            val arr = JSONArray(RustNet.nativeConnectedPeers())
            for (i in 0 until arr.length()) set.add(arr.getString(i))
        }
        return set
    }

    private fun updateLocalClipboard(content: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                // To avoid triggering our own listener and causing a loop, 
                // we could use a flag or check the content.
                val clip = ClipData.newPlainText("Synced Clipboard", content)
                clipboard.setPrimaryClip(clip)
                Log.i("NetService", "Clipboard updated from remote")
            } catch (_: Exception) {
                Log.e("NetService", "Failed to update clipboard")
            }
        }
    }

    private fun syncClipboard(content: String) {
        if (content == lastReceivedClipboard) return
        
        serviceScope.launch {
            val json = JSONObject().apply {
                put("type", "clipboard")
                put("content", content)
            }.toString()
            
            val peersJson = RustNet.nativeGetPeers()
            val peers = JSONArray(peersJson)
            val connectedJson = RustNet.nativeConnectedPeers()
            val connectedIds = JSONArray(connectedJson)
            val connectedSet = mutableSetOf<String>()
            for (i in 0 until connectedIds.length()) connectedSet.add(connectedIds.getString(i))

            for (i in 0 until peers.length()) {
                val p = peers.getJSONObject(i)
                val nodeId = p.getString("node_id")
                val proto = if (connectedSet.contains(nodeId)) "WS" else "UDP"
                RustNet.nativeSend(nodeId, proto, json)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        multicastLock?.let { if (it.isHeld) it.release() }
        netCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    companion object {
        /** ACTIVE clipboard auto-sync on/off. Shared with the UI (same process). */
        @Volatile var clipboardSyncEnabled = false
        /** This device's node_id, captured from the Rust identity on start. */
        @Volatile var myNodeId = ""
    }
}
