package com.glancemap.glancemapwearos.presentation.features.ble

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.core.service.transfer.ble.BleTransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import android.app.ActivityManager

@Composable
fun BluetoothTransferScreen(onOpenGeneralSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(isServiceRunning(context)) }
    var statusText by remember { mutableStateOf("") }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ ->
            startService(context)
            isRunning = true
        }

    fun ensurePermissionsAndStart() {
        val permissions =
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        val missing = permissions.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            startService(context)
            isRunning = true
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Poll service state while the screen is visible
    androidx.compose.runtime.LaunchedEffect(isRunning) {
        while (isActive) {
            isRunning = isServiceRunning(context)
            statusText =
                if (isRunning) {
                    "BLE activo — visible como 'GlanceMap'.\nConecta desde el teléfono o portátil."
                } else {
                    "Servicio detenido.\nInícialo para recibir GPX por Bluetooth."
                }
            delay(1500)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Bluetooth Transfer", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (isRunning) {
                    context.stopService(Intent(context, BleTransferService::class.java))
                    isRunning = false
                } else {
                    ensurePermissionsAndStart()
                }
            },
            enabled = true,
        ) {
            Text(if (isRunning) "Stop BLE" else "Start BLE")
        }
    }
}

private fun startService(context: android.content.Context) {
    val intent = Intent(context, BleTransferService::class.java).setAction(BleTransferService.ACTION_START)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun isServiceRunning(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
    return manager.getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == BleTransferService::class.java.name }
}