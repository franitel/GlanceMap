package com.glancemap.glancemapwearos.core.service.location.service

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dynamic GPS interval calculator that adjusts the GPS update interval based on speed.
 *
 * Speed zones and intervals:
 *   < 0.8 m/s (standing/slow walk)  → 5s  (battery savings)
 *   0.8-1.8   (walking normal)      → 3s  (standard hiking)
 *   1.8-3.5   (fast walk/jogging)   → 2s  (more detail on curves)
 *   > 3.5     (running/cycling)     → 1s  (maximum precision)
 *
 * Design goals (fixed after field testing a 9.4 km run):
 *   - Highly stable EMA-filtered speed signal (not raw GPS speed, which has heavy jitter on watch).
 *   - Speed derived from distance/time between fixes as an additional, less-jittery signal.
 *   - Hysteresis deadzones per boundary so the target does not oscilate around a threshold.
 *   - Requires N consecutive readings and a minimum cooldown before switching interval.
 *   - Never below 1s, never above 10s.
 */
class DynamicGpsIntervalCalculator {

    companion object {
        /** Speed thresholds (m/s) for moving UP a zone (use upper deadzone edge going up). */
        const val SPEED_ENTER_WALKING_MPS = 0.8f   // standing -> walking
        const val SPEED_ENTER_FAST_MPS = 1.8f      // walking -> fast
        const val SPEED_ENTER_SPRINT_MPS = 3.5f    // fast -> sprint

        /** GPS intervals in milliseconds */
        const val INTERVAL_STANDING_MS = 5_000L
        const val INTERVAL_WALKING_MS = 3_000L
        const val INTERVAL_RUNNING_MS = 2_000L
        const val INTERVAL_SPRINT_MS = 1_000L

        /** Number of consecutive readings at a new zone before switching interval. */
        const val HYSTERESIS_READINGS_REQUIRED = 5

        /** Minimum time between interval switches to avoid thrashing (milliseconds). */
        const val MIN_SWITCH_COOLDOWN_MS = 15_000L

        /** EMA smoothing factor applied per fix (0..1, smaller = smoother/slower). */
        const val SPEED_EMA_ALPHA = 0.25f

        /** Absolute min/max bounds */
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_INTERVAL_MS = 10_000L

        /** A fix must be this far from the previous fix to contribute a derived-speed sample. */
        const val MIN_DERIVED_SPEED_DISTANCE_M = 3.0
    }

    // Smoothed speed signal (m/s). Starts at walking zone pace.
    private var filteredSpeedMps = 1.2f

    // Previous fix geometry for deriving speed from distance/time.
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastElapsedMs: Long = 0L

    // Current interval state
    private var currentIntervalMs: Long = INTERVAL_WALKING_MS

    // Zone selection hysteresis state (deadzone per boundary)
    private var currentZone = SpeedZone.WALKING
    private var consecutiveReadingsAtNewZone: Int = 0
    private var lastSwitchElapsedMs: Long = 0L

    /** Speed zone enum used to apply deadzone thresholds. */
    private enum class SpeedZone {
        STANDING,
        WALKING,
        FAST,
        SPRINT,
    }

    /**
     * Feed a new GPS location and update the internal state. Returns the recommended interval.
     */
    fun onNewLocation(location: Location, elapsedMs: Long): Long {
        val gpsSpeed = location.speed.takeIf { it.isFinite() && it > 0f } ?: 0f
        val derivedSpeed = derivedSpeedMps(location, elapsedMs)
        return updateState(gpsSpeed = gpsSpeed, derivedSpeedMps = derivedSpeed, elapsedMs = elapsedMs)
    }

    /**
     * Pure decision entry point: feed an instantaneous GPS speed, an optional distance-derived
     * speed (m/s), and the current elapsed time. Returns the recommended interval.
     *
     * Exposed for testing; [onNewLocation] adapts [android.location.Location] to this.
     */
    fun updateState(
        gpsSpeed: Float,
        derivedSpeedMps: Float,
        elapsedMs: Long,
    ): Long {
        // Use the stronger signal so running is never underestimated.
        val rawSpeed = maxOf(gpsSpeed, derivedSpeedMps)

        // EMA filter for a stable decision signal.
        filteredSpeedMps = filteredSpeedMps + SPEED_EMA_ALPHA * (rawSpeed - filteredSpeedMps)

        // Update zone with deadzone hysteresis.
        updateZone(elapsedMs)

        return currentIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    /**
     * Moves between zones only when the filtered speed clearly crosses a boundary, and only
     * after [HYSTERESIS_READINGS_REQUIRED] consecutive readings with a cooldown between switches.
     */
    private fun updateZone(nowElapsedMs: Long) {
        val intendedZone = intendedZoneForSpeed(filteredSpeedMps)
        if (intendedZone == currentZone) {
            consecutiveReadingsAtNewZone = 0
            return
        }

        consecutiveReadingsAtNewZone++
        val cooldownElapsed = nowElapsedMs - lastSwitchElapsedMs >= MIN_SWITCH_COOLDOWN_MS
        if (consecutiveReadingsAtNewZone >= HYSTERESIS_READINGS_REQUIRED && cooldownElapsed) {
            currentZone = intendedZone
            currentIntervalMs = intervalForZone(currentZone)
            consecutiveReadingsAtNewZone = 0
            lastSwitchElapsedMs = nowElapsedMs
        }
    }

    private fun intendedZoneForSpeed(speed: Float): SpeedZone = when {
        speed < SPEED_ENTER_WALKING_MPS -> SpeedZone.STANDING
        speed < SPEED_ENTER_FAST_MPS -> SpeedZone.WALKING
        speed < SPEED_ENTER_SPRINT_MPS -> SpeedZone.FAST
        else -> SpeedZone.SPRINT
    }

    private fun intervalForZone(zone: SpeedZone): Long = when (zone) {
        SpeedZone.STANDING -> INTERVAL_STANDING_MS
        SpeedZone.WALKING -> INTERVAL_WALKING_MS
        SpeedZone.FAST -> INTERVAL_RUNNING_MS
        SpeedZone.SPRINT -> INTERVAL_SPRINT_MS
    }

    /**
     * Derives speed (m/s) from the haversine distance between the last fix and this one,
     * divided by the time between them. Returns 0 when not enough movement/time has elapsed.
     */
    private fun derivedSpeedMps(location: Location, nowElapsedMs: Long): Float {
        val prevLat = lastLat
        val prevLon = lastLon
        val prevTime = lastElapsedMs

        lastLat = location.latitude
        lastLon = location.longitude
        lastElapsedMs = nowElapsedMs

        if (prevLat == null || prevLon == null) return 0f
        val dtSeconds = (nowElapsedMs - prevTime).coerceAtLeast(1L) / 1000.0
        val distMeters =
            haversineMeters(prevLat, prevLon, location.latitude, location.longitude)
        if (distMeters < MIN_DERIVED_SPEED_DISTANCE_M) return 0f
        return (distMeters / dtSeconds).toFloat()
    }

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Get the current interval without feeding a new location.
     */
    fun getCurrentIntervalMs(): Long = currentIntervalMs

    /**
     * Get the current estimated smoothed speed in m/s.
     */
    fun getCurrentSpeed(): Float = filteredSpeedMps

    /**
     * Get a human-readable description of the current speed zone.
     */
    fun getCurrentSpeedZone(): String = when (currentZone) {
        SpeedZone.STANDING -> "standing"
        SpeedZone.WALKING -> "walking"
        SpeedZone.FAST -> "fast"
        SpeedZone.SPRINT -> "sprinting"
    }

    /**
     * Reset state (e.g., when recording starts/stops).
     */
    fun reset() {
        filteredSpeedMps = 1.2f
        lastLat = null
        lastLon = null
        lastElapsedMs = 0L
        currentIntervalMs = INTERVAL_WALKING_MS
        currentZone = SpeedZone.WALKING
        consecutiveReadingsAtNewZone = 0
        lastSwitchElapsedMs = 0L
    }
}
