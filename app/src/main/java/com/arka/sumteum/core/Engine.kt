package com.arka.sumteum.core

import kotlin.math.roundToInt

/** What the car tells us each tick. Null fields are unknown on this vehicle. */
data class CarState(
    val occupants: Int,
    val recirc: Boolean,
    val fan: Int,
    val driving: Boolean,
    val tripDriveMin: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val heading: Double? = null,
    val outsideAirBlock: String? = null,   // tunnel, poor air quality: keep fresh air closed
    val routeAhead: List<Pair<Double, Double>>? = null,   // known route from here, when guidance is on
)

/** What we ask the car to do. Every "on" has a matching "off" that restores the driver's setting. */
sealed interface Cmd {
    data class FreshAir(val on: Boolean) : Cmd
    data class CoolCabin(val on: Boolean) : Cmd     // -1.5 C, driver seat ventilation, cool ambient light
    data class Ask(val text: String) : Cmd          // speak, then listen for yes / no
    data class Say(val text: String) : Cmd
    data class AddRestStop(val stop: RestStop, val km: Double) : Cmd
}

/**
 * Prevention layer (virtual CO2) plus a four-step intervention ladder:
 * 1 fresh air, 2 cool cabin, 3 voice check, 4 rest stop as waypoint.
 */
class Engine(private val stops: RestStops) {
    val steering = SteeringMonitor()
    var ppm = Co2Model.OUTDOOR_PPM; private set
    var risk = Risk(0, emptyList()); private set
    var step = 0; private set
    var venting = false; private set
    var nextStop: Pair<RestStop, Double>? = null; private set
    var driveMin = 0.0; private set
    var waitingAnswer = false; private set
    var question: String? = null; private set
    var askedStop: Pair<RestStop, Double>? = null; private set
    var restAdded = false; private set
    var addedStop: RestStop? = null; private set   // the waypoint we added, shown until the rest
    var car: CarState? = null; private set
    val events = ArrayDeque<String>()

    private var counted = 0.0
    private var restOffset = 0.0
    private var parkedSince: Long? = null
    private var calmSince: Long? = null
    private var ventSince = 0L
    private var lastAsk: Long? = null
    private var selfReport = false
    private var blockNoted: String? = null

    fun tick(now: Long, dtSec: Double, hour: Int, car: CarState): List<Cmd> {
        val out = mutableListOf<Cmd>()
        this.car = car
        ppm = Co2Model.step(ppm, car.occupants, car.recirc, car.fan, dtSec)
        if (car.driving) counted += dtSec / 60
        driveMin = ((car.tripDriveMin ?: counted) - restOffset).coerceAtLeast(0.0)
        // Suggest only stops at least 3 km out: closer ones leave no time to decide and change lanes.
        var askable: Pair<RestStop, Double>? = null
        if (car.lat != null && car.lon != null && car.heading != null) {
            fun find(minKm: Double) = car.routeAhead?.let { stops.nextOnRoute(it, car.heading, minKm) }
                ?: if (car.routeAhead == null) stops.nextAhead(car.lat, car.lon, car.heading, minKm) else null
            nextStop = find(0.3)
            askable = find(3.0)
        }
        // A stop of 10 minutes or more counts as a rest: the clock and the ladder start over.
        if (car.driving) parkedSince = null else if (parkedSince == null) parkedSince = now
        if (parkedSince != null && now - parkedSince!! >= 10 * 60_000 && driveMin > 0) {
            restOffset = car.tripDriveMin ?: counted
            driveMin = 0.0
            selfReport = false
            restAdded = false
            addedStop = null
            if (step >= 2) out += Cmd.CoolCabin(false)
            step = 0
            note("휴식 확인, 운전 시간을 다시 셉니다")
        }
        risk = RiskModel.score(RiskInput(
            driveMin, hour, ppm, steering.jerks(now),
            if (car.driving) steering.monotonyMin(now) else 0.0, selfReport))

        // Prevention: open fresh air at 1,500 ppm or at step 1, close at 900 ppm once risk is low.
        val wantAir = ppm >= 1500 || risk.score >= 40
        if (car.outsideAirBlock != null) {
            if (venting) { venting = false; out += Cmd.FreshAir(false) }
            if (wantAir && blockNoted != car.outsideAirBlock) note("외기 보류: ${car.outsideAirBlock}")
            blockNoted = car.outsideAirBlock
        } else {
            blockNoted = null
            if (!venting && wantAir) {
                venting = true; ventSince = now
                out += Cmd.FreshAir(true)
                note(if (ppm >= 1500) "CO₂ ${ppm.roundToInt()}ppm, 외기 환기" else "졸음 위험 ${risk.score}, 외기 환기")
            } else if (venting && ppm <= 900 && risk.score < 40 && now - ventSince >= 60_000) {
                venting = false
                out += Cmd.FreshAir(false)
                note("CO₂ ${ppm.roundToInt()}ppm, 원래 공조로")
            }
        }
        if (risk.score >= 40) step = maxOf(step, 1)

        if (risk.score >= 55 && step < 2) {
            step = 2
            out += Cmd.CoolCabin(true)
            note("온도 1.5℃ 낮춤, 운전석 통풍, 실내 조명 전환")
        }
        val stop = askable
        // No answer within a minute counts as "not now"; ask again ten minutes after the question.
        if (waitingAnswer && lastAsk?.let { now - it > 60_000 } == true) {
            waitingAnswer = false
            question = null
            note("응답 없음, 10분 뒤 다시 묻습니다")
        }
        val askedRecently = lastAsk?.let { now - it < 10 * 60_000 } == true
        if (risk.score >= 70 && !restAdded && !waitingAnswer && !askedRecently) {
            step = maxOf(step, 3)
            lastAsk = now
            if (stop != null) {
                waitingAnswer = true
                askedStop = stop
                question = "${stop.first.name}까지 ${stop.second.roundToInt()}킬로미터입니다. 경유지로 넣을까요?"
                out += Cmd.Ask(question!!)
                note("음성 확인: ${stop.first.name} 경유 제안")
            } else {
                out += Cmd.Say("졸음 위험이 높습니다. 가까운 곳에서 쉬어 가세요.")
            }
        }

        // De-escalate after five calm minutes.
        if (risk.score < 30) {
            if (calmSince == null) calmSince = now
            if (step >= 1 && now - calmSince!! >= 5 * 60_000) {
                if (step >= 2) out += Cmd.CoolCabin(false)
                step = 0
                note("위험 낮아짐, 실내 설정 복원")
            }
        } else calmSince = null
        return out
    }

    /** Driver's yes / no to the voice check. */
    fun answer(yes: Boolean): List<Cmd> {
        if (!waitingAnswer) return emptyList()   // the question already closed
        waitingAnswer = false
        question = null
        val stop = askedStop
        if (!yes || stop == null) {
            note("경유 제안 거절, 10분 뒤 다시 묻습니다")
            return emptyList()
        }
        return addRestStop(stop)
    }

    /** Gleo REPORT_DROWSINESS: skip the ladder and do everything at once. */
    fun reportDrowsy(now: Long, block: String?): List<Cmd> {
        selfReport = true
        val out = mutableListOf<Cmd>()
        if (!venting && block == null) { venting = true; ventSince = now; out += Cmd.FreshAir(true) }
        if (step < 2) out += Cmd.CoolCabin(true)
        step = maxOf(step, 2)
        note("운전자 졸음 신고")
        nextStop?.let { out += addRestStop(it) }
        return out
    }

    /** Gleo VENTILATE_CABIN. Returns null when outside air must stay closed. */
    fun ventilateNow(now: Long, block: String?): List<Cmd>? {
        if (block != null) return null
        note("음성 요청, 외기 환기")
        ventSince = now
        if (venting) return emptyList()
        venting = true
        return listOf(Cmd.FreshAir(true))
    }

    fun addRestStopNow(): List<Cmd>? = nextStop?.let { addRestStop(it) }

    /** Commands that put the driver's settings back, for when the app hands the car over. */
    fun restoreCommands(): List<Cmd> = listOfNotNull(
        Cmd.FreshAir(false).takeIf { venting }, Cmd.CoolCabin(false).takeIf { step >= 2 })

    private fun addRestStop(stop: Pair<RestStop, Double>): List<Cmd> {
        restAdded = true
        addedStop = stop.first
        waitingAnswer = false
        question = null
        note("${stop.first.name} 경유지 추가")
        return listOf(Cmd.AddRestStop(stop.first, stop.second),
            Cmd.Say("${stop.first.name}을 경유지로 넣었습니다."))
    }

    private fun note(s: String) {
        events.addFirst(s)
        while (events.size > 5) events.removeLast()
    }
}
