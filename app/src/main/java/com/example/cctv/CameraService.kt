package com.example.cctv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class CameraService : LifecycleService() {

    private val tag = "CameraService"
    private var mjpegServer: MjpegServer? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lastMotionNotificationAt = 0L
    private var previousGray: ByteArray? = null
    private var previousWidth = 0
    private var previousHeight = 0

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
        createNotificationChannels()
        startForeground(NOTIF_ID, buildServiceNotification("Camera ready"))
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (mjpegServer == null) {
            mjpegServer = MjpegServer(PORT, USER, PASS).apply {
                onMotionDetected = { showMotionNotification() }
                start()
            }
            startCamera()
        }

        return START_STICKY
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, ::processImage) }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis, videoCapture)
                Log.i(tag, "Camera ready on :$PORT")
            } catch (e: Exception) {
                Log.e(tag, "Camera initialization failed", e)
            }
        }, cameraExecutor)
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            val server = mjpegServer ?: return
            val jpeg = convertYuvToJpeg(imageProxy)
            if (jpeg != null) {
                server.pushFrame(jpeg)
            }

            if (server.motionDetectionEnabled && imageProxy.format == ImageFormat.YUV_420_888) {
                detectMotion(imageProxy)?.let { triggered ->
                    if (triggered) server.notifyMotion()
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun detectMotion(image: ImageProxy): Boolean? {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val width = image.width
        val height = image.height
        val rowStride = yPlane.rowStride

        val gridWidth = 40
        val gridHeight = 30
        val sample = ByteArray(gridWidth * gridHeight)
        val stepX = width / gridWidth
        val stepY = height / gridHeight

        buffer.rewind()
        val row = ByteArray(rowStride)
        for (y in 0 until height step stepY) {
            val toRead = minOf(rowStride, buffer.remaining())
            if (toRead <= 0) break
            buffer.get(row, 0, toRead)
            for (x in 0 until width step stepX) {
                sample[(y / stepY) * gridWidth + (x / stepX)] = row[x]
            }
        }

        val previous = previousGray
        val isFirstFrame = previous == null || previousWidth != gridWidth || previousHeight != gridHeight
        previousGray = sample
        previousWidth = gridWidth
        previousHeight = gridHeight

        if (isFirstFrame) return null

        var diffCount = 0
        for (i in sample.indices) {
            if (abs(sample[i].toInt() - previous[i].toInt()) > 25) {
                diffCount++
            }
        }

        return diffCount > (sample.size * 0.15)
    }

    private fun convertYuvToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val nv21 = ByteArray(width * height * 3 / 2)
            copyPlane(yPlane.buffer, yPlane.rowStride, width, height, nv21, 0, 1)
            copyPlane(uPlane.buffer, uPlane.rowStride, width / 2, height / 2, nv21, width * height, 2)
            copyPlane(vPlane.buffer, vPlane.rowStride, width / 2, height / 2, nv21, width * height + 1, 2)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val output = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 60, output)
            output.toByteArray()
        } catch (e: Exception) {
            Log.e(tag, "JPEG conversion failed", e)
            null
        }
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        offset: Int,
        pixelStride: Int
    ) {
        val row = ByteArray(rowStride)
        var outPosition = offset
        buffer.rewind()
        for (y in 0 until height) {
            val toRead = minOf(rowStride, buffer.remaining())
            if (toRead <= 0) break
            buffer.get(row, 0, toRead)

            var x = 0
            while (x < width) {
                if (outPosition < out.size) {
                    out[outPosition] = row[x * pixelStride]
                }
                outPosition += pixelStride
                x++
            }
        }
    }

    fun startRecording(): File? {
        val vc = videoCapture ?: return null
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val fileName = "rec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val file = File(dir, fileName)

        val outputOptions = androidx.camera.video.FileOutputOptions.Builder(file).build()
        val pendingRecording = vc.output.prepareRecording(this, outputOptions)
        activeRecording = pendingRecording.start(mainExecutor) {
            // Recording callbacks can be handled here if needed.
        }
        mjpegServer?.recordingEnabled = true
        updateServiceNotification("Recording to $fileName")
        return file
    }

    fun stopRecording() {
        try {
            activeRecording?.stop()
            activeRecording?.close()
        } catch (e: Exception) {
            Log.w(tag, "Failed to stop recording", e)
        }

        activeRecording = null
        mjpegServer?.recordingEnabled = false
        updateServiceNotification("Camera ready")
    }

    private val mainExecutor: java.util.concurrent.Executor
        get() = androidx.core.content.ContextCompat.getMainExecutor(this)

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CCTV Service", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(MOTION_CHANNEL_ID, "Motion Detection", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun buildServiceNotification(text: String): Notification {
        val intent = Intent(this, ServerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CCTV Active")
            .setContentText("$text — port $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateServiceNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) as NotificationManager
        manager.notify(NOTIF_ID, buildServiceNotification(text))
    }

    private fun showMotionNotification() {
        val now = System.currentTimeMillis()
        if (now - lastMotionNotificationAt < 10_000) return
        lastMotionNotificationAt = now

        val manager = getSystemService(NotificationManager::class.java) as NotificationManager
        val notification = NotificationCompat.Builder(this, MOTION_CHANNEL_ID)
            .setContentTitle("⚠️ Motion detected")
            .setContentText("Activity detected at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(MOTION_NOTIF_ID, notification)

        if (activeRecording == null) {
            startRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        mjpegServer?.stop()
        mjpegServer = null
        cameraExecutor.shutdown()
        isRunning = false
    }

    override fun onBind(intent: Intent): IBinder? = null
}
