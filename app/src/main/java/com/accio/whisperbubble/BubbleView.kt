package com.accio.whisperbubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The floating circle. State is communicated by colour alone, so there is no
 * text or icon on it.
 */
class BubbleView(context: Context) : View(context) {

    companion object {
        const val IDLE = 0
        const val RECORDING = 1
        const val BUSY = 2
        const val ERROR = 3
        const val DONE = 4
    }

    var state: Int = IDLE
        set(value) {
            if (field == value) return
            field = value
            phase = 0f
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private var phase = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val arc = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - 4f * density

        fill.color = when (state) {
            RECORDING -> 0xFFE0483A.toInt()
            BUSY -> 0xFFDD9A35.toInt()
            ERROR -> 0xFF8E6BD0.toInt()
            DONE -> 0xFF3E9C5A.toInt()
            else -> 0xFF23211F.toInt()
        }

        fill.setShadowLayer(7f * density, 0f, 2.5f * density, 0x4D000000)
        canvas.drawCircle(cx, cy, radius, fill)
        fill.clearShadowLayer()

        // Inner mark: filled dot when idle, square when recording, ring when busy.
        val mark = 0xFFF7F5F0.toInt()
        when (state) {
            RECORDING -> {
                val s = radius * 0.46f
                fill.color = mark
                canvas.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.35f, s * 0.35f, fill)
            }
            BUSY -> {
                ring.color = mark
                ring.strokeWidth = 3f * density
                ring.strokeCap = Paint.Cap.ROUND
                arc.set(cx - radius * 0.46f, cy - radius * 0.46f, cx + radius * 0.46f, cy + radius * 0.46f)
                canvas.drawArc(arc, phase * 360f, 110f, false, ring)
            }
            ERROR -> {
                fill.color = mark
                val s = radius * 0.34f
                canvas.drawRoundRect(cx - s, cy - 1.6f * density, cx + s, cy + 1.6f * density, 1.6f * density, 1.6f * density, fill)
            }
            else -> {
                fill.color = mark
                canvas.drawCircle(cx, cy, radius * 0.30f, fill)
            }
        }

        if (state == RECORDING) {
            ring.color = Color.argb(
                ((1f - phase) * 100).toInt().coerceIn(0, 255),
                224, 72, 58
            )
            ring.strokeWidth = 2.5f * density
            ring.strokeCap = Paint.Cap.ROUND
            canvas.drawCircle(cx, cy, radius + phase * 9f * density, ring)
        }

        if (state == RECORDING || state == BUSY) {
            phase += if (state == RECORDING) 0.035f else 0.022f
            if (phase > 1f) phase = 0f
            postInvalidateOnAnimation()
        }
    }
}
