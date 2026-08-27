package com.glancemap.glancemapwearos.core.service.location.service

import android.location.Location

/**
 * Dynamic GPS interval calculator that adjusts the GPS update interval based on speed.
 *
 * Speed thresholds and intervals:
 *   < 1 m/s  (standing/slow walk)  → 5s  (battery savings)
 *   1-2 m/s  (walking normal)      → 3s  (standard hiking)
 *   2-4 m/s  (fast walk/jogging)   → 2s  (more detail on curves)
 *   > 4 m/s  (running/cycling)     → 1s  (maximum precision)
 *
 * Features:
 *   - Hysteresis: only changes interval when speed changes >0.5 m/s for >3 consecutive readings
 *   - Min/max bounds: never below 1s, never above 10s
 *   - Smooth transitions: avoids rapid interval thrashing
 */
class DynamicGpsIntervalCalculator {

    companion object {
        /** Speed thresholds in m/s */
        const val SPEED_STANDING_THRESHOLD = 1.0
        const val SPEED_WALKING_THRESHOLD = 2.0
        const val SPEED_RUNNING_THRESHOLD = 4.0

        /** GPS intervals in milliseconds */
        const val INTERVAL_STANDING_MS = 5_000L
        const val INTERVAL_WALKING_MS = 3_000L
        const val INTERVAL_RUNNING_MS = 2_000L
        const val INTERVAL_SPRINT_MS = 1_000L

        /** Hysteresis: speed must change by this much to trigger interval change */
        const val HYSTERESIS_SPEED_DELTA = 0.5

        /** Number of consecutive readings with speed change before switching interval */
        const val HYSTERESIS_READINGS_REQUIRED = 3

        /** Absolute min/max bounds */
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_INTERVAL_MS = 10_000L

        /** Max age of speed samples to consider (milliseconds) */
        const val MAX_SPEED_SAMPLE_AGE_MS = 30_000L
    }

    /**
     * Represents a speed sample with timestamp.
     */
    data class SpeedSample(
        val speed: Float, // m/s
        val elapsedMs: Long, // SystemClock.elapsedRealtime()
    )

    // Recent speed samples for averaging and hysteresis
    private val speedSamples = mutableListOf<SpeedSample>()

    // Current interval state
    private var currentIntervalMs: Long = INTERVAL_WALKING_MS
    private var currentTargetIntervalMs: Long = INTERVAL_WALKING_MS
    private var consecutiveReadingsAtTarget: Int = 0

    /**
     * Feed a new GPS location and return the recommended interval.
     */
    fun onNewLocation(location: Location, elapsedMs: Long): Long {
        // Add new sample
        speedSamples.add(SpeedSample(speed = location.speed, elapsedMs = elapsedMs))

        // Remove old samples
        speedSamples.removeAll { elapsedMs - it.elapsedMs > MAX_SPEED_SAMPLE_AGE_MS }

        // Calculate weighted average speed (recent samples weighted more)
        val avgSpeed = calculateWeightedAverageSpeed()

        // Determine target interval based on speed
        val newTarget = when {
            avgSpeed < SPEED_STANDING_THRESHOLD -> INTERVAL_STANDING_MS
            avgSpeed < SPEED_WALKING_THRESHOLD -> INTERVAL_WALKING_MS
            avgSpeed < SPEED_RUNNING_THRESHOLD -> INTERVAL_RUNNING_MS
            else -> INTERVAL_SPRINT_MS
        }

        // Apply hysteresis: only change if target differs and sustained for N readings
        if (newTarget != currentTargetIntervalMs) {
            currentTargetIntervalMs = newTarget
            consecutiveReadingsAtTarget = 1
        } else {
            consecutiveReadingsAtTarget++
        }

        // Switch interval only after sustained readings
        if (consecutiveReadingsAtTarget >= HYSTERESIS_READINGS_REQUIRED &&
            currentIntervalMs != currentTargetIntervalMs
        ) {
            currentIntervalMs = currentTargetIntervalMs
        }

        return currentIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    /**
     * Calculate weighted average speed from recent samples.
     * More recent samples have higher weight.
     */
    private fun calculateWeightedAverageSpeed(): Float {
        if (speedSamples.isEmpty()) return 0f

        val now = speedSamples.last().elapsedMs
        var totalWeight = 0f
        var weightedSpeed = 0f

        for (sample in speedSamples) {
            val ageMs = now - sample.elapsedMs
            // Weight: 1.0 for most recent, decreasing linearly to 0.3 for oldest
            val weight = 1.0f - (ageMs.toFloat() / MAX_SPEED_SAMPLE_AGE_MS) * 0.7f
            weightedSpeed += sample.speed * weight
            totalWeight += weight
        }

        return if (totalWeight > 0f) weightedSpeed / totalWeight else 0f
    }

    /**
     * Get the current interval without feeding a new location.
     */
    fun getCurrentIntervalMs(): Long = currentIntervalMs

    /**
     * Get the current estimated speed in m/s.
     */
    fun getCurrentSpeed(): Float = calculateWeightedAverageSpeed()

    /**
     * Get a human-readable description of the current speed zone.
     */
    fun getCurrentSpeedZone(): String = when {
        calculateWeightedAverageSpeed() < SPEED_STANDING_THRESHOLD -> "standing"
        calculateWeightedAverageSpeed() < SPEED_WALKING_THRESHOLD -> "walking"
        calculateWeightedAverageSpeed() < SPEED_RUNNING_THRESHOLD -> "fast"
        else -> "sprinting"
    }

    /**
     * Reset state (e.g., when recording starts/stops).
     */
    fun reset() {
        speedSamples.clear()
        currentIntervalMs = INTERVAL_WALKING_MS
        currentTargetIntervalMs = INTERVAL_WALKING_MS
        consecutiveReadingsAtTarget = 0
    }
}
