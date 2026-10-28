package com.arka.sumteum.car

import com.arka.sumteum.core.CarState
import com.arka.sumteum.core.Cmd

/** Cabin settings the app changed, shown on screen so the driver sees what moved. */
data class Cabin(
    val recirc: Boolean = true,
    val fan: Int = 2,
    val tempC: Float? = null,
    val seatVent: Int = 0,
    val ambientBright: Boolean = false,
    val waypoint: String? = null,
)

/** One vehicle: the real Pleos car, or the demo drive that plays the same signals. */
interface CarLink {
    val label: String
    /** Clock the engine runs on: wall time for a car, compressed time for the demo. */
    fun now(): Long
    fun hour(): Int
    fun start(onSteer: (Long, Float) -> Unit, onSignal: (Long) -> Unit, onAnswer: (Boolean) -> Unit)
    fun state(): CarState
    fun cabin(): Cabin
    fun apply(cmd: Cmd)
    fun stop()
}
