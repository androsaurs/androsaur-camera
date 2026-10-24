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
    private class Box(val x: Double, val y: Double, val w: Double, val h: Double, val score: Double?)

    private class Stats {
        var frames = 0L; var withFace = 0L; var faces = 0L
        var convertNanos = 0L
        val nanos = LongArray(1024); var count = 0
        var loadMs = 0L; var state = "idle"; var error: String? = null
        var last: List<Box> = emptyList()
        fun add(elapsed: Long, found: List<Box>) {
            nanos[count++ % nanos.size] = elapsed
            frames++; faces += found.size; if (found.isNotEmpty()) withFace++
            last = found
        }
    }

    private val lock = Any()
    private val mlkit = Stats(); private val mediapipe = Stats(); private val hardware = Stats()
    private var windowStart = SystemClock.elapsedRealtime()
    private var cpuStart = Process.getElapsedCpuTime()
    private var analysisFrames = 0L
    /** Which software detectors run on each frame; the hardware one costs nothing and always reports while the lab is on. */
    @Volatile var software: Set<String> = setOf("mlkit", "mediapipe")
    @Volatile private var rotation = 0
    private var upright = 0 to 0
    @Volatile private var activeW = 0
    @Volatile private var activeH = 0
    private var mlkitDetector: com.google.mlkit.vision.face.FaceDetector? = null
    private var mediapipeDetector: MediaPipeFaceDetector? = null

    fun reset() = synchronized(lock) {
        listOf(mlkit, mediapipe, hardware).forEach { s ->
            s.frames = 0; s.withFace = 0; s.faces = 0; s.convertNanos = 0; s.count = 0; s.nanos.fill(0); s.last = emptyList()
        }
        analysisFrames = 0; windowStart = SystemClock.elapsedRealtime(); cpuStart = Process.getElapsedCpuTime()
    }

    fun close() {
        mlkitDetector?.close(); mlkitDetector = null
        mediapipeDetector?.close(); mediapipeDetector = null
        mlkit.state = "idle"; mediapipe.state = "idle"
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
        synchronized(lock) { hardware.state = "ready"; hardware.add(0, boxes) }
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
        synchronized(lock) { analysisFrames++; upright = uw to uh }
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
                synchronized(lock) { mlkit.loadMs = SystemClock.elapsedRealtime() - began; mlkit.state = "ready" }
            }
            val started = SystemClock.elapsedRealtimeNanos()
            val faces = Tasks.await(mlkitDetector!!.process(InputImage.fromMediaImage(media, degrees)))
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            val boxes = faces.map { f -> f.boundingBox.let { Box(it.left.toDouble() / uw, it.top.toDouble() / uh, it.width().toDouble() / uw, it.height().toDouble() / uh, null) } }
            synchronized(lock) { mlkit.add(elapsed, boxes) }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit face detection failed", e)
            synchronized(lock) { mlkit.state = "failed"; mlkit.error = "${e.javaClass.simpleName}: ${e.message}".take(160) }
        }
    }

    private fun runMediaPipe(image: ImageProxy, degrees: Int, uw: Int, uh: Int) {
        try {
            if (mediapipeDetector == null) {
                val began = SystemClock.elapsedRealtime()
                mediapipeDetector = MediaPipeFaceDetector.createFromOptions(context, MediaPipeFaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_detector/blaze_face_short_range.tflite").build())
                    .setRunningMode(RunningMode.IMAGE).setMinDetectionConfidence(0.5f).build())
                synchronized(lock) { mediapipe.loadMs = SystemClock.elapsedRealtime() - began; mediapipe.state = "ready" }
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
            synchronized(lock) { mediapipe.state = "failed"; mediapipe.error = "${e.javaClass.simpleName}: ${e.message}".take(160) }
        }
    }

    fun json(): JSONObject = synchronized(lock) {
        val windowMs = SystemClock.elapsedRealtime() - windowStart
        val cpuMs = Process.getElapsedCpuTime() - cpuStart
        JSONObject().put("windowMs", windowMs).put("analysisFrames", analysisFrames)
            .put("analysisFps", if (windowMs > 0) Math.round(analysisFrames * 10000.0 / windowMs) / 10.0 else 0.0)
            .put("analysisSize", JSONObject().put("width", upright.first).put("height", upright.second))
            .put("rotationDegrees", rotation).put("software", JSONArray(software.sorted()))
            .put("processCpuPercentOfOneCore", if (windowMs > 0) Math.round(cpuMs * 1000.0 / windowMs) / 10.0 else 0.0)
            .put("hardware", describe(hardware, windowMs, timed = false))
            .put("mlkit", describe(mlkit, windowMs, timed = true))
            .put("mediapipe", describe(mediapipe, windowMs, timed = true))
    }

    private fun describe(s: Stats, windowMs: Long, timed: Boolean): JSONObject {
        val json = JSONObject().put("state", s.state).put("frames", s.frames).put("framesWithFace", s.withFace).put("faces", s.faces)
            .put("framesPerSecond", if (windowMs > 0) Math.round(s.frames * 10000.0 / windowMs) / 10.0 else 0.0)
        if (s.loadMs > 0) json.put("loadMs", s.loadMs)
        s.error?.let { json.put("error", it) }
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
