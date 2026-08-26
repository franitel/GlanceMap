package com.glancemap.glancemapwearos.core.service.transfer.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * Foreground service that exposes a BLE GATT server for GMS-free GPX transfer.
 *
 * Protocol (GlanceMap BLE v1):
 *  - Service  aabbccdd-...-0
 *    - CMD    aabbccdd-...-1  (write + read)  -> JSON commands from client
 *    - DATA   aabbccdd-...-2  (write-no-response) -> binary chunks [0x01][seq:2B BE][payload]
 *    - NOTIFY aabbccdd-...-3  (notify + read) -> JSON status / export chunks [0x02][seq:2B BE][payload]
 *
 * Commands (JSON on CMD):
 *  {"cmd":"handshake"}                       -> {"cmd":"handshake_ack","proto":1,"watch":"GlanceMap"}
 *  {"cmd":"gpx_upload","filename":"x.gpx","size":123,"sha256":"..."}
 *                                             -> {"cmd":"ready"}
 *  <binary data chunks on DATA>              -> progress notifies every N chunks
 *  {"cmd":"complete"}                        -> {"cmd":"complete_ok","sha256":"..."} | {"cmd":"error","msg":"..."}
 *  {"cmd":"gpx_export","filename":"x.gpx"}   -> {"cmd":"export_start",...} + chunks + {"cmd":"export_done",...}
 *  {"cmd":"list_gpx"}                        -> {"cmd":"gpx_list","files":[...]}
 *  {"cmd":"cancel"}                          -> cancels current upload/export
 *  {"cmd":"stop"}                            -> {"cmd":"bye"} + stops service
 */
class BleTransferService : Service() {

    companion object {
        private const val TAG = "BleTransfer"

        const val ACTION_START = "com.glancemap.glancemapwearos.ble.START"
        const val ACTION_STOP = "com.glancemap.glancemapwearos.ble.STOP"
        const val ACTION_EXPORT = "com.glancemap.glancemapwearos.ble.EXPORT"
        const val EXTRA_EXPORT_FILE = "extra_export_file"

        // Observable export status for watch UI (GPX send flow)
        data class ExportState(
            val exporting: Boolean = false,
            val fileName: String? = null,
            val bytes: Long = 0L,
            val total: Long = 0L,
            val done: Boolean = false,
            val error: String? = null,
        )

        val exportState = kotlinx.coroutines.flow.MutableStateFlow(ExportState())

        val SVC_UUID: UUID = UUID.fromString("aabbccdd-1234-5678-1234-56789abcdef0")
        val CMD_CHAR: UUID = UUID.fromString("aabbccdd-1234-5678-1234-56789abcdef1")
        val DATA_CHAR: UUID = UUID.fromString("aabbccdd-1234-5678-1234-56789abcdef2")
        val NOTIFY_CHAR: UUID = UUID.fromString("aabbccdd-1234-5678-1234-56789abcdef3")

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Safe payload size well below MTU 517
        const val CHUNK_MAX = 500

        /** Frame type markers for DATA channel. */
        const val CHUNK_DATA = 0x01         // Binary data chunk: [0x01][seq:2B BE][payload]
        const val CHUNK_EXPORT = 0x02       // Export chunk from watch: [0x02][seq:2B BE][payload]

        private const val PROGRESS_EVERY_CHUNKS = 64
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "glancemap_ble_transfer"
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    // Upload state (accessed only on worker thread)
    private var uploadPartFile: File? = null
    private var uploadOut: FileOutputStream? = null
    private var uploadName: String? = null
    private var uploadExpectedSize: Long = 0L
    private var uploadSha256: String? = null
    private var uploadReceived: Long = 0L
    private var chunkCounter = 0
    private val receivedSeqs = mutableSetOf<Int>() // Dedup: Samsung envía callbacks duplicados en DATA bursts
    private var uploading = false
    private var exporting = false

    // File selected from the watch UI to export when a client asks (watch-initiated export)
    private var pendingExportFile: String? = null

    private val gpxDir: File by lazy {
        applicationContext.getExternalFilesDir(null)?.let { File(it, "gpx") }
            ?: getDir("gpx", MODE_PRIVATE)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val thread = HandlerThread("BleTransferWorker").also { it.start() }
        workerThread = thread
        worker = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_STOP == intent.action) {
            shutdown()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        startGattServer()
        startAdvertising()
        if (intent != null && ACTION_EXPORT == intent.action) {
            val exportFile = intent.getStringExtra(EXTRA_EXPORT_FILE)
            if (exportFile != null) {
                pendingExportFile = exportFile
                exportState.value =
                    ExportState(
                        exporting = false,
                        fileName = File(exportFile).name,
                    )
            }
        } else {
            pendingExportFile = null
            exportState.value = ExportState()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        runCatching { stopAdvertising() }
        runCatching { stopGattServer() }
        runCatching { closeUpload() }
        pendingExportFile = null
        exportState.value = ExportState()
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Foreground / notification ─────────────────────────────────────

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "GlanceMap BLE Transfer",
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GlanceMap BLE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun startForegroundCompat() {
        val notification = buildNotification("Bluetooth transfer active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── GATT server ───────────────────────────────────────────────────

    private fun startGattServer() {
        if (gattServer != null) return
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val server =
            runCatching { btManager.openGattServer(this, gattCallback) }.getOrNull()
                ?: run {
                    Log.e(TAG, "No se pudo abrir GATT server")
                    return
                }
        gattServer = server

        val service = BluetoothGattService(SVC_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val cmdChar =
            BluetoothGattCharacteristic(
                CMD_CHAR,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ,
            )

        val dataChar =
            BluetoothGattCharacteristic(
                DATA_CHAR,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )

        val notifyChar =
            BluetoothGattCharacteristic(
                NOTIFY_CHAR,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        notifyChar.addDescriptor(
            BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )

        service.addCharacteristic(cmdChar)
        service.addCharacteristic(dataChar)
        service.addCharacteristic(notifyChar)
        server.addService(service)
        notifyCharacteristic = notifyChar

        Log.i(TAG, "GATT server iniciado: service=$SVC_UUID")
    }

    private fun stopGattServer() {
        gattServer?.close()
        gattServer = null
        notifyCharacteristic = null
        connectedDevices.clear()
        subscribedDevices.clear()
    }

    private val gattCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices.add(device)
                    Log.i(TAG, "Conectado: ${device.address}")
                } else {
                    connectedDevices.remove(device)
                    subscribedDevices.remove(device)
                    Log.i(TAG, "Desconectado: ${device.address}")
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseOffset: Boolean,
                length: Int,
                value: ByteArray?,
            ) {
                when (characteristic.uuid) {
                    CMD_CHAR -> {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null,
                        )
                        val text = value?.toString(Charsets.UTF_8).orEmpty()
                        Log.i(TAG, "CMD: $text")
                        postCommand(text)
                    }

                    DATA_CHAR -> {
                        // Write-without-response: do NOT send a response.
                        Log.i(TAG, "DATA write len=${value?.size ?: -1} uploading=$uploading")
                        value?.let { handleDataChunk(it) }
                    }

                    else -> {
                        Log.i(TAG, "WRITE a char desconocido: ${characteristic.uuid} len=${value?.size ?: -1}")
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null,
                        )
                    }
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val status = statusJson().toString().toByteArray(Charsets.UTF_8)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, status)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                if (descriptor.uuid == CCCD_UUID && descriptor.characteristic?.uuid == NOTIFY_CHAR) {
                    val enabled = value != null && value.size == 2 && value[0].toInt() and 0xFF == 0x01
                    if (enabled) {
                        subscribedDevices.add(device)
                        Log.i(TAG, "Notificaciones habilitadas para ${device.address}")
                    } else {
                        subscribedDevices.remove(device)
                    }
                }
            }
        }

    // ── Advertising ───────────────────────────────────────────────────

    private fun startAdvertising() {
        if (advertiser != null) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth apagado")
            return
        }
        val adv = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull() ?: return
        advertiser = adv

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .build()

        val data =
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { Log.e(TAG, "Advertising fallo: ${it.message}") }
    }

    private fun stopAdvertising() {
        advertiser?.let { adv ->
            runCatching { adv.stopAdvertising(advertiseCallback) }
        }
        advertiser = null
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Advertising OK")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising fallo, error=$errorCode")
            }
        }

    // ── Protocol handling (worker thread) ─────────────────────────────

    private fun postCommand(json: String) {
        worker?.post {
            runCatching {
                val obj = JSONObject(json)
                when (val cmd = obj.optString("cmd")) {
                    "handshake" -> sendJson(JSONObject().put("cmd", "handshake_ack").put("proto", 1).put("watch", "GlanceMap"))
                    "gpx_upload" -> handleUploadStart(obj)
                    "complete" -> handleComplete()
                    "gpx_export" -> handleExportStart(obj)
                    "list_gpx" -> handleListGpx()
                    "cancel" -> {
                        closeUpload(deletePartial = true)
                        exporting = false
                        sendJson(JSONObject().put("cmd", "cancel_ok"))
                    }
                    "stop" -> {
                        sendJson(JSONObject().put("cmd", "bye"))
                        worker?.post { shutdown() }
                    }
                    else -> sendJson(JSONObject().put("cmd", "error").put("msg", "unknown_command:$cmd"))
                }
            }.onFailure { e ->
                Log.w(TAG, "Error procesando comando: ${e.message}")
                sendJson(JSONObject().put("cmd", "error").put("msg", e.message.orEmpty()))
            }
        }
    }

    private fun handleUploadStart(obj: JSONObject) {
        val rawName = obj.optString("filename", "").substringAfterLast('/').substringAfterLast('\\').trim()
        if (!rawName.endsWith(".gpx", ignoreCase = true) || rawName.length > 200) {
            sendJson(JSONObject().put("cmd", "error").put("msg", "bad_filename"))
            return
        }
        if (!gpxDir.exists() && !gpxDir.mkdirs()) {
            sendJson(JSONObject().put("cmd", "error").put("msg", "no_storage"))
            return
        }

        closeUpload(deletePartial = true)
        val partFile = File(gpxDir, ".$rawName.part")
        partFile.delete()

        val out = runCatching { FileOutputStream(partFile) }.getOrElse {
            sendJson(JSONObject().put("cmd", "error").put("msg", "cannot_open"))
            return
        }

        uploadPartFile = partFile
        uploadOut = out
        uploadName = rawName
        uploadExpectedSize = obj.optLong("size", 0L).coerceAtLeast(0L)
        uploadSha256 = obj.optString("sha256", "").lowercase().takeIf { it.isNotBlank() }
        uploadReceived = 0L
        chunkCounter = 0
        uploading = true

        Log.i(TAG, "Upload iniciado: $rawName (${uploadExpectedSize}B)")
        sendJson(
            JSONObject()
                .put("cmd", "ready")
                .put("filename", rawName)
                .put("expected", uploadExpectedSize),
        )
    }

    private fun handleDataChunk(value: ByteArray) {
        if (!uploading || value.isEmpty()) return
        if (value[0].toInt() != CHUNK_DATA) {
            Log.w(TAG, "Chunk tipo inesperado: ${value[0]}")
            return
        }
        if (value.size < 3) return

        val seq = ((value[1].toInt() and 0xFF) shl 8) or (value[2].toInt() and 0xFF)

        // Post EVERYTHING to worker thread for sequential processing.
        worker?.post {
            synchronized(receivedSeqs) {
                if (!receivedSeqs.add(seq)) {
                    Log.d(TAG, "Chunk $seq duplicado — skipping")
                    return@post
                }
            }

            val payload = value.copyOfRange(3, value.size)
            uploadReceived += payload.size.toLong()
            chunkCounter++

            // Send progress every N chunks.
            if (chunkCounter % PROGRESS_EVERY_CHUNKS == 0) {
                sendJson(JSONObject()
                    .put("cmd", "progress")
                    .put("received", uploadReceived)
                    .put("total", uploadExpectedSize)
                    .put("seq", seq))
            }

            Log.d(TAG, "Chunk $seq recibido (${payload.size}B, total=${uploadReceived}/${uploadExpectedSize})")

            // Write to file.
            val out = uploadOut ?: return@post
            runCatching { out.write(payload) }.onFailure {
                Log.e(TAG, "Error escribiendo chunk: ${it.message}")
                sendJson(JSONObject().put("cmd", "error").put("msg", "write_failed"))
                closeUpload(deletePartial = true)
                return@post
            }
        }
    }

    private fun handleComplete() {
        // Wait for any pending chunk callbacks to arrive from BLE stack.
        Thread.sleep(200)

        // Capture state AFTER waiting — ensures all chunks processed.
        val partFile = uploadPartFile
        val name = uploadName
        val expected = uploadExpectedSize
        val expectedSha = uploadSha256

        Log.d(TAG, "handleComplete: expected=$expected receivedSeqs.size=${receivedSeqs.size} partFile.exists=${partFile?.exists()}")

        if (partFile == null || name == null) {
            sendJson(JSONObject().put("cmd", "error").put("msg", "no_upload_state"))
            return
        }

        // Flush and close.
        val out = uploadOut  // Capture before flush/close
        runCatching { out?.flush() }.onFailure {
            Log.e(TAG, "Flush failed: ${it.message}")
            partFile.delete()
            sendJson(JSONObject().put("cmd", "error").put("msg", "flush_failed"))
            return
        }

        closeUpload(deletePartial = false)

        // Check file size on DISK (not counter).
        val diskSize = partFile.length()
        Log.d(TAG, "handleComplete: diskSize=$diskSize expected=$expected receivedCounter=$uploadReceived")

        if (expected > 0L && diskSize != expected) {
            partFile.delete()
            sendJson(JSONObject()
                .put("cmd", "error")
                .put("msg", "size_mismatch disk=$diskSize expected=$expected counter=$uploadReceived")
                .put("expected", expected)
                .put("received", uploadReceived))
            return
        }

        // SHA256.
        val actualSha = sha256Of(partFile).also { Log.d(TAG, "SHA256: $it diskSize=$diskSize") }
        if (expectedSha != null && expectedSha != actualSha) {
            partFile.delete()
            sendJson(JSONObject()
                .put("cmd", "error")
                .put("msg", "checksum_mismatch")
                .put("expected", expectedSha)
                .put("actual", actualSha))
            return
        }

        // Rename.
        val finalFile = File(gpxDir, name)
        if (!partFile.renameTo(finalFile)) {
            Log.w(TAG, "Rename failed: ${partFile.absolutePath} → ${finalFile.absolutePath}")
            sendJson(JSONObject().put("cmd", "error").put("msg", "rename_failed"))
            return
        }

        Log.i(TAG, "Upload completo: $name (${finalFile.length()}B) sha256=$actualSha")
        sendJson(JSONObject()
            .put("cmd", "complete_ok")
            .put("filename", name)
            .put("sha256", actualSha))
    }

    private fun handleExportStart(obj: JSONObject) {
        if (exporting) {
            sendJson(JSONObject().put("cmd", "error").put("msg", "busy"))
            return
        }
        val rawName =
            obj.optString("filename", "")
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
                .ifBlank { pendingExportFile?.let { File(it).name } ?: "" }
        val file = File(gpxDir, rawName)
        if (!file.exists() || !file.isFile || !rawName.endsWith(".gpx", ignoreCase = true)) {
            sendJson(JSONObject().put("cmd", "error").put("msg", "not_found"))
            return
        }

        exporting = true
        exportState.value =
            ExportState(
                exporting = true,
                fileName = rawName,
                bytes = 0L,
                total = file.length(),
            )
        Thread {
            try {
                val sha = sha256Of(file)
                sendJson(
                    JSONObject()
                        .put("cmd", "export_start")
                        .put("filename", rawName)
                        .put("size", file.length())
                        .put("sha256", sha),
                )

                val buffer = ByteArray(CHUNK_MAX)
                var seq = 0
                var sentBytes = 0L
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val frame = ByteArray(read + 3)
                        frame[0] = CHUNK_EXPORT.toByte()
                        frame[1] = ((seq shr 8) and 0xFF).toByte()
                        frame[2] = (seq and 0xFF).toByte()
                        System.arraycopy(buffer, 0, frame, 3, read)
                        sendBinary(frame)
                        seq = (seq + 1) and 0xFFFF
                        sentBytes += read
                        exportState.value =
                            ExportState(
                                exporting = true,
                                fileName = rawName,
                                bytes = sentBytes,
                                total = file.length(),
                            )
                        // Gentle pacing so the BLE stack doesn't drop frames.
                        Thread.sleep(3)
                    }
                }

                sendJson(
                    JSONObject()
                        .put("cmd", "export_done")
                        .put("filename", rawName)
                        .put("chunks", seq),
                )
                exportState.value =
                    ExportState(
                        exporting = false,
                        fileName = rawName,
                        bytes = file.length(),
                        total = file.length(),
                        done = true,
                    )
                Log.i(TAG, "Export completo: $rawName (${file.length()}B)")
            } catch (e: Exception) {
                Log.w(TAG, "Export fallo: ${e.message}")
                sendJson(JSONObject().put("cmd", "error").put("msg", "export_failed"))
                exportState.value =
                    ExportState(
                        exporting = false,
                        fileName = rawName,
                        error = e.message ?: "export_failed",
                    )
            } finally {
                exporting = false
            }
        }.start()
    }

    private fun handleListGpx() {
        val files =
            if (!gpxDir.exists()) {
                emptyList()
            } else {
                gpxDir.listFiles { f -> f.isFile && f.name.endsWith(".gpx", ignoreCase = true) }
                    ?.map { it.name }
                    ?.sorted()
                    .orEmpty()
            }
        val arr = JSONArray().apply { files.forEach { put(it) } }
        sendJson(JSONObject().put("cmd", "gpx_list").put("files", arr))
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun closeUpload(deletePartial: Boolean = false) {
        runCatching { uploadOut?.flush() }
        runCatching { uploadOut?.close() }
        uploadOut = null
        if (deletePartial) {
            uploadPartFile?.delete()
        }
        uploadPartFile = null
        uploadName = null
        uploadExpectedSize = 0L
        uploadSha256 = null
        uploadReceived = 0L
        chunkCounter = 0
        receivedSeqs.clear() // Reset dedup set for next transfer
        uploading = false
    }

    private fun statusJson(): JSONObject {
        val status = JSONObject()
        status.put("cmd", "status")
        status.put("proto", 1)
        status.put("uploading", uploading)
        status.put("exporting", exporting)
        status.put("connected", connectedDevices.size)
        status.put("subscribed", subscribedDevices.size)
        status.put("gpx_dir", gpxDir.absolutePath)
        return status
    }

    private fun sendJson(obj: JSONObject) {
        sendBinary(obj.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendBinary(payload: ByteArray) {
        val server = gattServer ?: return
        val char = notifyCharacteristic ?: return
        for (device in subscribedDevices) {
            runCatching { server.notifyCharacteristicChanged(device, char, false, payload) }
                .onFailure { Log.w(TAG, "notify fallo: ${it.message}") }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}