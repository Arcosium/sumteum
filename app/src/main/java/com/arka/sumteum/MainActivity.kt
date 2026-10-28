package com.arka.sumteum

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import com.arka.sumteum.car.DemoCarLink
import com.arka.sumteum.core.Co2Model
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val levels = arrayOf("정상", "주의", "경고", "위험")
    private lateinit var v: Views

    private inner class Views {
        val mode: TextView = findViewById(R.id.mode)
        val clock: TextView = findViewById(R.id.clock)
        val demo: TextView = findViewById(R.id.demo)
        val ppm: TextView = findViewById(R.id.ppm)
        val gauge: BarGauge = findViewById(R.id.gauge)
        val air: TextView = findViewById(R.id.air)
        val risk: TextView = findViewById(R.id.risk)
        val level: TextView = findViewById(R.id.level)
        val factors: TextView = findViewById(R.id.factors)
        val stop: TextView = findViewById(R.id.stop)
        val km: TextView = findViewById(R.id.km)
        val waypoint: TextView = findViewById(R.id.waypoint)
        val ladder: LinearLayout = findViewById(R.id.ladder)
        val cabin: TextView = findViewById(R.id.cabin)
        val event: TextView = findViewById(R.id.event)
        val ask: View = findViewById(R.id.ask)
        val askText: TextView = findViewById(R.id.askText)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        v = Views()
        requestPermissions(PERMISSIONS, 1)
        startForegroundService(Intent(this, SumteumService::class.java))
        if (intent.getBooleanExtra("demo", false)) {
            Hub.start(this, demo = true)
            window.insetsController?.hide(WindowInsets.Type.systemBars())   // full-screen recording
        }
        v.demo.setOnClickListener { Hub.start(this, demo = true) }
        findViewById<View>(R.id.yes).setOnClickListener { Hub.answer(true) }
        findViewById<View>(R.id.no).setOnClickListener { Hub.answer(false) }
    }

    override fun onResume() { super.onResume(); ui.post(render) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(render) }

    private val render: Runnable = object : Runnable {
        override fun run() {
            draw()
            ui.postDelayed(this, 250)
        }
    }

    private fun draw() {
        val e = Hub.engine ?: return
        val l = Hub.link ?: return
        val car = e.car ?: return
        val cab = l.cabin()
        val accent = getColor(R.color.accent)

        v.mode.text = l.label
        v.clock.text = (l as? DemoCarLink)?.clock() ?: SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
        v.demo.visibility = if (car.driving) View.GONE else View.VISIBLE

        v.ppm.text = "%,d".format(e.ppm.roundToInt())
        v.ppm.setTextColor(if (e.ppm >= 1500) accent else getColor(R.color.text))
        v.gauge.ppm = e.ppm
        val toVent = Co2Model.secondsTo(1500.0, e.ppm, car.occupants, car.recirc, car.fan)
        v.air.text = listOfNotNull(
            when {
                car.outsideAirBlock != null -> "외기 보류 (${car.outsideAirBlock})"
                e.venting -> "외기 환기 중"
                car.recirc -> "내기 순환"
                else -> "외기 유입"
            },
            "${car.occupants}명 탑승",
            toVent?.takeIf { it > 0 && !e.venting }?.let { "1,500까지 ${(it / 60).roundToInt().coerceAtLeast(1)}분" },
        ).joinToString("  ·  ")

        v.risk.text = e.risk.score.toString()
        v.level.text = levels[e.risk.level]
        v.level.setTextColor(when (e.risk.level) { 0 -> getColor(R.color.text); 3 -> getColor(R.color.danger); else -> accent })
        v.factors.text = e.risk.parts.joinToString("\n") { "${it.first}  +${it.second}" }

        val stop = e.nextStop
        v.stop.text = stop?.first?.name ?: "찾는 중"
        v.km.text = stop?.let { (if (it.second < 10) "%.1f km" else "%.0f km").format(it.second) } ?: ""
        v.waypoint.text = if (cab.waypoint != null) "경유지로 추가됨" else ""

        for (i in 0 until v.ladder.childCount) {
            val t = v.ladder.getChildAt(i) as TextView
            val on = if (i == 3) e.restAdded else e.step >= i + 1
            t.setBackgroundColor(getColor(if (on) R.color.accent else R.color.surface))
            t.setTextColor(getColor(if (on) R.color.bg else R.color.text2))
            t.typeface = resources.getFont(if (on) R.font.noto_sans_kr_bold else R.font.noto_sans_kr_regular)
        }

        v.cabin.text = listOfNotNull(
            if (cab.recirc) "내기" else "외기",
            "풍량 ${cab.fan}",
            cab.tempC?.let { "%.1f℃".format(it) },
            "운전석 통풍 ${cab.seatVent}",
            if (cab.ambientBright) "실내 조명 켬" else null,
        ).joinToString("  ·  ")
        v.event.text = e.events.firstOrNull() ?: ""

        val asked = e.askedStop.takeIf { e.question != null }
        v.ask.visibility = if (asked != null) View.VISIBLE else View.GONE
        if (asked != null) v.askText.text = "${asked.first.name}\n${asked.second.roundToInt()}km 앞, 경유지로 넣을까요?"
    }

    companion object {
        private val PERMISSIONS = arrayOf(
            "pleos.car.permission.CAR_SEATS", "pleos.car.permission.CAR_CLIMATE",
            "pleos.car.permission.CAR_DRIVE", "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.CONTROL_CAR_CLIMATE", "pleos.car.permission.CONTROL_CAR_SEATS",
            "pleos.car.permission.CONTROL_CAR_LIGHTS",
            android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
