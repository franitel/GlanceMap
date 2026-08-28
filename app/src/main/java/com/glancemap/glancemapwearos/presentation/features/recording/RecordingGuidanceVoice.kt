package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import android.speech.tts.TextToSpeech
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.RecordingProgressVibrationSettings
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight TextToSpeech helper for spoken recording progress milestones
 * (e.g. "1 kilometer", "30 minutes"). The tts engine is initialised lazily on the
 * first milestone and kept for the life of the process; [shutdown] is called from
 * the recording ViewModel's [TraceRecordingViewModel.onCleared]. Not Composable:
 * it is driven from the recording ViewModel, so we manage the TTS lifecycle manually.
 *
 * Thread safety: [speak] and [shutdown] are synchronised because the TTS engine is not
 * thread-safe; recording progress is emitted from the ViewModel's main scope.
 */
internal object RecordingGuidanceVoice {
    @Volatile private var tts: TextToSpeech? = null
    private val initialising = AtomicBoolean(false)

    @Synchronized
    private fun ensureInitialised(context: Context) {
        if (tts != null || initialising.get()) return
        initialising.set(true)
        val appContext = context.applicationContext
        tts =
            TextToSpeech(appContext) { status ->
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    val locale = Locale.getDefault()
                    engine.language = locale
                    DebugTelemetry.log(
                        "TraceRecording",
                        "voice=init_success locale=${locale.toLanguageTag()} " +
                            "engine=${engine.defaultEngine ?: "na"}",
                    )
                } else {
                    DebugTelemetry.log("TraceRecording", "voice=init_failed status=$status")
                }
                initialising.set(false)
            }
    }

    /**
     * Speaks [text] through the engine. Returns true when the utterance was queued
     * successfully. Initialises the engine on first use (may not speak the very first
     * milestone until the async TTS init completes).
     */
    @Synchronized
    fun speak(context: Context, text: String): Boolean {
        ensureInitialised(context)
        val engine = tts ?: return false
        return try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, RECORDING_VOICE_UTTERANCE_ID) ==
                TextToSpeech.SUCCESS
        } catch (e: Exception) {
            DebugTelemetry.log("TraceRecording", "voice=speak_error ${e.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialising.set(false)
    }
}

/**
 * Builds the spoken text for a set of triggered recording-progress milestones.
 * [triggers] are Distance(milestone)/Time(milestone) pairs combined with the matching
 * interval to produce "1 kilometer" / "30 minutes", joined with a comma.
 */
internal fun recordingProgressSpokenText(
    triggers: List<RecordingProgressVibrationTrigger>,
    settings: RecordingProgressVibrationSettings,
): String {
    val parts = mutableListOf<String>()
    for (trigger in triggers) {
        when (trigger) {
            is RecordingProgressVibrationTrigger.Distance -> {
                val meters = (settings.distanceMeters * trigger.milestone).toInt().coerceAtLeast(1)
                parts += distanceToSpoken(meters)
            }
            is RecordingProgressVibrationTrigger.Time -> {
                val minutes = (settings.timeMinutes * trigger.milestone).toInt()
                parts += timeToSpoken(minutes)
            }
        }
    }
    return parts.joinToString(", ")
}

private fun distanceToSpoken(meters: Int): String =
    if (meters < 1_000) {
        "$meters meters"
    } else if (meters % 1_000 == 0) {
        val km = meters / 1_000
        spokenKilometers(km)
    } else {
        val km = meters / 1_000.0
        spokenKilometers(km)
    }

private fun spokenKilometers(value: Any): String {
    val text =
        when (value) {
            is Int -> "$value kilometer" + if (value == 1) "" else "s"
            is Double -> "%.1f kilometer%s".format(value, if (value == 1.0) "" else "s")
            else -> "$value kilometers"
        }
    return text
}

private fun timeToSpoken(minutes: Int): String =
    if (minutes % 60 == 0) {
        val hours = minutes / 60
        "$hours hour" + if (hours == 1) "" else "s"
    } else {
        "$minutes minutes"
    }

private const val RECORDING_VOICE_UTTERANCE_ID = "recording_progress_voice"
