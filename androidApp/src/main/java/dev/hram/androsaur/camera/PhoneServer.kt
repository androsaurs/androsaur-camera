package dev.hram.androsaur.camera

import android.util.Log
import dev.hram.androsaur.camera.lens.Lens
import dev.hram.androsaur.camera.store.CaptureStoreLedger
import org.json.JSONArray
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
    private val mcp = McpEndpoint(camera)
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
                } catch (e: Exception) { if (!closed) Log.w("AndrosaurCamera", "HTTP accept failed", e) }
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
            require(parts.size == 3 && parts[0] in setOf("GET", "POST", "DELETE"))
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
            if (method == "GET" && uri.path.startsWith("/dl/")) { serveLink(socket, uri.path.removePrefix("/dl/"), headers["range"]); return }
            val supplied = headers["authorization"]?.removePrefix("Bearer ") ?: ""
            if (!MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) {
                json(socket, 401, JSONObject().put("error", "unauthorized")); return
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 0..16384 && "transfer-encoding" !in headers)
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
                    val frame = camera.frameSnapshot()
                    if (frame == null) json(socket, 202, JSONObject().put("state", "warming_up"))
                    else respond(socket, 200, frame.jpeg, "image/jpeg", mapOf("X-Frame-Id" to frame.id.toString(),
                        "X-Frame-Age-Ms" to (android.os.SystemClock.elapsedRealtime() - frame.capturedAt).toString()))
                }
                "POST /mcp" -> {
                    // Browsers always send Origin; MCP clients do not. Refusing it blocks DNS-rebinding and cross-site calls.
                    if ("origin" in headers) { json(socket, 403, JSONObject().put("error", "origin_not_allowed")); return }
                    val reply = mcp.handle(body, headers["host"])
                    if (reply == null) respond(socket, 202, ByteArray(0), "application/json") else json(socket, 200, reply)
                }
                "GET /mcp", "DELETE /mcp" -> respond(socket, 405, ByteArray(0), "application/json", mapOf("Allow" to "POST"))
                "POST /torch" -> {
                    val ok = camera.setTorch(body.getBoolean("enabled"))
                    json(socket, if (ok) 200 else 400, if (ok) JSONObject().put("ok", true) else JSONObject().put("error", "torch_unavailable"))
                }
                "POST /camera" -> {
                    val wanted = Lens.parse(body.optString("lens"))
                    if (wanted == null) json(socket, 400, JSONObject().put("error", "invalid_lens").put("lenses", JSONArray().put("back").put("front")))
                    else { val (code, value) = camera.setLens(wanted); json(socket, code, value) }
                }
                "GET /people", "GET /who" -> json(socket, 200, camera.faceId.json())
                "POST /people/rename" -> {
                    val problem = camera.faceId.store.rename(body.getString("name"), body.getString("to").trim())
                    json(socket, if (problem == null) 200 else if (problem == "unknown_person") 404 else 409, if (problem == null) JSONObject().put("ok", true) else JSONObject().put("error", problem))
                }
                "POST /people/forget" -> {
                    val ok = camera.faceId.store.forget(body.getString("name"))
                    json(socket, if (ok) 200 else 404, JSONObject().put(if (ok) "ok" else "error", if (ok) true else "unknown_person"))
                }
                "GET /faces" -> json(socket, 200, camera.setFaceLab(null, false).second)
                "POST /faces" -> {
                    val (code, value) = camera.setFaceLab(if (body.has("enabled")) body.getBoolean("enabled") else null, body.optBoolean("reset", false),
                        body.optString("mode").ifEmpty { null }, body.optJSONArray("detectors")?.let { list -> (0 until list.length()).map { list.getString(it) } })
                    json(socket, code, value)
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
            Log.d("AndrosaurCamera", "HTTP request ended: ${e.javaClass.simpleName}")
            try { json(socket, 400, JSONObject().put("error", "bad_request")) } catch (_: Exception) { }
        }
    }

    /** Download links carry their own unguessable token instead of the Bearer code; they expire and may be one-time. */
    private fun serveLink(socket: Socket, token: String, range: String?) {
        if (!token.matches(Regex("[0-9a-f]{32}"))) { json(socket, 404, JSONObject().put("error", "not_found")); return }
        val download = camera.agentStore.openLink(token, range)
        when (val outcome = download.outcome) {
            CaptureStoreLedger.LinkOutcome.Invalid -> json(socket, 404, JSONObject().put("error", "not_found"))
            CaptureStoreLedger.LinkOutcome.Expired -> json(socket, 410, JSONObject().put("error", "expired"))
            is CaptureStoreLedger.LinkOutcome.Unsatisfiable ->
                respond(socket, 416, ByteArray(0), "application/json", mapOf("Content-Range" to "bytes */${outcome.total}"))
            is CaptureStoreLedger.LinkOutcome.Served -> {
                val extra = mutableMapOf("ETag" to "\"${outcome.entry.sha256}\"", "Accept-Ranges" to "bytes",
                    "Content-Disposition" to "attachment; filename=\"${outcome.entry.handle}.jpg\"")
                if (outcome.partial) extra["Content-Range"] = "bytes ${outcome.start}-${outcome.end}/${outcome.entry.bytes}"
                respond(socket, if (outcome.partial) 206 else 200, download.bytes ?: ByteArray(0), "image/jpeg", extra)
            }
        }
    }

    private fun json(socket: Socket, code: Int, json: JSONObject) = respond(socket, code, json.toString().toByteArray(), "application/json")
    private fun respond(socket: Socket, code: Int, bytes: ByteArray, type: String, extra: Map<String, String> = emptyMap()) {
        val reason = mapOf(200 to "OK", 202 to "Accepted", 206 to "Partial Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 410 to "Gone", 416 to "Range Not Satisfiable", 500 to "Internal Server Error", 503 to "Service Unavailable")[code] ?: "Error"
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
