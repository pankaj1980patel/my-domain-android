package com.mydomain.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class Peer(
    val nodeId: String, val name: String, val ip: String,
    val tcpPort: Int, val udpPort: Int, val wsPort: Int, val source: String,
)

data class LogEntry(val dir: String, val peer: String, val ip: String, val protocol: String, val text: String, val ok: Boolean)

class MainActivity : ComponentActivity() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("mydomain").apply { setReferenceCounted(true); acquire() }

        RustNet.nativeStart(Build.MODEL ?: "android", wifiIpv4())

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
        multicastLock?.let { if (it.isHeld) it.release() }
        netCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
    }
}

private fun wifiIpv4(): String {
    fun scan(preferWlan: Boolean): String? {
        val ifaces = runCatching { java.net.NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            if (preferWlan && !(name.startsWith("wlan") || name.startsWith("ap"))) continue
            for (addr in iface.inetAddresses) {
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress
                    if (host != null && (preferWlan || addr.isSiteLocalAddress)) return host
                }
            }
        }
        return null
    }
    return scan(true) ?: scan(false) ?: ""
}

private fun parsePeers(json: String): List<Peer> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Peer(o.getString("node_id"), o.getString("name"), o.getString("ip"),
            o.getInt("tcp_port"), o.getInt("udp_port"), o.optInt("ws_port"), o.optString("source", "registry"))
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

@Composable
fun LoginScreen(prefs: android.content.SharedPreferences, onReady: () -> Unit) {
    var server by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var user by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun submit(register: Boolean) {
        if (server.isBlank() || user.isBlank() || pass.isBlank() || key.isBlank()) {
            status = "All fields are required."; return
        }
        status = "Connecting…"
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                val a = if (register) RustNet.nativeAuthRegister(server, user, pass)
                else RustNet.nativeAuthLogin(server, user, pass)
                if (a.isNotEmpty()) return@withContext a
                RustNet.nativeSetEncryptionKey(key)
            }
            if (err.isEmpty() && RustNet.nativeIsReady() == "true") {
                prefs.edit().putString("server", server).putString("user", user).apply()
                withContext(Dispatchers.IO) { RustNet.nativeRefresh() }
                onReady()
            } else {
                status = err.removePrefix("ERROR: ")
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text("my-domain", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Log in, then set your encryption key to unlock messaging.", fontSize = 13.sp)
        OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(key, { key = it }, label = { Text("Encryption key") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { key = RustNet.nativeGenerateKey() }) { Text("Gen") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { submit(false) }, modifier = Modifier.weight(1f)) { Text("Log in") }
            OutlinedButton(onClick = { submit(true) }, modifier = Modifier.weight(1f)) { Text("Register") }
        }
        // DEV / LAN-only: skip the registry. Needs just a username + encryption key;
        // LAN discovery and WS messaging work between devices that share both.
        OutlinedButton(
            onClick = {
                if (user.isBlank() || key.isBlank()) {
                    status = "Username and encryption key required for LAN mode."; return@OutlinedButton
                }
                val e = RustNet.nativeDevLogin(user, key)
                if (e.isEmpty()) {
                    prefs.edit().putString("user", user).apply()
                    onReady()
                } else status = e.removePrefix("ERROR: ")
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Skip login (LAN only)") }
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        Text("Use the SAME encryption key on all your devices.", fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(prefs: android.content.SharedPreferences, onLogout: () -> Unit) {
    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var connected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val log = remember { mutableStateListOf<LogEntry>() }
    var selected by remember { mutableStateOf<String?>(null) }
    var protocol by remember { mutableStateOf("WS") }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            val p = withContext(Dispatchers.IO) { runCatching { parsePeers(RustNet.nativeGetPeers()) }.getOrDefault(emptyList()) }
            val msgs = withContext(Dispatchers.IO) { runCatching { RustNet.nativePollMessages() }.getOrDefault("[]") }
            val conn = withContext(Dispatchers.IO) { runCatching { RustNet.nativeConnectedPeers() }.getOrDefault("[]") }
            peers = p
            connected = runCatching {
                val a = JSONArray(conn)
                (0 until a.length()).map { a.getString(it) }.toSet()
            }.getOrDefault(emptySet())
            if (selected == null && p.isNotEmpty()) selected = p.first().nodeId
            val arr = JSONArray(msgs)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                log.add(0, LogEntry("in", o.optString("from"), o.optString("ip"), o.optString("protocol"), o.optString("text"), o.optBoolean("ok", true)))
            }
            delay(1000)
        }
    }

    val sel = peers.firstOrNull { it.nodeId == selected }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("LAN Messenger", fontWeight = FontWeight.Bold) },
            actions = {
                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { RustNet.nativeRefresh() } } }) { Text("Refresh") }
                TextButton(onClick = { RustNet.nativeScanLan() }) { Text("Scan") }
                IconButton(onClick = { showSettings = true }) { Text("⚙") }
            },
        )
    }) { inner ->
        Column(Modifier.padding(inner).padding(16.dp).fillMaxSize()) {
            Text("Peers (${peers.size})", fontWeight = FontWeight.SemiBold)
            peers.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.name}  [${p.source}]", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${p.ip} · tcp ${p.tcpPort} · udp ${p.udpPort} · ws ${p.wsPort}", fontSize = 11.sp)
                    }
                    if (p.wsPort != 0) {
                        val isConnected = connected.contains(p.nodeId)
                        OutlinedButton(
                            enabled = !isConnected,
                            onClick = {
                                scope.launch { val e = withContext(Dispatchers.IO) { RustNet.nativeConnectWs(p.nodeId) }; if (e.isNotEmpty()) status = e }
                            },
                        ) { Text(if (isConnected) "WS ✓" else "WS", fontSize = 12.sp) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Send", fontWeight = FontWeight.SemiBold)
            ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = !menu }) {
                OutlinedTextField(
                    value = sel?.let { "${it.name} (${it.ip})" } ?: "No peers", onValueChange = {},
                    readOnly = true, label = { Text("To") }, modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
                )
                ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    peers.forEach { p ->
                        DropdownMenuItem(text = { Text("${p.name} (${p.ip})") }, onClick = { selected = p.nodeId; menu = false })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                listOf("UDP", "TCP", "WS").forEach { pr ->
                    FilterChip(selected = protocol == pr, onClick = { protocol = pr }, label = { Text(pr) })
                }
            }
            OutlinedTextField(message, { message = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val node = selected ?: return@Button
                val text = message.trim()
                if (text.isEmpty()) { status = "Message is empty"; return@Button }
                val peerName = sel?.name ?: "peer"; val peerIp = sel?.ip ?: ""
                scope.launch {
                    val e = withContext(Dispatchers.IO) {
                        if (protocol == "WS") { RustNet.nativeConnectWs(node); delay(400) }
                        RustNet.nativeSend(node, protocol, text)
                    }
                    if (e.isEmpty()) {
                        log.add(0, LogEntry("out", peerName, peerIp, protocol, text, true)); message = ""; status = "Sent over $protocol"
                    } else status = e.removePrefix("ERROR: ")
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Send") }
            if (status.isNotEmpty()) Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)

            Spacer(Modifier.height(12.dp))
            Text("Messages", fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.fillMaxSize()) {
                items(log) { e ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("[${e.protocol}] ${if (e.dir == "in") "from" else "to"} ${e.peer}  ${e.ip}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(e.text, color = if (e.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false }, onLogout = {
            RustNet.nativeLogout()
            showSettings = false; onLogout()
        })
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit, onLogout: () -> Unit) {
    var newKey by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Change encryption key", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newKey, { newKey = it }, label = { Text("New key") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { newKey = RustNet.nativeGenerateKey() }) { Text("Gen") }
                }
                OutlinedTextField(pass, { pass = it }, label = { Text("Confirm password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val e = withContext(Dispatchers.IO) { RustNet.nativeUpdateEncryptionKey(newKey, pass) }
                    status = if (e.isEmpty()) { onDismiss(); "" } else e.removePrefix("ERROR: ")
                }
            }) { Text("Update key") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onLogout) { Text("Log out") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
