package dev.hram.androsaur.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CaptureResult
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector as MediaPipeFaceDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Experiment bench for face detectors on the front camera: the same analysis frames go through ML Kit and
 * MediaPipe BlazeFace one after another, while the camera chip's own face detection (Camera2 SIMPLE mode)
 * is read from capture results. Only counts, times and normalized boxes are kept; no frame leaves the phone.
 * Boxes are fractions 0-1 of the upright, unmirrored image (x to the right, y downwards).
 */
class FaceLab(private val context: Context) {
    class Box(val x: Double, val y: Double, val w: Double, val h: Double, val score: Double?)

    /** What the screen draws for one detector right now. Boxes are fractions of the upright, unmirrored image. */
    class LiveDetector(val name: String, val state: String, val boxes: List<Box>, val avgMs: Double?, val fps: Double, val ageMs: Long, val withFacePercent: Int)
    class Live(val mode: String, val width: Int, val height: Int, val detectors: List<LiveDetector>)

    private class Stats {
        var frames = 0L; var withFace = 0L; var faces = 0L
        var convertNanos = 0L
        val nanos = LongArray(1024); var count = 0
        var loadMs = 0L; var state = "idle"; var error: String? = null
        var last: List<Box> = emptyList()
        var lastAt = 0L
        fun add(elapsed: Long, found: List<Box>) {
            nanos[count++ % nanos.size] = elapsed
            frames++; faces += found.size; if (found.isNotEmpty()) withFace++
            last = found; lastAt = SystemClock.elapsedRealtime()
        }
        fun clear() { frames = 0; withFace = 0; faces = 0; convertNanos = 0; count = 0; nanos.fill(0); last = emptyList(); lastAt = 0 }
        fun recentMs(): Double? = if (count == 0) null else {
            val n = minOf(count, 20)
            (1 until n + 1).sumOf { nanos[(count - it) % nanos.size] } / n / 1e6
        }
    }

    /** What a detector is, independent of the mode: the state does not reset when you switch. */
    private class Info { var state = "idle"; var loadMs = 0L; var error: String? = null }
    private val infoHw = Info(); private val infoMl = Info(); private val infoMp = Info()

    /** Counters of one mode; modes keep their numbers when you switch away, so a session can be read afterwards. */
    private class Bucket {
        val hardware = Stats(); val mlkit = Stats(); val mediapipe = Stats()
        var analysisFrames = 0L
        var activeMs = 0L
        var since = 0L
        fun clear() { hardware.clear(); mlkit.clear(); mediapipe.clear(); analysisFrames = 0; activeMs = 0; if (since != 0L) since = SystemClock.elapsedRealtime() }
        fun elapsed() = activeMs + if (since != 0L) SystemClock.elapsedRealtime() - since else 0L
    }

    private val lock = Any()
    /** Mode A: only the camera chip's detector. B: + ML Kit. C: + MediaPipe. D: all three. The chip's one costs nothing and always reports. */
    private val buckets = linkedMapOf("A" to Bucket(), "B" to Bucket(), "C" to Bucket(), "D" to Bucket())
    @Volatile var mode = "D"; private set
    private var running = false
    private var cpuStart = Process.getElapsedCpuTime()
    private fun bucket() = buckets.getValue(mode)
    val software: Set<String> get() = when (mode) { "B" -> setOf("mlkit"); "C" -> setOf("mediapipe"); "D" -> setOf("mlkit", "mediapipe"); else -> emptySet() }
    private val mlkit get() = bucket().mlkit
    private val mediapipe get() = bucket().mediapipe
    private val hardware get() = bucket().hardware
    @Volatile private var rotation = 0
    private var upright = 0 to 0
    @Volatile private var activeW = 0
    @Volatile private var activeH = 0
    private var mlkitDetector: com.google.mlkit.vision.face.FaceDetector? = null
    private var mediapipeDetector: MediaPipeFaceDetector? = null

    /** Clears the counters of every mode (or of one). */
    fun reset(only: String? = null) = synchronized(lock) {
        buckets.forEach { (name, b) -> if (only == null || only == name) b.clear() }
        cpuStart = Process.getElapsedCpuTime()
    }

    /** Switches the mode; the numbers of the mode left behind are kept. Returns false for an unknown mode. */
    fun switchTo(next: String): Boolean = synchronized(lock) {
        if (next !in buckets) return false
        if (next == mode) return true
        val now = SystemClock.elapsedRealtime()
        bucket().let { if (it.since != 0L) { it.activeMs += now - it.since; it.since = 0 } }
        mode = next
        if (running) bucket().since = now
        true
    }

    /** The screen or the API starts and stops the lab; time only counts while it runs. */
    fun setRunning(on: Boolean) = synchronized(lock) {
        if (on == running) return@synchronized
        running = on
        val now = SystemClock.elapsedRealtime()
        bucket().let { if (on) it.since = now else if (it.since != 0L) { it.activeMs += now - it.since; it.since = 0 } }
    }

    fun close() {
        mlkitDetector?.close(); mlkitDetector = null
        mediapipeDetector?.close(); mediapipeDetector = null
        infoMl.state = "idle"; infoMp.state = "idle"
    }

    /** Active array of the sensor, needed to turn the chip's rectangles into fractions. */
    fun setActiveArray(width: Int, height: Int) { activeW = width; activeH = height }

    /** From the Camera2 session callback: the faces the camera chip found in this exposure. */
    fun onCameraResult(result: CaptureResult) {
        val faces = result.get(CaptureResult.STATISTICS_FACES) ?: return
        if (activeW == 0 || activeH == 0) return
        val boxes = faces.map { f ->
            val b = f.bounds
            val fx = b.left.toDouble() / activeW; val fy = b.top.toDouble() / activeH
            val fw = b.width().toDouble() / activeW; val fh = b.height().toDouble() / activeH
            val (x, y, w, h) = turn(fx, fy, fw, fh, rotation)
            Box(x, y, w, h, f.score.toDouble() / 100)
        }
        synchronized(lock) { infoHw.state = "ready"; hardware.add(0, boxes) }
    }

    /** Rotates a box given in sensor fractions clockwise by [degrees], as the image is turned upright. */
    private fun turn(x: Double, y: Double, w: Double, h: Double, degrees: Int): List<Double> = when (degrees) {
        90 -> listOf(1 - y - h, x, h, w)
        180 -> listOf(1 - x - w, 1 - y - h, w, h)
        270 -> listOf(y, 1 - x - w, h, w)
        else -> listOf(x, y, w, h)
    }

    /** One analysis frame through both software detectors. The caller keeps ownership of [image] and closes it. */
    fun analyze(image: ImageProxy) {
        val degrees = image.imageInfo.rotationDegrees
        rotation = degrees
        val uw = if (degrees % 180 == 0) image.width else image.height
        val uh = if (degrees % 180 == 0) image.height else image.width
        synchronized(lock) { bucket().analysisFrames++; upright = uw to uh }
        if ("mlkit" in software) runMlKit(image, degrees, uw, uh)
        if ("mediapipe" in software) runMediaPipe(image, degrees, uw, uh)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun runMlKit(image: ImageProxy, degrees: Int, uw: Int, uh: Int) {
        val media = image.image ?: return
        try {
            if (mlkitDetector == null) {
                val began = SystemClock.elapsedRealtime()
                mlkitDetector = FaceDetection.getClient(FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE).build())
                synchronized(lock) { infoMl.loadMs = SystemClock.elapsedRealtime() - began; infoMl.state = "ready" }
            }
            val started = SystemClock.elapsedRealtimeNanos()
            val faces = Tasks.await(mlkitDetector!!.process(InputImage.fromMediaImage(media, degrees)))
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            val boxes = faces.map { f -> f.boundingBox.let { Box(it.left.toDouble() / uw, it.top.toDouble() / uh, it.width().toDouble() / uw, it.height().toDouble() / uh, null) } }
            synchronized(lock) { mlkit.add(elapsed, boxes) }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit face detection failed", e)
            synchronized(lock) { infoMl.state = "failed"; infoMl.error = "${e.javaClass.simpleName}: ${e.message}".take(160) }
        }
    }

    private fun runMediaPipe(image: ImageProxy, degrees: Int, uw: Int, uh: Int) {
        try {
            if (mediapipeDetector == null) {
                val began = SystemClock.elapsedRealtime()
                mediapipeDetector = MediaPipeFaceDetector.createFromOptions(context, MediaPipeFaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_detector/blaze_face_short_range.tflite").build())
                    .setRunningMode(RunningMode.IMAGE).setMinDetectionConfidence(0.5f).build())
                synchronized(lock) { infoMp.loadMs = SystemClock.elapsedRealtime() - began; infoMp.state = "ready" }
            }
            val converting = SystemClock.elapsedRealtimeNanos()
            val raw = image.toBitmap()
            val turned = if (degrees == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            val converted = SystemClock.elapsedRealtimeNanos() - converting
            val started = SystemClock.elapsedRealtimeNanos()
            val result = mediapipeDetector!!.detect(BitmapImageBuilder(turned).build())
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            val boxes = result.detections().map { d -> d.boundingBox().let { Box(it.left.toDouble() / uw, it.top.toDouble() / uh, it.width().toDouble() / uw, it.height().toDouble() / uh,
                d.categories().firstOrNull()?.score()?.toDouble()) } }
            if (turned !== raw) raw.recycle(); turned.recycle()
            synchronized(lock) { mediapipe.convertNanos += converted; mediapipe.add(elapsed, boxes) }
        } catch (e: Exception) {
            Log.w(TAG, "MediaPipe face detection failed", e)
            synchronized(lock) { infoMp.state = "failed"; infoMp.error = "${e.javaClass.simpleName}: ${e.message}".take(160) }
        }
    }

    fun json(): JSONObject = synchronized(lock) {
        val cpuMs = Process.getElapsedCpuTime() - cpuStart
        val json = summary(bucket()).put("mode", mode).put("running", running)
            .put("analysisSize", JSONObject().put("width", upright.first).put("height", upright.second))
            .put("rotationDegrees", rotation).put("software", JSONArray(software.sorted()))
            .put("processCpuPercentOfOneCore", Math.round(cpuMs * 1000.0 / maxOf(1L, SystemClock.elapsedRealtime() - cpuWindowStart())) / 10.0)
        val all = JSONObject()
        buckets.forEach { (name, b) -> all.put(name, summary(b)) }
        json.put("modes", all)
    }

    private fun cpuWindowStart() = SystemClock.elapsedRealtime() - maxOf(1L, buckets.values.sumOf { it.elapsed() })

    private fun summary(b: Bucket): JSONObject {
        val windowMs = b.elapsed()
        return JSONObject().put("windowMs", windowMs).put("analysisFrames", b.analysisFrames)
            .put("analysisFps", if (windowMs > 0) Math.round(b.analysisFrames * 10000.0 / windowMs) / 10.0 else 0.0)
            .put("hardware", describe(b.hardware, infoHw, windowMs, timed = false))
            .put("mlkit", describe(b.mlkit, infoMl, windowMs, timed = true))
            .put("mediapipe", describe(b.mediapipe, infoMp, windowMs, timed = true))
    }

    /** For the screen: the newest boxes and figures of each detector in the current mode. */
    fun live(): Live = synchronized(lock) {
        val b = bucket()
        val now = SystemClock.elapsedRealtime()
        val windowMs = b.elapsed()
        fun one(name: String, s: Stats, info: Info, active: Boolean) = LiveDetector(name, if (active) info.state else "off", if (active) s.last else emptyList(),
            if (active && name != "hardware") s.recentMs() else null, if (windowMs > 0) s.frames * 1000.0 / windowMs else 0.0,
            if (s.lastAt == 0L) -1 else now - s.lastAt, if (s.frames > 0) (100 * s.withFace / s.frames).toInt() else 0)
        Live(mode, upright.first, upright.second, listOf(
            one("hardware", b.hardware, infoHw, true), one("mlkit", b.mlkit, infoMl, "mlkit" in software), one("mediapipe", b.mediapipe, infoMp, "mediapipe" in software)))
    }

    private fun describe(s: Stats, info: Info, windowMs: Long, timed: Boolean): JSONObject {
        val json = JSONObject().put("state", info.state).put("frames", s.frames).put("framesWithFace", s.withFace).put("faces", s.faces)
            .put("framesPerSecond", if (windowMs > 0) Math.round(s.frames * 10000.0 / windowMs) / 10.0 else 0.0)
        if (info.loadMs > 0) json.put("loadMs", info.loadMs)
        info.error?.let { json.put("error", it) }
        if (timed && s.count > 0) {
            val n = minOf(s.count, s.nanos.size)
            val sorted = s.nanos.copyOf(n).also { it.sort() }
            fun ms(nanos: Long) = Math.round(nanos / 1e4) / 100.0
            json.put("ms", JSONObject().put("avg", ms(sorted.sum() / n)).put("p50", ms(sorted[n / 2])).put("p95", ms(sorted[(n * 95 / 100).coerceAtMost(n - 1)])).put("max", ms(sorted[n - 1])))
            if (s.convertNanos > 0) json.put("convertMsAvg", Math.round(s.convertNanos / s.frames / 1e4) / 100.0)
        }
        json.put("lastBoxes", JSONArray(s.last.map { b ->
            JSONObject().put("x", r3(b.x)).put("y", r3(b.y)).put("w", r3(b.w)).put("h", r3(b.h)).also { o -> b.score?.let { o.put("score", r3(it)) } }
        }))
        return json
    }

    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    private companion object { const val TAG = "AndrosaurCamera" }
}
