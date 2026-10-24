package dev.hram.androsaur.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import dev.hram.androsaur.camera.face.Embeddings
import dev.hram.androsaur.camera.face.Gallery
import dev.hram.androsaur.camera.face.Similarity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Teaches the camera who is who and recognizes people: the MediaPipe Face Landmarker finds the face and its points,
 * the face is turned onto the template of the recognition model, SFace turns it into 128 numbers and the numbers are
 * compared with the ones taught. Only numbers are stored; pictures never leave the frame.
 * Follows up to [MAX_FACES] faces at once (teaching takes exactly one), on the front camera, while a screen (or the API) has switched it on.
 */
class FaceId(private val context: Context, val store: PeopleStore) {
    enum class Mode { IDLE, ENROLL, TEST }

    /** The face seen now: a frame (fractions of the upright, unmirrored image, turned by [angle] degrees clockwise) and its points. */
    class Face(val x: Double, val y: Double, val w: Double, val h: Double, val angle: Double, val points: FloatArray,
               val yaw: Double?, val pitch: Double?, val roll: Double?)
    class Enroll(val name: String, val samples: Int, val target: Int, val poses: List<Pair<Double, Double>>, val hint: String, val ready: Boolean)
    class Who(val name: String?, val best: Gallery.Score?, val scores: List<Gallery.Score>, val at: Long)
    /** A face followed from frame to frame: [id] stays while it is in view, [who] is what it was last recognized as. */
    class Tracked(val id: Int, val face: Face, val who: Who?)
    class Snapshot(val mode: Mode, val state: String, val error: String?, val faces: List<Tracked>, val faceAgeMs: Long, val enroll: Enroll?,
                   val threshold: Double, val landmarkerMs: Double?, val embedMs: Double?, val fps: Double, val width: Int, val height: Int) {
        /** The biggest face (the closest person). */
        val primary: Tracked? get() = faces.maxByOrNull { it.face.w * it.face.h }
        val face: Face? get() = primary?.face
        val who: Who? get() = primary?.who
    }

    private class Track(val id: Int, var face: Face, var seenAt: Long, var who: Who? = null, var embeddedAt: Long = 0)

    private val lock = Any()
    private val prefs by lazy { context.getSharedPreferences("people", Context.MODE_PRIVATE) }
    private var landmarker: FaceLandmarker? = null
    private var embedder: FaceEmbedder? = null
    @Volatile private var state = "idle"
    private var error: String? = null

    @Volatile var mode = Mode.IDLE; private set
    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var faceAt = 0L
    private var enrollName = ""
    private val vectors = ArrayList<FloatArray>()
    private val poses = ArrayList<Pair<Double, Double>>()
    private var hint = ""
    private var lastSampleAt = 0L
    private var landmarkerMs: Double? = null
    private var embedMs: Double? = null
    private var fps = 0.0
    private var lastFrameAt = 0L
    private var size = 0 to 0

    var threshold: Double
        get() = prefs.getFloat("threshold", 0.40f).toDouble()
        set(value) { prefs.edit().putFloat("threshold", value.coerceIn(0.1, 0.9).toFloat()).apply() }

    fun setMode(next: Mode) = synchronized(lock) {
        mode = next
        if (next != Mode.ENROLL) { vectors.clear(); poses.clear(); enrollName = "" }
        tracks.clear(); hint = ""
    }

    /** Starts teaching [name] (an existing person gets more samples). Returns an error code or null. */
    fun startEnroll(name: String): String? = synchronized(lock) {
        if (!PeopleStore.valid(name)) return "invalid_name"
        enrollName = name; vectors.clear(); poses.clear(); lastSampleAt = 0; hint = "Смотрите в камеру"
        mode = Mode.ENROLL; tracks.clear()
        null
    }

    /** Saves the samples taken so far. Returns how many were saved, or -1 when there are too few to be useful. */
    fun finishEnroll(): Int = synchronized(lock) {
        if (mode != Mode.ENROLL || vectors.size < MIN_SAMPLES) return -1
        val count = vectors.size
        store.add(enrollName, vectors.toList())
        mode = Mode.IDLE; vectors.clear(); poses.clear(); enrollName = ""
        count
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        Snapshot(mode, state, error, tracks.filter { now - it.seenAt <= 1500 }.map { Tracked(it.id, it.face, it.who) }, if (faceAt == 0L) -1 else now - faceAt,
            if (mode == Mode.ENROLL) Enroll(enrollName, vectors.size, TARGET_SAMPLES, poses.toList(), hint, vectors.size >= MIN_SAMPLES) else null,
            threshold, landmarkerMs, embedMs, fps, size.first, size.second)
    }

    fun json(): JSONObject {
        val s = snapshot()
        val people = JSONArray(store.names().map { JSONObject().put("name", it).put("samples", store.samples(it)) })
        val json = JSONObject().put("mode", s.mode.name.lowercase()).put("state", s.state).put("threshold", s.threshold)
            .put("model", JSONObject().put("name", "SFace int8").put("dimensions", 128)).put("people", people)
            .put("landmarkerMs", s.landmarkerMs?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
            .put("embedMs", s.embedMs?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL).put("fps", Math.round(s.fps * 10) / 10.0)
        s.error?.let { json.put("error", it) }
        fun faceJson(f: Face) = JSONObject().put("x", r3(f.x)).put("y", r3(f.y)).put("w", r3(f.w)).put("h", r3(f.h)).put("angleDeg", Math.round(f.angle * 10) / 10.0)
            .put("pose", JSONObject().put("yaw", f.yaw ?: JSONObject.NULL).put("pitch", f.pitch ?: JSONObject.NULL).put("roll", f.roll ?: JSONObject.NULL))
        fun whoJson(w: Who?) = w?.let { JSONObject().put("name", it.name ?: JSONObject.NULL).put("ageMs", SystemClock.elapsedRealtime() - it.at)
            .put("best", it.best?.let { b -> JSONObject().put("name", b.name).put("similarity", r3(b.similarity)) } ?: JSONObject.NULL)
            .put("scores", JSONObject().also { o -> it.scores.forEach { sc -> o.put(sc.name, r3(sc.similarity)) } }) } ?: JSONObject.NULL
        // face and who describe the biggest face; faces lists everyone in view.
        json.put("face", s.face?.let { faceJson(it) } ?: JSONObject.NULL).put("who", whoJson(s.who))
        json.put("faces", JSONArray(s.faces.map { t -> faceJson(t.face).put("id", t.id).put("who", whoJson(t.who)) }))
        return json
    }

    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    fun close() = synchronized(lock) {
        landmarker?.close(); landmarker = null
        embedder?.close(); embedder = null
        state = "idle"
    }

    /** One analysis frame. The caller owns [image] and closes it. */
    fun analyze(image: ImageProxy) {
        if (mode == Mode.IDLE) return
        var raw: Bitmap? = null; var upright: Bitmap? = null
        try {
            ensureLoaded()
            val now = SystemClock.elapsedRealtime()
            val degrees = image.imageInfo.rotationDegrees
            raw = image.toBitmap()
            upright = if (degrees == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            val uw = upright.width; val uh = upright.height
            val began = SystemClock.elapsedRealtimeNanos()
            val result = landmarker!!.detect(BitmapImageBuilder(upright).build())
            val took = (SystemClock.elapsedRealtimeNanos() - began) / 1e6
            synchronized(lock) {
                landmarkerMs = smooth(landmarkerMs, took)
                if (lastFrameAt != 0L) fps = smooth(if (fps == 0.0) null else fps, 1000.0 / maxOf(1L, now - lastFrameAt))!!
                lastFrameAt = now; size = uw to uh
            }
            val all = result.faceLandmarks().filter { it.size >= 478 }
            if (all.isEmpty()) { lost(now); return }
            val matrices = result.facialTransformationMatrixes().orElse(null)
            val points = all.map { marks -> marks.map { it.x() to it.y() } }
            val seen = points.mapIndexed { i, p -> describe(p, matrices?.getOrNull(i)?.takeIf { it.size >= 16 }, uw, uh) }
            val followed = follow(seen, now)
            synchronized(lock) { faceAt = now }
            when (mode) {
                Mode.ENROLL ->
                    if (seen.size > 1) synchronized(lock) { hint = "В кадре должен быть один человек" }
                    else enrolStep(seen[0], upright, points[0], now)
                Mode.TEST -> testStep(followed, points, upright, now)
                Mode.IDLE -> {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Face recognition step failed", e)
            synchronized(lock) { state = "failed"; error = "${e.javaClass.simpleName}: ${e.message}".take(160) }
        } finally {
            if (upright !== raw) upright?.recycle()
            raw?.recycle()
        }
    }

    private fun smooth(old: Double?, value: Double) = if (old == null) value else old * 0.8 + value * 0.2

    private fun ensureLoaded() {
        if (landmarker != null && embedder != null) return
        synchronized(lock) { state = "loading"; error = null }
        embedder = embedder ?: FaceEmbedder(context)
        landmarker = landmarker ?: FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker/face_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE).setNumFaces(MAX_FACES).setMinFaceDetectionConfidence(0.5f)
            .setOutputFaceBlendshapes(false).setOutputFacialTransformationMatrixes(true).build())
        synchronized(lock) { state = "ready" }
    }

    private fun lost(now: Long) = synchronized(lock) {
        tracks.removeAll { now - it.seenAt > FORGET_AFTER_MS }
        if (mode == Mode.ENROLL) hint = "Лицо не найдено. Держите телефон на уровне глаз"
    }

    /** Matches the faces of this frame to the ones followed before by the nearest centre; a face with no match starts a new track. */
    private fun follow(faces: List<Face>, now: Long): List<Track> = synchronized(lock) {
        val free = ArrayList(tracks)
        val matched = faces.map { f ->
            val cx = f.x + f.w / 2; val cy = f.y + f.h / 2
            val near = free.minByOrNull { hypot(it.face.x + it.face.w / 2 - cx, it.face.y + it.face.h / 2 - cy) }
                ?.takeIf { hypot(it.face.x + it.face.w / 2 - cx, it.face.y + it.face.h / 2 - cy) < FOLLOW_DISTANCE }
            if (near != null) { free.remove(near); near.face = f; near.seenAt = now; near } else Track(nextId++, f, now).also { tracks.add(it) }
        }
        tracks.removeAll { now - it.seenAt > FORGET_AFTER_MS }
        matched
    }

    /** The frame of the face (fitted in the tilted frame of the eyes, as in the lab), and its head pose from the transformation matrix. */
    private fun describe(marks: List<Pair<Float, Float>>, m: FloatArray?, uw: Int, uh: Int): Face {
        val (ax, ay) = marks[33]; val (bx, by) = marks[263]
        val (lx, ly, rx, ry) = if (ax <= bx) listOf(ax, ay, bx, by) else listOf(bx, by, ax, ay)
        val tilt = Math.toDegrees(atan2((ry - ly).toDouble() * uh, (rx - lx).toDouble() * uw))
        val t = Math.toRadians(tilt); val c = cos(t); val sn = sin(t)
        var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE; var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
        for ((x, y) in marks) {
            val px = x * uw.toDouble(); val py = y * uh.toDouble()
            val u = px * c + py * sn; val v = -px * sn + py * c
            if (u < minU) minU = u; if (u > maxU) maxU = u; if (v < minV) minV = v; if (v > maxV) maxV = v
        }
        val cu = (minU + maxU) / 2; val cv = (minV + maxV) / 2
        val cx = (cu * c - cv * sn) / uw; val cy = (cu * sn + cv * c) / uh
        val w = (maxU - minU) / uw; val h = (maxV - minV) / uh
        val points = FloatArray(marks.size * 2) { if (it % 2 == 0) marks[it / 2].first else marks[it / 2].second }
        var pitch: Double? = null; var yaw: Double? = null; var roll: Double? = null
        if (m != null) {
            pitch = Math.toDegrees(atan2(m[6].toDouble(), m[10].toDouble())); yaw = Math.toDegrees(asin((-m[2]).toDouble().coerceIn(-1.0, 1.0))); roll = Math.toDegrees(atan2(m[1].toDouble(), m[0].toDouble()))
        }
        return Face(cx - w / 2, cy - h / 2, w, h, tilt, points, yaw, pitch, roll)
    }

    /** The face turned and scaled onto the template of the model: five points (pupils, nose tip, mouth corners). */
    private fun align(source: Bitmap, marks: List<Pair<Float, Float>>): Bitmap {
        val uw = source.width.toDouble(); val uh = source.height.toDouble()
        fun p(i: Int) = doubleArrayOf(marks[i].first * uw, marks[i].second * uh)
        fun pair(a: Int, b: Int): List<DoubleArray> = listOf(p(a), p(b)).sortedBy { it[0] }   // the one on the left of the picture first
        val eyes = pair(468, 473); val mouth = pair(61, 291); val nose = p(1)
        val from = doubleArrayOf(eyes[0][0], eyes[0][1], eyes[1][0], eyes[1][1], nose[0], nose[1], mouth[0][0], mouth[0][1], mouth[1][0], mouth[1][1])
        val fit = Similarity.fit(from, TEMPLATE)
        val out = Bitmap.createBitmap(FaceEmbedder.SIZE, FaceEmbedder.SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, Matrix().apply { setValues(fit.values()) }, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun embedOf(source: Bitmap, marks: List<Pair<Float, Float>>): FloatArray {
        val aligned = align(source, marks)
        try {
            val began = SystemClock.elapsedRealtimeNanos()
            val v = embedder!!.embed(aligned)
            val took = (SystemClock.elapsedRealtimeNanos() - began) / 1e6
            synchronized(lock) { embedMs = smooth(embedMs, took) }
            return v
        } finally { aligned.recycle() }
    }

    private fun enrolStep(f: Face, source: Bitmap, marks: List<Pair<Float, Float>>, now: Long) {
        val yaw = f.yaw; val pitch = f.pitch
        val verdict: String? = synchronized(lock) {
            when {
                vectors.size >= TARGET_SAMPLES -> "Готово. Нажмите «Сохранить»"
                f.w < MIN_FACE_WIDTH -> "Подойдите ближе или поднесите телефон"
                yaw == null || pitch == null -> "Не удаётся определить поворот головы"
                abs(yaw) > MAX_YAW || abs(pitch) > MAX_PITCH -> "Смотрите почти прямо в камеру"
                now - lastSampleAt < MIN_GAP_MS -> null
                poses.any { hypot(it.first - yaw, it.second - pitch) < MIN_POSE_STEP } -> "Чуть измените поворот или наклон головы"
                else -> null
            }
        }
        if (verdict != null) { synchronized(lock) { hint = verdict }; return }
        if (now - lastSampleAt < MIN_GAP_MS || yaw == null || pitch == null) return
        val v = embedOf(source, marks)
        synchronized(lock) {
            val first = vectors.firstOrNull()
            if (first != null && Embeddings.cosine(first, v) < SAME_PERSON_FLOOR) { hint = "В кадре другой человек? Снимок не принят"; return }
            vectors.add(v); poses.add(yaw to pitch); lastSampleAt = now
            hint = if (vectors.size >= TARGET_SAMPLES) "Готово. Нажмите «Сохранить»" else "Хорошо. Медленно двигайте головой: влево, вправо, вверх, вниз"
        }
    }

    /** At most one face per frame is recognized: a new face first, then the one recognized longest ago. */
    private fun testStep(tracked: List<Track>, points: List<List<Pair<Float, Float>>>, source: Bitmap, now: Long) {
        val due = tracked.indices.filter { i ->
            val t = tracked[i]; val f = t.face
            f.w >= MIN_FACE_WIDTH * 0.8 && (f.yaw == null || abs(f.yaw) <= 55) && (t.who == null || now - t.embeddedAt >= REEMBED_MS)
        }.minByOrNull { tracked[it].embeddedAt } ?: return
        val v = embedOf(source, points[due])
        val decision = store.gallery().decide(v, threshold)
        synchronized(lock) { tracked[due].who = Who(decision.name, decision.best, decision.scores, SystemClock.elapsedRealtime()); tracked[due].embeddedAt = now }
    }

    private companion object {
        const val TAG = "AndrosaurCamera"
        const val TARGET_SAMPLES = 12
        const val MIN_SAMPLES = 5
        const val MIN_FACE_WIDTH = 0.18
        const val MAX_YAW = 40.0
        const val MAX_PITCH = 35.0
        const val MIN_POSE_STEP = 7.0
        const val MIN_GAP_MS = 400L
        const val SAME_PERSON_FLOOR = 0.25
        const val REEMBED_MS = 1500L
        const val FOLLOW_DISTANCE = 0.2
        const val MAX_FACES = 3
        const val FORGET_AFTER_MS = 1500L
        /** Where the model expects the pupils, nose tip and mouth corners in its 112x112 input (the usual ArcFace template). */
        val TEMPLATE = doubleArrayOf(38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041)
    }
}
