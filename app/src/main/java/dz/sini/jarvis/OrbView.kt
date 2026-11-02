package dz.sini.jarvis

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.*

/** Canvas-only, resolution-independent animated orb; refreshes at display cadence. */
class OrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val started = SystemClock.uptimeMillis()
    var active: Boolean = false
        set(value) { field = value; postInvalidateOnAnimation() }
    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) * .39f
        val t = (SystemClock.uptimeMillis() - started) / 1000f
        val speed = if (active) 1.8f else .75f
        canvas.drawColor(Color.TRANSPARENT)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, r * 1.33f,
            intArrayOf(0x440064C9, 0x150050B5, Color.TRANSPARENT),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.33f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * resources.displayMetrics.density
        paint.color = 0xFF04CBFF.toInt()
        paint.setShadowLayer(13f, 0f, 0f, 0xFF00BFFF.toInt())
        canvas.drawCircle(cx, cy, r * (1f + .012f * sin(t * speed)), paint)
        paint.clearShadowLayer()
        paint.strokeWidth = 1.2f * resources.displayMetrics.density
        paint.color = 0x6633ADFF
        canvas.drawCircle(cx, cy, r * 1.10f, paint)
        paint.style = Paint.Style.FILL
        for (i in 0 until 80) {
            val a = i * (Math.PI * 2 / 80) + t * .17
            val rr = r * 1.15f
            paint.color = if (i % 4 == 0) 0xFF00D9FF.toInt() else 0x660099EE
            canvas.drawCircle(cx + cos(a).toFloat() * rr, cy + sin(a).toFloat() * rr,
                if (i % 4 == 0) 1.7f else 1.0f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * resources.displayMetrics.density
        for (line in 0 until 52) {
            val path = Path()
            val phase = line * .105f
            val shade = (42 + line * 2).coerceAtMost(170)
            paint.color = Color.argb(shade, 0, 185, 255)
            for (s in 0..180) {
                val x = (s / 180f * 2f - 1f)
                val envelope = sqrt((1f - x * x).coerceAtLeast(0f))
                val y = .35f * sin(x * 4.7f + phase + t * speed) +
                    .24f * cos(x * 6.2f - phase * 1.4f - t * speed * .7f)
                val px = cx + r * .91f * x
                val py = cy + r * envelope * y + r * .13f * sin(phase + t * .37f)
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, paint)
        }
        paint.strokeWidth = 3f * resources.displayMetrics.density
        paint.color = 0xFF45DEFF.toInt()
        paint.setShadowLayer(14f, 0f, 0f, 0xFF00CCFF.toInt())
        canvas.drawArc(cx-r, cy-r, cx+r, cy+r, (t * 34f) % 360f, 82f, false, paint)
        paint.clearShadowLayer()
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }
}