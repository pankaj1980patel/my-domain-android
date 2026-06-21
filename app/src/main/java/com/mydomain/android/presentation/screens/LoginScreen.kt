package com.mydomain.android.presentation.screens

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydomain.android.RustNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
fun LoginScreen(prefs: SharedPreferences, onReady: () -> Unit) {
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