package com.example.cctv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs

class CameraService : LifecycleService() {

    private val TAG = "CameraService"
    private var mjpegServer: MjpegServer? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recorder: Recorder? = null
    private var activeRecording: Recording? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lastMotionNotifTime = 0L
    private var prevGray: ByteArray? = null
    private var prevWidth = 0
    private var prevHeight = 0

    companion object {
        const val CHANNEL_ID = "cctv_channel"
        const val MOTION_CHANNEL_ID = "cctv_motion"
        const val NOTIF_ID = 1001
        const val MOTION_NOTIF_ID = 1002
        const val PORT = 8080
        const val USER = "admin"
        const val PASS = "cctv123"
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ID, buildServiceNotification("Kamera siap"))
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (mjpegServer == null) {
            mjpegServer = MjpegServer(PORT, USER, PASS).also {
                it.onMotionDetected = { showMotionNotification() }
                it.start()
            }
            startCamera()
        }
        return START_STICKY
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

                // ==== Preview buffer (ImageAnalysis) ====
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, ::processImage) }

                // ==== VideoCapture untuk rekam ====
                recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder!!)

                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis, videoCapture)

                Log.i(TAG, "Camera ready on :$PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Camera init error", e)
            }
        }, cameraExecutor)
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            val server = mjpegServer ?: return

            // ==== Encode ke JPEG ====
            val jpeg = yuv420ToJpeg(imageProxy)
            if (jpeg != null) server.pushFrame(jpeg)

            // ==== Deteksi gerakan ====
            if (server.motionDetectionEnabled && imageProxy.format == ImageFormat.YUV_420_888) {
                detectMotion(imageProxy)?.let { server.notifyMotion() }
            }
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Deteksi gerakan pakai frame difference sederhana.
     * Return true kalau ada gerakan signifikan.
     */
    private fun detectMotion(image: ImageProxy): Boolean? {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val w = image.width
        val h = image.height
        val rowStride = yPlane.rowStride

        // Downsample Y-plane jadi grid kecil (mis. 40x30)
        val gw = 40
        val gh = 30
        val small = ByteArray(gw * gh)
        val stepX = w / gw
        val stepY = h / gh

        buffer.rewind()
        val row = ByteArray(rowStride)
        for (y in 0 until h step stepY) {
            val toRead = minOf(rowStride, buffer.remaining())
            if (toRead <= 0) break
            buffer.get(row, 0, toRead)
            for (x in 0 until w step stepX) {
                small[(y / stepY) * gw + (x / stepX)] = row[x]
            }
        }

        val prev = prevGray
        val isFirst = prev == null || prevWidth != gw || prevHeight != gh
        prevGray = small
        prevWidth = gw
        prevHeight = gh

        if (isFirst) return null

        var diffCount = 0
        for (i in small.indices) {
            if (abs(small[i].toInt() - prev[i].toInt()) > 25) diffCount++
        }
        // Threshold: >15% piksel berubah = gerakan
        return diffCount > (small.size * 0.15)
    }

    /** Konversi YUV_420_888 → JPEG pakai NV21 trick */
    private fun yuv420ToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val w = image.width
            val h = image.height
            val y = image.planes[0]
            val u = image.planes[1]
            val v = image.planes[2]

            val nv21 = ByteArray(w * h * 3 / 2)
            copyPlane(y.buffer, y.rowStride, w, h, nv21, 0, 1)
            copyPlane(u.buffer, u.rowStride, w / 2, h / 2, nv21, w * h, 2)
            copyPlane(v.buffer, v.rowStride, w / 2, h / 2, nv21, w * h + 1, 2)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, w, h), 60, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "JPEG encode error", e); null
        }
    }

    private fun copyPlane(buffer: ByteBuffer, rowStride: Int, w: Int, h: Int,
                          out: ByteArray, offset: Int, pixelStride: Int) {
        val row = ByteArray(rowStride)
        var outPos = offset
        buffer.rewind()
        for (y in 0 until h) {
            val toRead = minOf(rowStride, buffer.remaining())
            if (toRead <= 0) break
            buffer.get(row, 0, toRead)
            var x = 0
            while (x < w) {
                if (outPos < out.size) out[outPos] = row[x * pixelStride]
                outPos += pixelStride
                x++
            }
        }
    }

    // ================== REKAMAN ==================
    fun startRecording(): File? {
        val vc = videoCapture ?: return null
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val name = "rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val file = File(dir, name)

        val opts = FileOutputOptions.Builder(file).build()
        val pending = vc.output.prepareRecording(this, opts)
        activeRecording = pending.start(mainExecutor) { /* events */ }
        mjpegServer?.recordingEnabled = true
        updateServiceNotification("🔴 Merekam ke $name")
        Log.i(TAG, "Recording: ${file.absolutePath}")
        return file
    }

    fun stopRecording() {
        try {
            activeRecording?.stop()
            activeRecording?.close()
        } catch (e: Exception) { Log.w(TAG, "Stop rec: ${e.message}") }
        activeRecording = null
        mjpegServer?.recordingEnabled = false
        updateServiceNotification("Kamera siap")
    }

    private val mainExecutor: java.util.concurrent.Executor
        get() = androidx.core.content.ContextCompat.getMainExecutor(this)

    // ================== NOTIFIKASI ==================
    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CCTV Service",
                    NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(MOTION_CHANNEL_ID, "Deteksi Gerakan",
                    NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun buildServiceNotification(text: String): Notification {
        val intent = Intent(this, ServerActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CCTV Aktif")
            .setContentText("$text — port $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildServiceNotification(text))
    }

    private fun showMotionNotification() {
        val now = System.currentTimeMillis()
        if (now - lastMotionNotifTime < 10_000) return // max 1/10 detik
        lastMotionNotifTime = now

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, MOTION_CHANNEL_ID)
            .setContentTitle("⚠️ Gerakan Terdeteksi")
            .setContentText("Ada aktivitas di kamera — ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(MOTION_NOTIF_ID, notif)

        // Auto-start rekam kalau belum
        if (activeRecording == null) startRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        mjpegServer?.stop(); mjpegServer = null
        cameraExecutor.shutdown()
        isRunning = false
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent); return null
    }
}
