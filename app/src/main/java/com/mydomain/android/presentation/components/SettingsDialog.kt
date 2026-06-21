package com.mydomain.android.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
