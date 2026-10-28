package com.arka.sumteum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun ppmAfter(min: Int, n: Int, recirc: Boolean, fan: Int, start: Double = 600.0): Double {
        var p = start
        repeat(min * 60) { p = Co2Model.step(p, n, recirc, fan, 1.0) }
        return p
    }

    @Test fun co2MatchesRoadTest() {
        // Korea Expressway Corp.: two occupants, recirculation, 600 -> over 5,000 ppm in 30 minutes.
        val p = ppmAfter(30, 2, true, 2)
        assertTrue("got $p", p in 5000.0..5500.0)
    }

    @Test fun minutesTo2000ByOccupants() {
        fun min(n: Int) = Co2Model.secondsTo(2000.0, 600.0, n, true, 2)!! / 60
        assertEquals(7.2, min(2), 0.1)
        assertEquals(3.5, min(4), 0.1)
        assertEquals(15.7, min(1), 0.1)
        assertNull(Co2Model.secondsTo(2000.0, 600.0, 1, false, 4))   // fresh air never gets there
    }

    @Test fun freshAirClearsWithinTwoMinutes() {
        assertTrue(ppmAfter(2, 2, false, 4, start = 1500.0) < 950)
    }

    @Test fun riskStacksFactors() {
        val calm = RiskModel.score(RiskInput(30.0, 10, 800.0, 0, 5.0, false))
        assertEquals(0, calm.score)
        val night = RiskModel.score(RiskInput(120.0, 3, 2500.0, 2, 60.0, false))
        assertTrue("got ${night.score}", night.score >= 80)
    }

    @Test fun steeringJerkOnlyWithoutSignal() {
        val m = SteeringMonitor()
        m.onAngle(0, 0f); m.onAngle(500, 4f); m.onAngle(1000, 20f)
        assertEquals(1, m.jerks(1000))
        m.onSignal(10_000)
        m.onAngle(11_000, 0f); m.onAngle(11_800, 25f)   // lane change with signal
        assertEquals(1, m.jerks(12_000))
        assertEquals(0, m.jerks(1000 + 5 * 60_000 + 1))
    }

    @Test fun restStopMustBeAhead() {
        val stops = RestStops.parse("name,kind,lat,lon\n북쪽쉼터,졸음쉼터,37.10,127.0\n남쪽쉼터,졸음쉼터,36.90,127.0")
        assertEquals("남쪽쉼터", stops.nextAhead(37.0, 127.0, 180.0)!!.first.name)
        assertEquals("북쪽쉼터", stops.nextAhead(37.0, 127.0, 0.0)!!.first.name)
        // Southbound: the west side of the road is on the right.
        val twins = RestStops.parse("name,kind,lat,lon\n죽전 휴게소 (서울 방향),휴게소,36.90,127.001\n죽전휴게소 (부산방향),휴게소,36.90,126.999")
        assertEquals("죽전휴게소 (부산방향)", twins.nextAhead(37.0, 127.0, 180.0)!!.first.name)
        assertTrue(RestStops.servesHeading("안성휴게소(부산방면)", 37.0, 127.1, 160.0))
        assertTrue(!RestStops.servesHeading("청주휴게소 서울방향", 36.7, 127.3, 160.0))
        assertTrue(RestStops.servesHeading("망향 휴게소 (부상 방향)", 36.8, 127.2, 160.0))   // typo: unknown city passes
        // On a known route a stop beside another road is skipped even if it lies straight ahead.
        val route = (0..20).map { 37.0 - it * 0.004 to 127.0 }   // 440 m spacing, like the bundled route
        val near = RestStops.parse("name,kind,lat,lon\n옆길쉼터,졸음쉼터,36.99,127.03\n길가쉼터,졸음쉼터,36.97,127.002")
        assertEquals("길가쉼터", near.nextOnRoute(route, 180.0)!!.first.name)
    }

    @Test fun engineVentsAt1500AndRespectsTunnel() {
        val stops = RestStops.parse("name,kind,lat,lon\n")
        val car = CarState(occupants = 2, recirc = true, fan = 2, driving = true)
        val e = Engine(stops)
        var opened = -1
        for (s in 1..900) {
            if (e.tick(s * 1000L, 1.0, 10, car).contains(Cmd.FreshAir(true))) { opened = s; break }
        }
        assertTrue("opened at $opened s", opened in 200..320)   // 1,500 ppm after ~4.3 min with two people

        val t = Engine(stops)
        val tunnel = car.copy(outsideAirBlock = "터널")
        val cmds = (1..900).flatMap { t.tick(it * 1000L, 1.0, 10, tunnel) }
        assertTrue(cmds.none { it == Cmd.FreshAir(true) })
    }

    @Test fun ladderAsksThenAddsWaypoint() {
        val stops = RestStops.parse("name,kind,lat,lon\n천안졸음쉼터,졸음쉼터,36.80,127.15")
        val e = Engine(stops)
        val car = CarState(2, recirc = false, fan = 3, driving = true, tripDriveMin = 130.0,
            lat = 37.0, lon = 127.15, heading = 180.0)
        // Night, long drive, steering jerks: risk climbs past 70.
        val t = 60 * 60_000L
        e.steering.onAngle(0, 0f)   // no turn signal for an hour
        listOf(40_000L, 20_000L, 5_000L).forEach { e.steering.onAngle(t - it, 0f); e.steering.onAngle(t - it + 300, 20f) }
        val cmds = e.tick(t, 1.0, 3, car)
        assertTrue(cmds.any { it is Cmd.CoolCabin && it.on })
        assertTrue(cmds.any { it is Cmd.Ask })
        // The car moves on before the driver answers: the stop we asked about is the one added.
        e.tick(t + 1000, 1.0, 3, car.copy(lat = 36.95))
        assertEquals("천안졸음쉼터", (e.answer(true).first { it is Cmd.AddRestStop } as Cmd.AddRestStop).stop.name)
        assertTrue(e.restAdded)
        assertEquals("천안졸음쉼터", e.addedStop!!.name)
        assertTrue(e.answer(true).isEmpty())   // a closed question ignores late answers
    }
}
