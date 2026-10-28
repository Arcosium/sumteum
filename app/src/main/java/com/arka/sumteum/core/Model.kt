package com.arka.sumteum.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Cabin CO2 from a single-zone mass balance. No sensor is needed: occupants come from seat
 * occupancy, the air exchange rate from the recirculation flag and fan step.
 *
 * Calibration: two occupants in recirculation, starting at 600 ppm, reach about 5,300 ppm after
 * 30 minutes, which matches the Korea Expressway Corporation road test (600 -> over 5,000 ppm).
 */
object Co2Model {
    const val CABIN_M3 = 3.0
    const val GEN_LPS = 0.0052          // exhaled CO2 per seated adult, L/s
    const val OUTDOOR_PPM = 600.0       // roadside air
    const val RECIRC_ACH = 1.2          // air changes per hour with recirculation on (leakage only)

    // ponytail: fresh-air ACH is a linear guess per fan step; calibrate per model with a CO2 logger.
    fun ach(recirc: Boolean, fan: Int): Double =
        if (recirc) RECIRC_ACH else 20.0 + 10.0 * fan.coerceAtLeast(1)

    private fun genPpmPerSec(occupants: Int) = GEN_LPS / 1000.0 * occupants / CABIN_M3 * 1e6

    /** Exact solution of dC/dt = G - k(C - C_out) over [dtSec]. */
    fun step(ppm: Double, occupants: Int, recirc: Boolean, fan: Int, dtSec: Double): Double {
        val k = ach(recirc, fan) / 3600.0
        val steady = OUTDOOR_PPM + genPpmPerSec(occupants) / k
        return steady - (steady - ppm) * exp(-k * dtSec)
    }

    /** Seconds until [target] is reached from [ppm] under constant conditions, or null if never. */
    fun secondsTo(target: Double, ppm: Double, occupants: Int, recirc: Boolean, fan: Int): Double? {
        if (ppm >= target) return 0.0
        val k = ach(recirc, fan) / 3600.0
        val steady = OUTDOOR_PPM + genPpmPerSec(occupants) / k
        if (steady <= target) return null
        return ln((steady - ppm) / (steady - target)) / k
    }
}

data class RiskInput(
    val driveMin: Double,      // continuous driving time
    val hour: Int,             // local clock hour
    val co2: Double,
    val jerks: Int,            // abrupt steering corrections without a turn signal, last 5 min
    val monotonyMin: Double,   // minutes since the last turn signal
    val selfReport: Boolean,   // driver said "I'm sleepy"
)

data class Risk(val score: Int, val parts: List<Pair<String, Int>>) {
    val level: Int get() = when {
        score >= 80 -> 3
        score >= 60 -> 2
        score >= 40 -> 1
        else -> 0
    }
}

/**
 * Drowsiness risk 0..100 from factors the car already knows. Weights follow the order of evidence:
 * time on task and circadian low first, cabin CO2 and steering as supporting signals.
 * It estimates risk factors; it does not claim to detect sleep.
 */
object RiskModel {
    fun score(i: RiskInput): Risk {
        val parts = listOf(
            "운전 시간" to ((i.driveMin - 60) / 60 * 25).coerceIn(0.0, 25.0).toInt(),
            "시간대" to when (i.hour) { in 2..5 -> 20; in 14..15 -> 10; else -> 0 },
            "실내 CO₂" to ((i.co2 - 1000) / 1500 * 20).coerceIn(0.0, 20.0).toInt(),
            "급조향" to min(i.jerks * 8, 20),
            "단조 주행" to ((i.monotonyMin - 20) / 40 * 15).coerceIn(0.0, 15.0).toInt(),
            "본인 신고" to if (i.selfReport) 40 else 0,
        )
        return Risk(min(100, parts.sumOf { it.second }), parts.filter { it.second > 0 })
    }
}
