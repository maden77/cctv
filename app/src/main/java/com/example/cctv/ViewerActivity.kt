package com.example.cctv

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.cctv.databinding.ActivityViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private val client = OkHttpClient()
    private var isStreaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Enter URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startStream(url)
        }

        binding.btnStop.setOnClickListener {
            isStreaming = false
        }
    }

    private fun startStream(baseUrl: String) {
        isStreaming = true
        val url = if (baseUrl.endsWith("/")) "${baseUrl}video" else "$baseUrl/video"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", Credentials.basic(CameraService.USER, CameraService.PASS))
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val buffer = ByteArray(8192)
                val streamBuffer = ByteArrayOutputStream()
                var read: Int

                while (isStreaming && body.byteStream().use { input ->
                        read = input.read(buffer)
                        if (read > 0) {
                            streamBuffer.write(buffer, 0, read)
                        }
                        read
                    } >= 0) {
                    parseJpegFrames(streamBuffer.toByteArray())
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@ViewerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseJpegFrames(bytes: ByteArray) {
        val boundary = "--frame".toByteArray(Charsets.US_ASCII)
        var searchStart = 0
        var frameCount = 0

        while (true) {
            val start = bytes.indexOf(boundary, searchStart)
            if (start < 0) break
            val contentTypeIndex = bytes.indexOf("Content-Type: image/jpeg".toByteArray(Charsets.US_ASCII), start)
            if (contentTypeIndex < 0) break
            val headerEnd = bytes.indexOf("\r\n\r\n".toByteArray(Charsets.US_ASCII), contentTypeIndex)
            if (headerEnd < 0) break

            val payloadStart = headerEnd + 4
            val payloadEnd = bytes.indexOf(boundary, payloadStart)
            if (payloadEnd < 0) break

            val jpgBytes = bytes.copyOfRange(payloadStart, payloadEnd)
            if (jpgBytes.isNotEmpty()) {
                val bitmap = BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size)
                if (bitmap != null) {
                    runOnUiThread {
                        binding.ivStream.setImageBitmap(bitmap)
                    }
                    frameCount++
                }
            }

            searchStart = payloadEnd + 1
            if (frameCount > 10) break
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
    }
}
