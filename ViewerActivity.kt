package com.example.cctv

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.cctv.databinding.ActivityViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private val client = OkHttpClient()
    private var streaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Masukkan URL stream", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startStream(url)
        }

        binding.btnStop.setOnClickListener {
            stopStream()
        }
    }

    private fun startStream(baseUrl: String) {
        stopStream()
        streaming = true
        val cleanUrl = baseUrl.trimEnd('/')
        val url = "$cleanUrl/video"

        binding.btnConnect.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val auth = "${CameraService.USER}:${CameraService.PASS}"
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "Authorization",
                        "Basic ${Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)}"
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Gagal terhubung: HTTP ${response.code}")
                    }

                    val body = response.body ?: throw IllegalStateException("Body stream kosong")
                    val input = body.byteStream()
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)

                    while (streaming) {
                        val read = input.read(chunk)
                        if (read == -1) break

                        buffer.write(chunk, 0, read)
                        val raw = buffer.toByteArray()
                        val text = raw.toString(Charsets.ISO_8859_1)
                        val headerIndex = text.indexOf("Content-Type: image/jpeg")

                        if (headerIndex >= 0) {
                            val payloadStart = text.indexOf("\r\n\r\n", headerIndex)
                            if (payloadStart >= 0) {
                                val payload = text.substring(payloadStart + 4)
                                val endIndex = payload.indexOf("\r\n--frame")
                                val jpegPayload = if (endIndex >= 0) {
                                    payload.substring(0, endIndex)
                                } else {
                                    payload
                                }

                                val jpegBytes = jpegPayload.toByteArray(Charsets.ISO_8859_1)
                                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                if (bitmap != null) {
                                    runOnUiThread {
                                        binding.ivStream.setImageBitmap(bitmap)
                                    }
                                }
                                buffer.reset()
                            }
                        }

                        if (buffer.size() > 256 * 1024) {
                            buffer.reset()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@ViewerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    binding.btnConnect.isEnabled = true
                }
            }
        }
    }

    private fun stopStream() {
        streaming = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
    }
}
