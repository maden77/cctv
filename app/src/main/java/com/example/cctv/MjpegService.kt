package com.example.cctv

import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MjpegServer(
    private val port: Int = 8080,
    private val username: String = "admin",
    private val password: String = "cctv123"
) {
    private val tag = "MjpegServer"
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val running = AtomicBoolean(false)

    @Volatile
    private var latestFrame: ByteArray? = null

    @Volatile
    var recordingEnabled = false

    @Volatile
    var motionDetectionEnabled = true

    @Volatile
    var lastMotionTime = 0L
        private set

    var onMotionDetected: (() -> Unit)? = null

    fun pushFrame(jpeg: ByteArray) {
        latestFrame = jpeg
    }

    fun notifyMotion() {
        lastMotionTime = System.currentTimeMillis()
        onMotionDetected?.invoke()
    }

    fun start() {
        if (running.get()) return
        running.set(true)
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i(tag, "Server running on :$port (user=$username)")
                while (running.get()) {
                    val client = serverSocket?.accept() ?: break
                    clients.add(client)
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(tag, "Server error", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separatorIndex = line.indexOf(':')
                if (separatorIndex > 0) {
                    headers[line.substring(0, separatorIndex).trim().lowercase()] =
                        line.substring(separatorIndex + 1).trim()
                }
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val path = parts[1]
            if (!checkAuth(headers["authorization"])) {
                val body = "Authentication required"
                val response = "HTTP/1.1 401 Unauthorized\r\n" +
                    "WWW-Authenticate: Basic realm=\"CCTV\"\r\n" +
                    "Content-Length: ${body.length}\r\n\r\n$body"
                socket.getOutputStream().write(response.toByteArray())
                socket.close()
                return
            }

            when {
                path == "/" || path == "/index.html" -> serveHtml(socket)
                path == "/video" -> streamMjpeg(socket)
                path == "/api/status" -> serveJson(socket, statusJson())
                path == "/api/toggle_record" -> {
                    recordingEnabled = !recordingEnabled
                    serveJson(socket, """{"recording":$recordingEnabled}""")
                }
                path == "/api/toggle_motion" -> {
                    motionDetectionEnabled = !motionDetectionEnabled
                    serveJson(socket, """{"motion":$motionDetectionEnabled}""")
                }
                path.startsWith("/snapshot") -> serveSnapshot(socket)
                else -> {
                    val body = "404 Not Found"
                    val response = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.length}\r\n\r\n$body"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Client error: ${e.message}")
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun checkAuth(header: String?): Boolean {
        if (header == null) return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false

        val decoded = try {
            String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT))
        } catch (_: Exception) {
            return false
        }

        return decoded == "$username:$password"
    }

    private fun streamMjpeg(socket: Socket) {
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Connection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                "Cache-Control: no-cache\r\n\r\n").toByteArray()
        )
        output.flush()

        var lastSent: ByteArray? = null
        while (running.get() && !socket.isClosed) {
            val frame = latestFrame ?: continue
            if (frame !== lastSent) {
                output.write("--frame\r\n".toByteArray())
                output.write("Content-Type: image/jpeg\r\n".toByteArray())
                output.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                output.write(frame)
                output.write("\r\n".toByteArray())
                output.flush()
                lastSent = frame
            }
            Thread.sleep(33)
        }
        socket.close()
    }

    private fun serveSnapshot(socket: Socket) {
        val frame = latestFrame ?: run {
            socket.getOutputStream().write("HTTP/1.1 503 Unavailable\r\n\r\n".toByteArray())
            socket.close()
            return
        }

        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${frame.size}\r\n\r\n"
        socket.getOutputStream().write(header.toByteArray())
        socket.getOutputStream().write(frame)
        socket.close()
    }

    private fun serveJson(socket: Socket, json: String) {
        val response = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${json.length}\r\n\r\n$json"
        socket.getOutputStream().write(response.toByteArray())
        socket.close()
    }

    private fun statusJson(): String {
        val motionRecent = (System.currentTimeMillis() - lastMotionTime) < 5000
        return """{"recording":$recordingEnabled,"motion":$motionDetectionEnabled,"motion_recent":$motionRecent}"""
    }

    private fun serveHtml(socket: Socket) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>CCTV Mandiri</title>
                <style>
                    body { margin: 0; background: #111; color: white; font-family: system-ui, sans-serif; text-align: center; }
                    h1 { color: #4ade80; padding: 12px; margin: 0; background: #1a1a1a; font-size: 1.2rem; }
                    img { width: 100%; max-width: 800px; margin: 12px auto; display: block; border-radius: 12px; }
                    .row { display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; padding: 12px; }
                    button { padding: 12px 20px; border: none; border-radius: 8px; font-weight: bold; background: #2563eb; color: white; font-size: 1rem; }
                    button.rec { background: #dc2626; }
                    .status { display: inline-block; padding: 4px 10px; border-radius: 10px; background: #333; margin-left: 8px; font-size: 0.8rem; }
                    .status.motion { background: #dc2626; animation: blink 1s infinite; }
                    @keyframes blink { 50% { opacity: 0.4; } }
                </style>
            </head>
            <body>
                <h1>📹 CCTV Mandiri <span id="s" class="status">LIVE</span></h1>
                <img src="/video" alt="stream" />
                <div class="row">
                    <button id="btnR" onclick="tr()">🔴 Start Recording</button>
                    <button id="btnM" onclick="tm()">👁️ Motion: ON</button>
                    <button onclick="location.reload()">🔄 Refresh</button>
                </div>
                <script>
                    async function tr() {
                        const r = await fetch('/api/toggle_record', { method: 'POST' });
                        const d = await r.json();
                        const b = document.getElementById('btnR');
                        b.textContent = d.recording ? '⏹️ Stop Recording' : '🔴 Start Recording';
                        b.className = d.recording ? 'rec' : '';
                    }
                    async function tm() {
                        const r = await fetch('/api/toggle_motion', { method: 'POST' });
                        const d = await r.json();
                        const b = document.getElementById('btnM');
                        b.textContent = '👁️ Motion: ' + (d.motion ? 'ON' : 'OFF');
                    }
                    setInterval(async () => {
                        const r = await fetch('/api/status');
                        const d = await r.json();
                        const s = document.getElementById('s');
                        if (d.motion_recent) {
                            s.textContent = '⚠️ MOTION';
                            s.className = 'status motion';
                        } else if (d.recording) {
                            s.textContent = '🔴 REC';
                            s.className = 'status';
                        } else {
                            s.textContent = 'LIVE';
                            s.className = 'status';
                        }
                    }, 2000);
                </script>
            </body>
            </html>
        """.trimIndent()

        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${html.toByteArray().size}\r\n\r\n$html"
        socket.getOutputStream().write(response.toByteArray())
        socket.close()
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clients.forEach { client ->
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        clients.clear()
    }

    fun isRunning() = running.get()
}
