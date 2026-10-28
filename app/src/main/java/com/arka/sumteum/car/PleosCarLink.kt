package com.arka.sumteum.car

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.util.Log
import com.arka.sumteum.core.CarState
import com.arka.sumteum.core.Cmd
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEvents

/** The real car through Pleos Vehicle, NaviHelper, TextToSpeech and SpeechToText SDKs. */
class PleosCarLink(ctx: Context) : CarLink {
    override val label = "Pleos 차량"

    private val vehicle = Vehicle(ctx)
    private val navi = NaviHelper(ctx)
    private val tts = TextToSpeech(ctx, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(ctx, SttMode.ON_DEVICE)
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val seats = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT,
        SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    private val occupied = ConcurrentHashMap<SeatValue, Boolean>()
    private val parkedGears = setOf(VehicleGear.GEAR_PARK, VehicleGear.GEAR_NEUTRAL,
        VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_UNKNOWN)

    @Volatile private var cab = Cabin()
    @Volatile private var driving = false
    @Volatile private var loc: CurrentLocationInfo? = null
    @Volatile private var listening = false
    @Volatile private var tunnelUntil = 0L
    private var ambientBefore: LightSwitch? = null
    private var beforeAir: Cabin? = null
    private var beforeCool: Cabin? = null
    private var lastLocPoll = 0L
    private var answer: (Boolean) -> Unit = {}
    private val fail: (Exception) -> Unit = { Log.w(TAG, "vehicle call failed", it) }

    override fun now() = System.currentTimeMillis()
    override fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    override fun start(onSteer: (Long, Float) -> Unit, onSignal: (Long) -> Unit, onAnswer: (Boolean) -> Unit) {
        answer = onAnswer
        vehicle.initialize()
        navi.initialize()
        tts.initialize()
        stt.initialize()

        val seat = vehicle.getSeat()
        for (s in seats) {
            val area = SeatArea(s)
            seat.getCurrentSeatOccupancy(area, { occupied[s] = it == true }, fail)
            seat.registerSeatOccupancy(area, object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(p0: Boolean?) { occupied[s] = p0 == true }
                override fun onFailed(p0: Exception) = fail(p0)
            })
        }
        val hvac = vehicle.getHVAC()
        hvac.getHvacRecirculationState(driver, { v -> v?.let { cab = cab.copy(recirc = it) } }, fail)
        hvac.registerHvacRecirculationState(driver, object : HvacRecirculationStateListener {
            override fun onHvacAutoRecirculationStateUpdated(p0: Boolean?) { p0?.let { cab = cab.copy(recirc = it) } }
            override fun onFailed(p0: Exception) = fail(p0)
        })
        hvac.getHvacFanSpeed(driver, { v -> v?.let { cab = cab.copy(fan = it) } }, fail)
        hvac.registerHvacFanSpeed(driver, object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(p0: Int?) { p0?.let { cab = cab.copy(fan = it) } }
            override fun onFailed(p0: Exception) = fail(p0)
        })
        hvac.getHvacTemperature(driver, TemperatureUnit.CELSIUS, { cab = cab.copy(tempC = it) }, fail)
        seat.getHvacSeatVentilationState(driver, { v -> v?.let { cab = cab.copy(seatVent = it) } }, fail)

        vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(p0: Float?) { p0?.let { onSteer(now(), it) } }
            override fun onFailed(p0: Exception) = fail(p0)
        })
        vehicle.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(p0: TurnSignalValue?) { if (p0 != null && p0 != TurnSignalValue.OFF) onSignal(now()) }
            override fun onFailed(p0: Exception) = fail(p0)
        })
        val gear = vehicle.getGear()
        gear.getCurrentGearSelectionState({ driving = it != null && it !in parkedGears }, fail)
        gear.registerGearSelection(object : GearSelectionListener {
            override fun onSelected(p0: VehicleGear?) { driving = p0 != null && p0 !in parkedGears }
            override fun onFailed(p0: Exception) = fail(p0)
        })

        navi.addListener(object : NaviHelperEventListener {
            override fun onCurrentLocationInfo(p0: CurrentLocationInfo) { loc = p0 }
            // Guidance lists a tunnel entrance as a TBT item: keep outside air closed from 500 m before it.
            // ponytail: entrance only, held 3 min (about 5 km at 100 km/h); longer tunnels need the exit point.
            override fun onTBTInfo(p0: List<TBTInfo>) {
                if (p0.any { it.type == TurnType.TURN_TUNNEL && it.distance <= 500 }) tunnelUntil = now() + 180_000
            }
        })
        tts.addEventListener(object : TtsEvents {
            override fun onDone() { if (listening) stt.request() }
            override fun onStart() {}
            override fun onReady() {}
            override fun onStop() {}
            override fun onError(p0: TtsError) { Log.w(TAG, "tts $p0") }
            override fun onUpdatedRms(p0: Double) {}
        })
        stt.addListener(object : ResultListener {
            override fun onUpdated(p0: String, p1: Boolean) {
                if (!p1 || !listening) return
                yesNo(p0)?.let { listening = false; answer(it) }
            }
            override fun onUpdatedEpdData(p0: Long, p1: Long) {}
            override fun onStartedRecognition() {}
            override fun onEndedRecognition() {}
            override fun onError(p0: SttError) { Log.w(TAG, "stt $p0") }
            override fun onReady() {}
            override fun onUpdatedRms(p0: Double) {}
        })
    }

    override fun state(): CarState {
        if (now() - lastLocPoll > 5_000) { lastLocPoll = now(); navi.getCurrentLocationInfo(); navi.getTBTInfo() }
        val l = loc
        return CarState(
            occupants = occupied.count { it.value }.coerceAtLeast(1),
            recirc = cab.recirc, fan = cab.fan, driving = driving,
            lat = l?.latitude, lon = l?.longitude, heading = l?.heading,
            outsideAirBlock = if (now() < tunnelUntil) "터널" else null,
        )
    }

    override fun cabin() = cab

    override fun apply(cmd: Cmd) {
        val hvac = vehicle.getHVAC()
        when (cmd) {
            is Cmd.FreshAir -> if (cmd.on) {
                beforeAir = cab
                hvac.setHvacRecirculation(driver, false, { _, _ -> }, fail)
                hvac.setHvacFanSpeed(driver, maxOf(cab.fan, 4), { _, _ -> }, fail)
            } else beforeAir?.let {
                hvac.setHvacRecirculation(driver, it.recirc, { _, _ -> }, fail)
                hvac.setHvacFanSpeed(driver, it.fan, { _, _ -> }, fail)
                beforeAir = null
            }
            is Cmd.CoolCabin -> if (cmd.on) {
                beforeCool = cab
                cab.tempC?.let { t -> hvac.setHvacTemperature(driver, t - 1.5f, TemperatureUnit.CELSIUS, { _, v -> cab = cab.copy(tempC = v) }, fail) }
                vehicle.getSeat().setHvacSeatVentilation(driver, 2, { _, _ -> }, fail)
                vehicle.getLight().getAmbientLightSwitch(driver, { ambientBefore = it }, fail)
                vehicle.getLight().setAmbientLightSwitch(driver, LightSwitch.ON, { _, _ -> }, fail)
                cab = cab.copy(seatVent = 2, ambientBright = true)
            } else beforeCool?.let { b ->
                b.tempC?.let { t -> hvac.setHvacTemperature(driver, t, TemperatureUnit.CELSIUS, { _, v -> cab = cab.copy(tempC = v) }, fail) }
                vehicle.getSeat().setHvacSeatVentilation(driver, b.seatVent, { _, _ -> }, fail)
                vehicle.getLight().setAmbientLightSwitch(driver, ambientBefore ?: LightSwitch.OFF, { _, _ -> }, fail)
                cab = cab.copy(seatVent = b.seatVent, ambientBright = false)
                beforeCool = null
            }
            is Cmd.Ask -> { listening = true; tts.speak(cmd.text, true) }
            is Cmd.Say -> tts.speak(cmd.text, true)
            is Cmd.AddRestStop -> {
                navi.addWaypoint(RequestWaypointInfo(cmd.stop.lat, cmd.stop.lon, cmd.stop.name,
                    WaypointIndex.FIRST, "", "", "", ""))
                cab = cab.copy(waypoint = cmd.stop.name)
            }
        }
    }

    override fun stop() {
        runCatching { vehicle.release(); navi.release(); tts.release(); stt.release() }
    }

    companion object {
        private const val TAG = "PleosCarLink"

        /** "응", "네", "그래" -> yes; "아니", "괜찮아" -> no; anything else -> ask again later. */
        fun yesNo(text: String): Boolean? = when {
            Regex("아니|괜찮|됐어|싫어|그만|안 ?해|안 ?넣|넣지 ?마").containsMatchIn(text) -> false
            Regex("응|네|예|그래|좋아|넣어|가자").containsMatchIn(text) -> true
            else -> null
        }
    }
}
