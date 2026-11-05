package com.jarvis.ai.core.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jarvis.ai.MainActivity
import com.jarvis.ai.R

/**
 * User-initiated screen capture session. Android shows the system chooser for
 * entire screen or one application. Frame stays in memory; nothing is uploaded.
 * A separate vision-model integration is required for semantic image analysis.
 */
class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: MediaProjection.Callback? = null
    private var ending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "LORA — مشاركة الشاشة", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            closeProjection()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        // Must enter a mediaProjection foreground service BEFORE getMediaProjection().
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_DATA)
        }
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        if (data == null || code != android.app.Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            // A consent intent/token is single-use on Android 14+.
            val manager = getSystemService(MediaProjectionManager::class.java)
            val p = manager.getMediaProjection(code, data)
                ?: throw IllegalStateException("No media projection granted")
            projection = p
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    closeProjection()
                    stopSelf()
                }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    // Don't recreate virtual display with the same consent.
                    if (width <= 0 || height <= 0) return
                    val old = reader ?: return
                    val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                    next.setOnImageAvailableListener({ incoming -> cacheLatest(incoming) }, mainHandler)
                    reader = next
                    display?.resize(width, height, resources.displayMetrics.densityDpi)
                    display?.surface = next.surface
                    old.close()
                }
            }
            callback = cb
            p.registerCallback(cb, mainHandler)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also {
                it.setOnImageAvailableListener({ incoming -> cacheLatest(incoming) }, mainHandler)
            }
            display = p.createVirtualDisplay(
                "LORA screen session", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, mainHandler
            )
            isSharing = true
        } catch (e: Exception) {
            closeProjection()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun cacheLatest(incoming: ImageReader) {
        val image = runCatching { incoming.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (image.format != PixelFormat.RGBA_8888) return
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val stride = plane.rowStride / 4
            val padded = Bitmap.createBitmap(stride, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val frame = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            latestFrame?.recycle()
            latestFrame = frame
        } finally {
            image.close()
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("لورا تشارك الشاشة")
            .setContentText("مشاركة فعالة باختيارك. اضغط إيقاف لإنهائها.")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "إيقاف المشاركة", stop).build()
    }

    private fun closeProjection() {
        if (ending) return
        ending = true
        isSharing = false
        latestFrame?.recycle()
        latestFrame = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        val p = projection
        projection = null
        callback?.let { cb -> runCatching { p?.unregisterCallback(cb) } }
        callback = null
        runCatching { p?.stop() }
    }

    override fun onDestroy() {
        closeProjection()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.jarvis.ai.SCREEN_SHARE_START"
        const val ACTION_STOP = "com.jarvis.ai.SCREEN_SHARE_STOP"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_DATA = "projection_result_data"
        private const val CHANNEL = "lora_screen_share"
        private const val NOTIFICATION_ID = 1772
        @Volatile var isSharing: Boolean = false
            private set
        @Volatile var latestFrame: Bitmap? = null
            private set
    }
}
