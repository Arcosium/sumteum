package com.arka.sumteum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.arka.sumteum.car.CarLink
import com.arka.sumteum.car.DemoCarLink
import com.arka.sumteum.car.PleosCarLink
import com.arka.sumteum.core.Cmd
import com.arka.sumteum.core.Engine
import com.arka.sumteum.core.RestStops
import org.json.JSONArray
import org.json.JSONObject

/** One engine and one car per process. Everything runs on the main thread. */
object Hub {
    private val main = Handler(Looper.getMainLooper())
    var engine: Engine? = null; private set
    var link: CarLink? = null; private set
    private var last = 0L
    private var stops: RestStops? = null

    fun hasPleosCar(ctx: Context) = ctx.packageManager
        .queryIntentServices(Intent("ai.pleos.playground.vehicle.VEHICLE_START"), 0).isNotEmpty()

    fun start(ctx: Context, demo: Boolean) {
        link?.let { old -> engine?.restoreCommands()?.forEach(old::apply); old.stop() }
        val rest = stops ?: RestStops.parse(ctx.assets.open("rest_stops.csv").bufferedReader().readText()).also { stops = it }
        val e = Engine(rest)
        val l = if (demo) DemoCarLink(ctx.assets.open("demo_route.csv").bufferedReader().readText()) else PleosCarLink(ctx.applicationContext)
        engine = e; link = l
        l.start(
            onSteer = { t, deg -> main.post { e.steering.onAngle(t, deg) } },
            onSignal = { t -> main.post { e.steering.onSignal(t) } },
            onAnswer = { yes -> main.post { if (engine === e) e.answer(yes).forEach(l::apply) } },
        )
        last = l.now()
        main.removeCallbacks(loop)
        main.post(loop)
    }

    fun answer(yes: Boolean) {
        val e = engine ?: return
        e.answer(yes).forEach { link?.apply(it) }
    }

    private val loop = object : Runnable {
        override fun run() {
            val e = engine ?: return
            val l = link ?: return
            val now = l.now()
            val dt = (now - last).coerceAtLeast(0) / 1000.0
            last = now
            e.tick(now, dt, l.hour(), l.state()).forEach(l::apply)
            main.postDelayed(this, 250)
        }
    }

    /** Gleo function call -> (status, English message, data JSON array). */
    fun gleo(action: String): Triple<String, String, String?> {
        val e = engine ?: return Triple("fail", "Sumteum is not running. Please open the app first.", null)
        val l = link!!
        val cmds: List<Cmd>? = when (action) {
            "VENTILATE_CABIN" -> e.ventilateNow(l.now(), l.state().outsideAirBlock)
            "ADD_REST_STOP" -> e.addRestStopNow()
            "REPORT_DROWSINESS" -> e.reportDrowsy(l.now(), l.state().outsideAirBlock)
            else -> return Triple("fail", "Unknown function.", null)
        }
        if (cmds == null) {
            return if (action == "VENTILATE_CABIN") Triple("fail", "Outside air is kept closed because of ${l.state().outsideAirBlock}.", null)
            else Triple("fail", "No rest stop was found ahead within 40 km.", null)
        }
        cmds.forEach(l::apply)
        val stop = e.nextStop
        val data = JSONArray().put(JSONObject()
            .put("co2Ppm", e.ppm.toInt()).put("riskScore", e.risk.score)
            .put("nextRestStop", stop?.first?.name ?: "").put("distanceKm", stop?.second?.toInt() ?: 0)).toString()
        val msg = when (action) {
            "VENTILATE_CABIN" -> "Outside air is on for a short time and the previous climate setting will come back."
            "ADD_REST_STOP" -> "The next rest stop was added as a waypoint."
            else -> "Fresh air and a cooler cabin are on, and the next rest stop was added as a waypoint."
        }
        return Triple("success", msg, data)
    }
}

/** Keeps the engine alive while navigation or another app is in front. */
class SumteumService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("run", "숨틈 실행", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "run")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("숨틈이 실내 공기와 졸음 위험을 보고 있습니다")
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (Hub.engine == null) Hub.start(this, demo = !Hub.hasPleosCar(this))
        return START_STICKY
    }
}
