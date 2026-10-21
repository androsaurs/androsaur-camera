package dev.hram.mobileledger

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small, bounded HTTP/1.1 prototype server. No CORS; cross-origin integration is a separate experiment. */
class PhoneServer(private val camera: CameraService, private val token: String) {
    val session = UUID.randomUUID().toString()
    private val listener = ServerSocket()
    private val workers = ThreadPoolExecutor(2, 6, 30, TimeUnit.SECONDS, ArrayBlockingQueue(12))
    @Volatile private var closed = false

    fun start() {
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(8088))
        Thread({
            while (!closed) {
                try {
                    val client = listener.accept()
                    try { workers.execute { client.use { handle(it) } } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { client.close() }
                } catch (e: Exception) { if (!closed) Log.w("MobileLedger", "HTTP accept failed", e) }
            }
        }, "camera-http").start()
    }

    fun close() { closed = true; listener.close(); workers.shutdownNow() }

    private fun handle(socket: Socket) {
        socket.soTimeout = 5000
        socket.tcpNoDelay = true
        try {
            val input = BufferedInputStream(socket.getInputStream())
            fun line(): String {
                val bytes = ArrayList<Byte>()
                while (true) {
                    val next = input.read()
                    require(next >= 0) { "Unexpected EOF" }
                    if (next == 10) break
                    require(bytes.size < 4096) { "Header too large" }
                    if (next != 13) bytes.add(next.toByte())
                }
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            val parts = line().split(' ')
            require(parts.size == 3 && parts[0] in setOf("GET", "POST"))
            val method = parts[0]
            val uri = URI(parts[1])
            val headers = mutableMapOf<String, String>()
            var count = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                require(++count <= 40)
                val colon = header.indexOf(':'); require(colon > 0)
                headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
            }
            if (uri.path == "/" && method == "GET") {
                respond(socket, 200, camera.assets.open("console.html").use { it.readBytes() }, "text/html; charset=utf-8")
                return
            }
            val supplied = headers["authorization"]?.removePrefix("Bearer ") ?: ""
            if (!MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) {
                json(socket, 401, JSONObject().put("error", "unauthorized")); return
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 0..4096 && "transfer-encoding" !in headers)
            val bodyBytes = ByteArray(length)
            var received = 0
            while (received < length) {
                val next = input.read(bodyBytes, received, length - received)
                require(next > 0); received += next
            }
            val body = if (length == 0) JSONObject() else JSONObject(bodyBytes.toString(Charsets.UTF_8))
            val query = (uri.rawQuery ?: "").split('&').filter { '=' in it }.associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            fun identifier(value: String): String {
                require(value.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid identifier" }
                return value
            }
            when ("$method ${uri.path}") {
                "GET /status" -> { if (query["passive"] != "1") camera.touch(); json(socket, 200, camera.status()) }
                "GET /frame.jpg" -> {
                    camera.touch()
                    val frame = camera.frame()
                    if (frame == null) json(socket, 202, JSONObject().put("state", "warming_up"))
                    else respond(socket, 200, frame, "image/jpeg")
                }
                "POST /torch" -> {
                    val ok = camera.setTorch(body.getBoolean("enabled"))
                    json(socket, if (ok) 200 else 400, if (ok) JSONObject().put("ok", true) else JSONObject().put("error", "torch_unavailable"))
                }
                "GET /focus" -> json(socket, 200, camera.focusStatus())
                "POST /focus" -> {
                    val action = body.getString("action")
                    val x = body.opt("x") ?: .5
                    val y = body.opt("y") ?: .5
                    require(x is Number && y is Number)
                    val (code, value) = camera.focus(action, x.toFloat(), y.toFloat())
                    json(socket, code, value)
                }
                "POST /calibration/tolerance" -> {
                    val percent = body.opt("tolerancePercent")
                    if (percent !is Number || !camera.setCalibrationTolerancePercent(percent.toDouble())) {
                        json(socket, 400, JSONObject().put("error", "invalid_calibration_tolerance")
                            .put("minPercent", 0.1).put("maxPercent", 5.0))
                    } else json(socket, 200, JSONObject().put("tolerancePercent", percent.toDouble()))
                }
                "POST /calibration/session" -> {
                    val (code, value) = camera.calibrationSession(identifier(body.getString("clientId")), body.getBoolean("active"))
                    json(socket, code, value)
                }
                "GET /calibration/frame" -> {
                    val (code, value) = camera.calibrationPacket()
                    json(socket, code, value)
                }
                "POST /capture" -> {
                    val id = identifier(body.getString("requestId"))
                    val client = identifier(body.getString("clientId"))
                    val width = if (body.has("width")) body.getInt("width") else null
                    val height = if (body.has("height")) body.getInt("height") else null
                    require((width == null) == (height == null))
                    camera.touch()
                    val (code, value) = camera.request(id, client, width, height)
                    json(socket, code, value)
                }
                "GET /result" -> {
                    val result = camera.result(identifier(query["requestId"] ?: ""), identifier(query["clientId"] ?: ""))
                    if (result.jpeg == null) json(socket, result.code, result.meta)
                    else respond(socket, 200, result.jpeg, "image/jpeg", mapOf(
                        "X-Focus-Status" to result.meta.optString("focus"),
                        "X-Capture-Ms" to result.meta.optString("captureMs"),
                        "X-Image-Width" to result.meta.optString("width"),
                        "X-Image-Height" to result.meta.optString("height")))
                }
                "POST /ack", "POST /cancel" -> {
                    val ok = camera.finish(identifier(body.getString("requestId")), identifier(body.getString("clientId")))
                    json(socket, if (ok) 200 else 410, JSONObject().put("ok", ok))
                }
                else -> json(socket, 404, JSONObject().put("error", "not_found"))
            }
        } catch (e: Exception) {
            Log.d("MobileLedger", "HTTP request ended: ${e.javaClass.simpleName}")
            try { json(socket, 400, JSONObject().put("error", "bad_request")) } catch (_: Exception) { }
        }
    }

    private fun json(socket: Socket, code: Int, json: JSONObject) = respond(socket, code, json.toString().toByteArray(), "application/json")
    private fun respond(socket: Socket, code: Int, bytes: ByteArray, type: String, extra: Map<String, String> = emptyMap()) {
        val reason = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 409 to "Conflict", 410 to "Gone", 500 to "Internal Server Error", 503 to "Service Unavailable")[code] ?: "Error"
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'\r\n")
            extra.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        // Send headers with the body; avoid a tiny packet followed by a delayed-ACK/Nagle wait.
        BufferedOutputStream(socket.getOutputStream(), 64 * 1024).apply {
            write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush()
        }
    }
}
