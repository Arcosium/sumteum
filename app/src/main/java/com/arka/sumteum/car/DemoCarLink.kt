package com.arka.sumteum.car

import android.os.SystemClock
import com.arka.sumteum.core.CarState
import com.arka.sumteum.core.Cmd
import com.arka.sumteum.core.RestStops
import kotlin.math.sin

/**
 * Demo drive for screens without a Pleos car: two people on the Gyeongbu Expressway at night,
 * Seoul to Daejeon, recirculation on. Only the vehicle signals are scripted (what the Virtual
 * Console would feed); every decision comes from the same Engine the car uses.
 * Time runs [speed] times faster than the wall clock.
 */
class DemoCarLink(routeCsv: String, private val speed: Double = 30.0) : CarLink {
    override val label = "시연 주행 (가상 차량 신호)"

    // Yangjae IC to Daejeon IC on the Gyeongbu Expressway, OSRM geometry thinned to 400 m (assets/demo_route.csv).
    private val route = Route(routeCsv.lineSequence().drop(1).filter { it.isNotBlank() }
        .map { it.split(',').let { c -> c[0].toDouble() to c[1].toDouble() } }.toList())
    private var lastWall = SystemClock.elapsedRealtime()
    private var simMs = 0.0
    private val clockStart = 1L * 3600_000 + 50 * 60_000   // 01:50
    private var lastSimSec = 0L
    private var cab = Cabin(recirc = true, fan = 2, tempC = 23f)
    private var beforeAir: Cabin? = null
    private var askedAt: Long? = null
    private var stopTarget: Pair<Double, Double>? = null
    private var arrivedAt: Double? = null
    private var closest = Double.MAX_VALUE
    private lateinit var steer: (Long, Float) -> Unit
    private lateinit var signal: (Long) -> Unit
    private lateinit var answer: (Boolean) -> Unit

    /** Simulated minutes since the demo started. */
    fun minutes() = now() / 60_000.0

    // Time runs at [speed]x, and at 1x while a question waits so the car does not pass the stop.
    override fun now(): Long {
        val wall = SystemClock.elapsedRealtime()
        simMs += (wall - lastWall) * if (askedAt != null) 1.0 else speed
        lastWall = wall
        return simMs.toLong()
    }
    override fun hour() = (((clockStart + now()) / 3600_000) % 24).toInt()
    fun clock(): String {
        val m = (clockStart + now()) / 60_000
        return "%02d:%02d".format((m / 60) % 24, m % 60)
    }

    override fun start(onSteer: (Long, Float) -> Unit, onSignal: (Long) -> Unit, onAnswer: (Boolean) -> Unit) {
        steer = onSteer; signal = onSignal; answer = onAnswer
    }

    override fun state(): CarState {
        val min = minutes()
        // Steering: one sample per simulated second, small lane-keeping noise plus scripted jerks.
        val nowSec = (min * 60).toLong()
        for (s in lastSimSec + 1..nowSec) {
            val m = s / 60.0
            val jerk = JERKS.any { s.toDouble() / 60 in it..it + 1.0 / 60 }
            steer(s * 1000, if (jerk) 18f else (1.5 * sin(s / 7.0)).toFloat())
            if (SIGNALS.any { m >= it && m - it < 1.0 / 60 }) signal(s * 1000)
        }
        lastSimSec = nowSec
        // The driver answers "응" five seconds after the question.
        askedAt?.let { if (SystemClock.elapsedRealtime() - it > 5_000) { askedAt = null; answer(true) } }

        val km = if (arrivedAt != null) arrivedAt!! else min * KMH / 60
        val (lat, lon, heading) = route.at(km)
        stopTarget?.let { (sLat, sLon) ->
            // Arrive when close, or when the stop starts falling behind (its map point sits off the road).
            val d = RestStops.km(lat, lon, sLat, sLon)
            if (arrivedAt == null && (d < 0.5 || (closest < 1.5 && d > closest + 0.2))) {
                arrivedAt = km
                cab = cab.copy(waypoint = null)
            }
            closest = minOf(closest, d)
        }
        return CarState(
            occupants = if (arrivedAt != null) 0 else 2,   // both step out at the rest stop
            recirc = cab.recirc, fan = cab.fan,
            driving = arrivedAt == null,
            tripDriveMin = 70 + if (arrivedAt != null) arrivedAt!! / KMH * 60 else min,
            lat = lat, lon = lon, heading = heading,
            outsideAirBlock = if (min in 12.0..14.0) "터널" else null,
            routeAhead = route.ahead(km, 40.0),
        )
    }

    override fun cabin() = cab

    override fun apply(cmd: Cmd) {
        when (cmd) {
            is Cmd.FreshAir -> if (cmd.on) {
                beforeAir = cab
                cab = cab.copy(recirc = false, fan = maxOf(cab.fan, 4))
            } else beforeAir?.let { cab = cab.copy(recirc = it.recirc, fan = it.fan); beforeAir = null }
            is Cmd.CoolCabin -> cab = if (cmd.on) cab.copy(tempC = 21.5f, seatVent = 2, ambientBright = true)
                else cab.copy(tempC = 23f, seatVent = 0, ambientBright = false)
            is Cmd.Ask -> askedAt = SystemClock.elapsedRealtime()
            is Cmd.Say -> {}
            is Cmd.AddRestStop -> {
                cab = cab.copy(waypoint = cmd.stop.name)
                stopTarget = cmd.stop.lat to cmd.stop.lon
            }
        }
    }

    override fun stop() {}

    private class Route(private val pts: List<Pair<Double, Double>>) {
        private val seg = pts.zipWithNext { a, b -> RestStops.km(a.first, a.second, b.first, b.second) }

        fun at(km: Double): Triple<Double, Double, Double> {
            var left = km
            for (i in seg.indices) {
                val (a, b) = pts[i] to pts[i + 1]
                if (left <= seg[i] || i == seg.lastIndex) {
                    val f = (left / seg[i]).coerceIn(0.0, 1.0)
                    return Triple(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f,
                        RestStops.bearing(a.first, a.second, b.first, b.second))
                }
                left -= seg[i]
            }
            error("empty route")
        }

        /** The car's position followed by route points up to [spanKm] ahead. */
        fun ahead(km: Double, spanKm: Double): List<Pair<Double, Double>> {
            val here = at(km).let { it.first to it.second }
            var passed = 0.0
            val out = mutableListOf(here)
            for (i in seg.indices) {
                passed += seg[i]
                if (passed > km + spanKm) break
                if (passed > km) out += pts[i + 1]
            }
            return out
        }
    }

    companion object {
        private const val KMH = 100.0
        private val SIGNALS = listOf(3.0, 9.0)                      // lane changes, then none
        private val JERKS = listOf(44.0, 47.0, 50.0)                // drift and snap back
    }
}
