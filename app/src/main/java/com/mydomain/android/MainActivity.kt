package com.mydomain.android

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mydomain.android.core.NetworkingUtils.wifiIpv4
import com.mydomain.android.presentation.screens.LoginScreen
import com.mydomain.android.presentation.screens.MainScreen

data class Peer(
    val nodeId: String, val name: String, val ip: String,
    val tcpPort: Int, val udpPort: Int, val wsPort: Int, val source: String,
)

data class LogEntry(val dir: String, val peer: String, val ip: String, val protocol: String, val text: String, val ok: Boolean)

class MainActivity : ComponentActivity() {
    private var connectivityManager: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the background service for networking and clipboard sync
        val serviceIntent = Intent(this, NetService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Re-register on network change.
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { RustNet.nativeNetworkChanged(wifiIpv4()) }
            override fun onLost(network: Network) { RustNet.nativeNetworkChanged(wifiIpv4()) }
        }
        runCatching { connectivityManager?.registerDefaultNetworkCallback(netCallback!!) }

        val prefs = getSharedPreferences("md", Context.MODE_PRIVATE)
        setContent { MaterialTheme { App(prefs) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        netCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
    }
}

@Composable
fun App(prefs: android.content.SharedPreferences) {
    var ready by remember { mutableStateOf(RustNet.nativeIsReady() == "true") }
    if (!ready) {
        LoginScreen(prefs) { ready = true }
    } else {
        MainScreen(prefs, onLogout = { ready = false })
    }
}
