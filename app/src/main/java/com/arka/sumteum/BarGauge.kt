package com.arka.sumteum

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Horizontal CO2 bar from 400 to 3,000 ppm with the 1,500 ppm ventilation line. */
class BarGauge(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    var ppm = 600.0
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val track = Paint().apply { color = ctx.getColor(R.color.surface) }
    private val fill = Paint()
    private val tick = Paint().apply { color = ctx.getColor(R.color.text); strokeWidth = 2 * dp }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.text2); textSize = 15 * resources.displayMetrics.scaledDensity
        typeface = resources.getFont(R.font.noto_sans_kr_regular)
    }

    override fun onDraw(c: Canvas) {
        val barTop = 6 * dp
        val barBottom = 20 * dp
        fun x(p: Double) = (((p - 400) / 2600).coerceIn(0.0, 1.0) * width).toFloat()
        c.drawRect(0f, barTop, width.toFloat(), barBottom, track)
        fill.color = context.getColor(if (ppm >= 1500) R.color.accent else R.color.text)
        c.drawRect(0f, barTop, x(ppm), barBottom, fill)
        val t = x(1500.0)
        c.drawLine(t, 0f, t, barBottom + 4 * dp, tick)
        c.drawText("1,500 환기", t + 6 * dp, height - 2 * dp, label)
    }
}
