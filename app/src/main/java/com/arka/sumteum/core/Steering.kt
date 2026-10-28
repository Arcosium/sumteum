package com.arka.sumteum.core

/**
 * Counts abrupt steering corrections made without a turn signal: a swing of [swingDeg] or more
 * inside [spanMs]. A drowsy driver drifts, then jerks back; a lane change comes with a signal.
 * Also tracks how long the driver has gone without signalling (monotonous cruising).
 */
class SteeringMonitor(
    private val swingDeg: Float = 15f,
    private val spanMs: Long = 1_500,
    private val windowMs: Long = 5 * 60_000,
    private val signalGraceMs: Long = 5_000,
) {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private val jerks = ArrayDeque<Long>()
    private var lastSignal: Long? = null
    private var started: Long? = null

    fun onSignal(t: Long) { lastSignal = t }

    fun onAngle(t: Long, deg: Float) {
        if (started == null) started = t
        samples.addLast(t to deg)
        while (samples.first().first < t - spanMs) samples.removeFirst()
        val swing = samples.maxOf { it.second } - samples.minOf { it.second }
        val signalled = lastSignal?.let { t - it <= signalGraceMs } == true
        if (swing >= swingDeg && !signalled) {
            jerks.addLast(t)
            samples.clear()   // one correction counts once
        }
    }

    fun jerks(now: Long): Int {
        while (jerks.isNotEmpty() && jerks.first() < now - windowMs) jerks.removeFirst()
        return jerks.size
    }

    fun monotonyMin(now: Long): Double {
        val since = lastSignal ?: started ?: now.also { started = it }
        return (now - since) / 60_000.0
    }
}
