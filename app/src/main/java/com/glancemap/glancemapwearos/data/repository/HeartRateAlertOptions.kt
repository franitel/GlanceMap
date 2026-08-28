package com.glancemap.glancemapwearos.data.repository

import kotlin.math.abs

/** Discrete BPM options for the high HR alert threshold (100..220 in 5 bpm steps). */
internal val heartRateAlertHighBpmOptions: List<Int> =
    (SettingsRepository.MIN_HEART_RATE_ALERT_BPM..SettingsRepository.MAX_HEART_RATE_ALERT_BPM step 5).toList()

/** Discrete BPM options for the low HR alert threshold (40..100 in 5 bpm steps). */
internal val heartRateAlertLowBpmOptions: List<Int> =
    (SettingsRepository.MIN_HEART_RATE_ALERT_BPM..100 step 5).toList()

/**
 * Rounds a BPM value to the nearest option in [options] (preferring the lower option on ties).
 * Falls back to the raw value when the list is empty.
 */
internal fun nearestHeartRateAlertBpm(
    value: Int,
    options: List<Int>,
): Int =
    options
        .minWithOrNull(compareBy<Int> { abs(it - value) }.thenBy { it })
        ?: value
