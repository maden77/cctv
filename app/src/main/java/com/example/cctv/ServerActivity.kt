package com.example.cctv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.cctv.databinding.ActivityServerBinding
import java.net.Inet4Address
import java.net.NetworkInterface

class ServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ip = getLocalIpAddress() ?: "0.0.0.0"
        binding.tvIp.text = "http://$ip:${CameraService.PORT}\nUser: ${CameraService.USER}  Pass: ${CameraService.PASS}"

        binding.btnToggle.setOnClickListener {
            if (CameraService.isRunning) {
                stopService(Intent(this, CameraService::class.java))
                binding.btnToggle.text = "▶️ Start Camera"
                binding.tvStatus.text = "Status: Inactive"
            } else {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, CameraService::class.java)
                )
                binding.btnToggle.text = "⏹️ Stop Camera"
                binding.tvStatus.text = "Status: Active ✅"
                Toast.makeText(this, "Camera active at $ip:${CameraService.PORT}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getLocalIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
