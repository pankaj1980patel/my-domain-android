package com.mydomain.android

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Peer(
    val nodeId: String,
    val name: String,
    val ip: String,
    val tcpPort: Int,
    val udpPort: Int,
)

data class LogEntry(
    val direction: String, // "in" | "out"
    val peer: String,
    val ip: String,
    val protocol: String,
    val text: String,
)

class MainActivity : ComponentActivity() {

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Receiving multicast on Android requires holding a MulticastLock.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("mydomain-discovery").apply {
            setReferenceCounted(true)
            acquire()
        }

        // Start the Rust networking core with this device's name + Wi-Fi IP so
        // multicast discovery uses the Wi-Fi interface (not cellular).
        val identityJson = RustNet.nativeStart(Build.MODEL ?: "android", wifiIpv4())
        val identity = JSONObject(identityJson)

        setContent {
            MaterialTheme {
                AppScreen(
                    myName = identity.optString("name", Build.MODEL ?: "android"),
                    myIp = identity.optString("ip", "?"),
                    myTcp = identity.optInt("tcp_port"),
                    myUdp = identity.optInt("udp_port"),
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        multicastLock?.let { if (it.isHeld) it.release() }
    }
}

/**
 * Best-effort Wi-Fi IPv4 address. Prefers the wlan interface, then any
 * site-local IPv4. Returns "" if none (e.g. Wi-Fi off) so Rust can fall back.
 */
private fun wifiIpv4(): String {
    fun scan(preferWlan: Boolean): String? {
        val ifaces = runCatching { java.net.NetworkInterface.getNetworkInterfaces() }
            .getOrNull() ?: return null
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
    return scan(preferWlan = true) ?: scan(preferWlan = false) ?: ""
}

private fun parsePeers(json: String): List<Peer> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Peer(
            nodeId = o.getString("node_id"),
            name = o.getString("name"),
            ip = o.getString("ip"),
            tcpPort = o.getInt("tcp_port"),
            udpPort = o.getInt("udp_port"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(myName: String, myIp: String, myTcp: Int, myUdp: Int) {
    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    val log = remember { mutableStateListOf<LogEntry>() }

    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var protocol by remember { mutableStateOf("UDP") }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Poll the Rust core for peers and incoming messages.
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val p = runCatching { parsePeers(RustNet.nativeGetPeers()) }.getOrDefault(emptyList())
                val msgsJson = runCatching { RustNet.nativePollMessages() }.getOrDefault("[]")
                withContext(Dispatchers.Main) {
                    peers = p
                    if (selectedNodeId == null && p.isNotEmpty()) selectedNodeId = p.first().nodeId
                    if (selectedNodeId != null && p.none { it.nodeId == selectedNodeId }) {
                        selectedNodeId = p.firstOrNull()?.nodeId
                    }
                    val arr = JSONArray(msgsJson)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        log.add(
                            0,
                            LogEntry(
                                direction = "in",
                                peer = o.optString("from"),
                                ip = o.optString("ip"),
                                protocol = o.optString("protocol"),
                                text = o.optString("text"),
                            )
                        )
                    }
                }
            }
            delay(1000)
        }
    }

    val selectedPeer = peers.firstOrNull { it.nodeId == selectedNodeId }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("LAN Messenger", fontWeight = FontWeight.Bold)
                    Text("$myName · $myIp · tcp $myTcp · udp $myUdp", fontSize = 12.sp)
                }
            })
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text("Peers (${peers.size})", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (peers.isEmpty()) {
                Text("Searching the LAN…", fontSize = 13.sp)
            } else {
                peers.forEach { p ->
                    Text("• ${p.name}  —  ${p.ip} · tcp ${p.tcpPort} · udp ${p.udpPort}", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Send", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))

            // Peer picker
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = !menuExpanded },
            ) {
                OutlinedTextField(
                    value = selectedPeer?.let { "${it.name} (${it.ip})" } ?: "No peers",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("To") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    peers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text("${p.name} (${p.ip})") },
                            onClick = {
                                selectedNodeId = p.nodeId
                                menuExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = protocol == "UDP", onClick = { protocol = "UDP" }, label = { Text("UDP") })
                FilterChip(selected = protocol == "TCP", onClick = { protocol = "TCP" }, label = { Text("TCP") })
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message / command") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val nodeId = selectedNodeId
                    val text = message.trim()
                    if (nodeId == null) { status = "No peer selected"; return@Button }
                    if (text.isEmpty()) { status = "Message is empty"; return@Button }
                    val peerName = selectedPeer?.name ?: "peer"
                    val peerIp = selectedPeer?.ip ?: ""
                    scope.launch {
                        val err = withContext(Dispatchers.IO) {
                            RustNet.nativeSend(nodeId, protocol, text)
                        }
                        if (err.isEmpty()) {
                            log.add(0, LogEntry("out", peerName, peerIp, protocol, text))
                            message = ""
                            status = "Sent over $protocol"
                        } else {
                            status = err
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send") }

            if (status.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))
            Text("Messages", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(log) { e ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "[${e.protocol}] ${if (e.direction == "in") "from" else "to"} ${e.peer}  ${e.ip}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(e.text)
                        }
                    }
                }
            }
        }
    }
}
