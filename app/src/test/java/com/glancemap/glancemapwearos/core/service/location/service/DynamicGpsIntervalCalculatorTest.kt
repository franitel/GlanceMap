package com.glancemap.glancemapwearos.core.service.location.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicGpsIntervalCalculatorTest {

    private fun newCalculator(): DynamicGpsIntervalCalculator = DynamicGpsIntervalCalculator()

    /** Feed N successive fixes at a constant derived speed, spaced 3s apart. */
    private fun runAtSpeed(
        calc: DynamicGpsIntervalCalculator,
        speedMps: Float,
        fixes: Int,
        startElapsedMs: Long = 0L,
    ) {
        for (i in 1..fixes) {
            calc.updateState(
                gpsSpeed = speedMps,
                derivedSpeedMps = speedMps,
                elapsedMs = startElapsedMs + i * 3_000L,
            )
        }
    }

    @Test
    fun `starts at walking interval`() {
        val calc = newCalculator()
        assertEquals(DynamicGpsIntervalCalculator.INTERVAL_WALKING_MS, calc.getCurrentIntervalMs())
    }

    @Test
    fun `standing speed settles to 5s`() {
        val calc = newCalculator()
        runAtSpeed(calc, 0.2f, 30)
        assertEquals("standing speed should reach 5s interval", 5_000L, calc.getCurrentIntervalMs())
        assertEquals("standing", calc.getCurrentSpeedZone())
    }

    @Test
    fun `fast running speed settles to 2s`() {
        val calc = newCalculator()
        // Starting from walking, accelerate to fast zone.
        runAtSpeed(calc, 2.5f, 30)
        assertEquals("running speed should reach 2s interval", 2_000L, calc.getCurrentIntervalMs())
    }

    @Test
    fun `sprint speed settles to 1s`() {
        val calc = newCalculator()
        runAtSpeed(calc, 5.0f, 30)
        assertEquals("sprint speed should reach 1s interval", 1_000L, calc.getCurrentIntervalMs())
        assertEquals("sprinting", calc.getCurrentSpeedZone())
    }

    @Test
    fun `does not thrash on oscillating speed around boundary`() {
        val calc = newCalculator()
        // Oscillate between 0.9 and 1.9 (around the 0.8 standing / 1.8 fast boundaries).
        for (i in 1..60) {
            val speed = if (i % 2 == 0) 0.9f else 1.9f
            calc.updateState(gpsSpeed = speed, derivedSpeedMps = speed, elapsedMs = i * 3_000L)
        }
        val interval = calc.getCurrentIntervalMs()
        // Should NOT thrash below walking; allowed values: 3s or 5s, but stable.
        assertTrue(
            "interval $interval should be stable (3s or 5s), not oscillating to 2s/1s",
            interval == 3_000L || interval == 5_000L,
        )
    }

    @Test
    fun `responds to sustained speed change after cooldown`() {
        val calc = newCalculator()
        // Walk at 1.5 m/s → 3s
        runAtSpeed(calc, 1.5f, 10)
        assertEquals(3_000L, calc.getCurrentIntervalMs())

        // Jump to fast 2.5 m/s sustained → eventually 2s
        runAtSpeed(calc, 2.5f, 12)
        assertEquals("sustained fast speed should reach 2s", 2_000L, calc.getCurrentIntervalMs())
    }

    @Test
    fun `brief speed spike does not change interval`() {
        val calc = newCalculator()
        runAtSpeed(calc, 1.5f, 10)
        // Single burst of fast speed (running for a few seconds), then back to walking.
        for (i in 1..3) calc.updateState(gpsSpeed = 3.5f, derivedSpeedMps = 3.5f, elapsedMs = 10 * 3_000L + i * 3_000L)
        for (i in 1..10) calc.updateState(gpsSpeed = 1.5f, derivedSpeedMps = 1.5f, elapsedMs = 16 * 3_000L + i * 3_000L)
        assertEquals(
            "brief speed spike should not have locked in 1s/2s; should recover to walking 3s",
            3_000L,
            calc.getCurrentIntervalMs(),
        )
    }
}
