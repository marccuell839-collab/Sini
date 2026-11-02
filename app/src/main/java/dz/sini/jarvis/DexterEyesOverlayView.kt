package dz.sini.jarvis

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.*

/** Lightweight animated neon eyes, continuously rendered only while attached. */
class DexterEyesOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = SystemClock.uptimeMillis()
    private var energy = 0f
    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = width / 200f
        val sy = height / 86f
        canvas.save()
        canvas.scale(sx, sy)
        val time = (SystemClock.uptimeMillis() - start) / 1000f
        val mode = DexterVoiceService.visualMode
        energy += ((DexterVoiceService.audioLevel + if (mode == "speaking") .28f else 0f).coerceIn(0f,1f) - energy) * .16f
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 200f, 86f, 0xED050E19.toInt(), 0xEC112B3C.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(1f, 2f, 199f, 84f, 38f, 38f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = 0xAA20E5FF.toInt()
        canvas.drawRoundRect(2f, 3f, 198f, 83f, 37f, 37f, paint)
        val gaze = sin(time * .73f) * 4.5f
        val blinkPeriod = time % 5.3f
        val blink = if (blinkPeriod > 5.02f) (1f - abs(blinkPeriod - 5.16f) / .14f).coerceIn(0f,1f) else 0f
        val heightFactor = max(.08f, 1f - blink * .96f)
        for (x in listOf(66f,134f)) {
            val y = 43f + sin(time * .8f) * 1.5f
            val glow = 11f + energy * 11f
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(x+gaze,y,glow*2f,
                intArrayOf(0x7800EAFF,0x3100CFFF,Color.TRANSPARENT),
                floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
            canvas.drawCircle(x+gaze,y,glow*2f,paint)
            paint.shader = null
            paint.color = 0xFF03C9FF.toInt()
            canvas.drawOval(x-19f+gaze,y-14f*heightFactor,x+19f+gaze,y+14f*heightFactor,paint)
            paint.color = 0xFFB3FDFF.toInt()
            canvas.drawOval(x-9f+gaze,y-11f*heightFactor,x+9f+gaze,y+11f*heightFactor,paint)
            paint.color = 0xFF06384C.toInt()
            canvas.drawOval(x-3f+gaze,y-8f*heightFactor,x+6f+gaze,y+8f*heightFactor,paint)
            paint.color=Color.WHITE
            canvas.drawCircle(x-5f+gaze,y-5f*heightFactor,2.6f,paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x9905DFFF.toInt()
        val offset = (time * if (mode=="thinking") 70f else 20f) % 200f
        canvas.drawLine(offset,76f, min(offset+34f,190f),76f,paint)
        canvas.restore()
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }
}