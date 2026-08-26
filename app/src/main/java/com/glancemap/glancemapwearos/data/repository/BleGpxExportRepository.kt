package com.glancemap.glancemapwearos.data.repository

import android.content.Context
import android.content.Intent
import com.glancemap.glancemapwearos.core.service.transfer.ble.BleTransferService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * GMS-free GPX export using the watch's own BLE GATT server.
 *
 * Starts [BleTransferService] with the selected file pre-loaded, then waits until a BLE
 * client (laptop / companion app) connects and requests the export. Success/failure is
 * reported through [BleTransferService.exportState].
 */
class BleGpxExportRepository(
    context: Context,
) : GpxExportRepository {
    private val appContext = context.applicationContext

    override suspend fun sendGpxToPhone(
        file: File,
        displayName: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(file.exists() && file.isFile) { "GPX file not found." }

                val intent =
                    Intent(appContext, BleTransferService::class.java)
                        .setAction(BleTransferService.ACTION_EXPORT)
                        .putExtra(BleTransferService.EXTRA_EXPORT_FILE, file.absolutePath)
                appContext.startForegroundService(intent)

                waitForExport(file.name)
            }
        }

    private suspend fun waitForExport(fileName: String) {
        val deadline = System.currentTimeMillis() + EXPORT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val state = BleTransferService.exportState.value
            if (state.error != null) throw IOException(state.error)
            if (state.done && state.fileName == fileName) return
            delay(POLL_INTERVAL_MS)
        }
        throw IOException("Waiting for BLE client… run: ble_client.py gpx-export $fileName")
    }

    private companion object {
        private const val EXPORT_TIMEOUT_MS = 10 * 60_000L
        private const val POLL_INTERVAL_MS = 200L
    }
}
