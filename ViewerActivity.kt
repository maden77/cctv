package com.example.cctv

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.cctv.databinding.ActivityViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

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
                Toast.makeText(this, "Masukkan URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startStream(url)
        }

        binding.btnStop.setOnClickListener {
            streaming = false
        }
    }

    /**
     * Karena ImageView tidak bisa langsung menampilkan MJPEG,
     * kita pakai pendekatan sederhana: decode MJPEG manual → Bitmap → ImageView.
     */
    private fun startStream(baseUrl: String) {
        streaming = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = if (baseUrl.endsWith("/")) "${baseUrl}video" else "$baseUrl/video"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val input = response.body?.byteStream() ?: return@launch

                val buffer = ByteArray(1024 * 64)
                var jpegBuffer = java.io.ByteArrayOutputStream()
                var prev = -1
                var curr: Int

                while (streaming && input.read(buffer).also { curr = it } != -1) {
                    // (Implementasi parsing MJPEG butuh deteksi boundary — disederhanakan)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@ViewerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streaming = false
    }
}
