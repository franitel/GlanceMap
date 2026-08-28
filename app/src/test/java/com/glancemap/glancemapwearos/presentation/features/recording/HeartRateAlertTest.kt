package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.data.repository.HeartRateAlertSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateAlertTest {
    private var nowNanos = 0L

    private fun tracker(settings: HeartRateAlertSettings): HeartRateAlertTracker =
        HeartRateAlertTracker(realtimeNanosProvider = { nowNanos }).apply { start(settings) }

    private fun advanceSeconds(seconds: Int) {
        nowNanos += seconds * 1_000_000_000L
    }

    @Test
    fun highAlertFiresOnCrossingThenSuppressedByCooldownThenFiresAgain() {
        val settings = HeartRateAlertSettings(highEnabled = true, highBpm = 160, lowEnabled = false)
        val tracker = tracker(settings)

        advanceSeconds(1)
        assertEquals(HeartRateAlertKind.HIGH, tracker.onHeartRate(165))

        // Re-fire suppressed within the 15s cooldown.
        advanceSeconds(2)
        assertNull(tracker.onHeartRate(180))
        assertNull(tracker.onHeartRate(190))

        // After the cooldown elapses, it may fire again for a sustained high HR.
        advanceSeconds(13)
        assertEquals(HeartRateAlertKind.HIGH, tracker.onHeartRate(185))
    }

    @Test
    fun belowThresholdNeverFiresHighAlert() {
        val settings = HeartRateAlertSettings(highEnabled = true, highBpm = 160, lowEnabled = false)
        val tracker = tracker(settings)

        advanceSeconds(1)
        assertNull(tracker.onHeartRate(140))
        assertNull(tracker.onHeartRate(80))
    }

    @Test
    fun lowAlertFiresOnCrossingBelowThreshold() {
        val settings = HeartRateAlertSettings(highEnabled = false, lowEnabled = true, lowBpm = 60)
        val tracker = tracker(settings)

        advanceSeconds(1)
        assertEquals(HeartRateAlertKind.LOW, tracker.onHeartRate(55))

        // Suppressed within cooldown.
        advanceSeconds(3)
        assertNull(tracker.onHeartRate(50))
    }

    @Test
    fun disabledThresholdNeverFires() {
        val settings = HeartRateAlertSettings(highEnabled = false, lowEnabled = false, highBpm = 160, lowBpm = 60)
        val tracker = tracker(settings)

        advanceSeconds(1)
        assertNull(tracker.onHeartRate(200))
        assertNull(tracker.onHeartRate(40))
    }

    @Test
    fun invalidHeartRateNeverFires() {
        val settings = HeartRateAlertSettings(highEnabled = true, highBpm = 160, lowEnabled = true, lowBpm = 60)
        val tracker = tracker(settings)

        assertNull(tracker.onHeartRate(null))
        assertNull(tracker.onHeartRate(0))
        assertNull(tracker.onHeartRate(-5))
    }

    @Test
    fun startResetsCooldownAndThresholdState() {
        val settings = HeartRateAlertSettings(highEnabled = true, highBpm = 160, lowEnabled = false)
        val tracker = tracker(settings)

        advanceSeconds(1)
        assertEquals(HeartRateAlertKind.HIGH, tracker.onHeartRate(165))
        // Within cooldown: suppressed.
        advanceSeconds(2)
        assertNull(tracker.onHeartRate(170))

        // A fresh recording resets the tracker, so the same HR fires immediately again.
        tracker.start(settings)
        assertEquals(HeartRateAlertKind.HIGH, tracker.onHeartRate(165))
    }
}
