package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.glancemap.glancemapwearos.data.repository.HeartRateAlertSettings

/**
 * Which HR alert threshold was crossed (used to pick the vibration pattern).
 */
internal enum class HeartRateAlertKind {
    HIGH,
    LOW,
}

/**
 * Detects when the live heart rate crosses the configured high/low thresholds during a recording.
 *
 * Firing is gated by a cooldown per threshold so a rapidly oscillating reader (common on
 * wrist optical sensors) does not spam alerts. The threshold itself is evaluated per sample
 * (>= high / <= low) and the cooldown (default [HEART_RATE_ALERT_COOLDOWN_NANOS]) prevents
 * re-firing for the same sustained deviation.
 */
internal class HeartRateAlertTracker(
    private val realtimeNanosProvider: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    private var settings = HeartRateAlertSettings()
    private var lastHighTriggerRealtimeNanos: Long? = null
    private var lastLowTriggerRealtimeNanos: Long? = null

    fun start(settings: HeartRateAlertSettings) {
        this.settings = settings
        lastHighTriggerRealtimeNanos = null
        lastLowTriggerRealtimeNanos = null
    }

    /**
     * Feed each new heart-rate sample. Returns [HeartRateAlertKind.HIGH] on a high-threshold
     * max, [HeartRateAlertKind.LOW] on a low-threshold min, or null when nothing fires.
     */
    fun onHeartRate(bpm: Int?): HeartRateAlertKind? {
        val current = bpm ?: return null
        if (current <= 0) return null
        val now = realtimeNanosProvider()
        return when {
            settings.highEnabled &&
                current >= settings.highBpm &&
                lastHighTriggerRealtimeNanos
                    ?.let { prev -> now - prev >= HEART_RATE_ALERT_COOLDOWN_NANOS } != false -> {
                lastHighTriggerRealtimeNanos = now
                HeartRateAlertKind.HIGH
            }
            settings.lowEnabled &&
                current <= settings.lowBpm &&
                lastLowTriggerRealtimeNanos
                    ?.let { prev -> now - prev >= HEART_RATE_ALERT_COOLDOWN_NANOS } != false -> {
                lastLowTriggerRealtimeNanos = now
                HeartRateAlertKind.LOW
            }
            else -> null
        }
    }
}

/**
 * Buzzes the watch with a high (3 pulses) or low (2 pulses) heartbeat-pattern vibration.
 */
internal fun vibrateHeartRateAlert(context: Context?, kind: HeartRateAlertKind): Boolean {
    val vibrator = context?.heartRateAlertVibrator() ?: return false
    if (!vibrator.hasVibrator()) return false
    val waveform =
        when (kind) {
            HeartRateAlertKind.HIGH -> longArrayOf(0L, 70L, 55L, 70L, 55L, 70L)
            HeartRateAlertKind.LOW -> longArrayOf(0L, 70L, 55L, 70L)
        }
    vibrator.vibrate(VibrationEffect.createWaveform(waveform, -1))
    return true
}

private fun Context.heartRateAlertVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

/**
 * Emits an audible alarm tone through the watch speaker: 3 beeps for a high HR alert,
 * 2 beeps for a low HR alert. Runs synchronously (blocking loop) as each ToneGenerator
 * tone is short (~150 ms) so the total time is well under a second.
 */
internal fun beepHeartRateAlert(kind: HeartRateAlertKind) {
    val pulses = if (kind == HeartRateAlertKind.HIGH) HEART_RATE_ALERT_HIGH_PULSES else HEART_RATE_ALERT_LOW_PULSES
    val tone =
        ToneGenerator(
            AudioManager.STREAM_ALARM,
            HEART_RATE_ALERT_TONE_VOLUME,
        )
    try {
        val gapMs = HEART_RATE_ALERT_TONE_ON_MS + HEART_RATE_ALERT_TONE_GAP_MS
        repeat(pulses) { index ->
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, HEART_RATE_ALERT_TONE_ON_MS)
            if (index < pulses - 1) {
                Thread.sleep(gapMs.toLong())
            }
        }
    } finally {
        tone.release()
    }
}

private const val HEART_RATE_ALERT_COOLDOWN_NANOS = 15_000_000_000L // 15 s
private const val HEART_RATE_ALERT_HIGH_PULSES = 3
private const val HEART_RATE_ALERT_LOW_PULSES = 2
private const val HEART_RATE_ALERT_TONE_ON_MS = 150
private const val HEART_RATE_ALERT_TONE_GAP_MS = 120
private const val HEART_RATE_ALERT_TONE_VOLUME = 60
