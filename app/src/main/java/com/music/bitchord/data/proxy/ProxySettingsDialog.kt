package com.music.bitchord.data.proxy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ProxySettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    val loaded = remember {
        runCatching { ProxyStorage.load(context) }
    }
    val saved = loaded.getOrDefault(ProxyConfig.Settings())

    var enabled by remember { mutableStateOf(saved.enabled) }
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var username by remember { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    var error by remember {
        mutableStateOf(
            if (loaded.isFailure)
                "Could not open encrypted storage."
            else ""
        )
    }
    var success by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HTTP Proxy") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable proxy")
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }

                Text("Route YouTube Music traffic through an HTTP proxy.")

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Proxy hostname") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )

                Text("Changes take effect after force stopping and reopening BitChord.")

                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }

                if (success) {
                    Text("Saved. Restart BitChord Dev to apply.")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = port.toIntOrNull()
                    if (p == null || p !in 1..65535 ||
                        (enabled && host.isBlank())) {
                        error = "Enter a valid hostname and port."
                        success = false
                    } else {
                        runCatching {
                            ProxyStorage.save(
                                context,
                                ProxyConfig.Settings(
                                    enabled,
                                    host.trim(),
                                    p,
                                    username.trim(),
                                    password
                                )
                            )
                        }.onSuccess {
                            error = ""
                            success = true
                        }.onFailure {
                            error = "Unable to save encrypted settings."
                            success = false
                        }
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
