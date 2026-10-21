package dev.hram.mobileledger.calibration

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

enum class CalibrationCorner { TL, TR, BR, BL;
    val payload: String get() = "mobile-ledger:calibration:v1:$name"
    companion object {
        fun parse(value: String?): CalibrationCorner? = entries.firstOrNull { it.payload == value }
    }
}

data class CalibrationPoint(val x: Double, val y: Double) {
    operator fun minus(other: CalibrationPoint) = CalibrationPoint(x - other.x, y - other.y)
    fun cross(other: CalibrationPoint) = x * other.y - y * other.x
    fun length() = hypot(x, y)
}

data class CalibrationReading(
    // Positive values mean lift the right/bottom edge of the screen respectively.
    val liftRight: Double,
    val liftBottom: Double,
    val error: Double,
    val aligned: Boolean,
)

object CalibrationGeometry {
    const val TOLERANCE = 0.01

    /** Projective centre: averaging QR corners would bias the result under perspective. */
    fun centre(corners: List<CalibrationPoint>): CalibrationPoint? {
        if (corners.size != 4) return null
        val diagonal = corners[2] - corners[0]
        val other = corners[3] - corners[1]
        val determinant = diagonal.cross(other)
        if (abs(determinant) < 1e-10) return null
        val t = (corners[1] - corners[0]).cross(other) / determinant
        if (t !in 0.0..1.0) return null
        return CalibrationPoint(corners[0].x + t * diagonal.x, corners[0].y + t * diagonal.y)
    }

    /** Coordinates are in the upright, unmirrored camera image, normalised to its size. */
    fun evaluate(markers: Map<CalibrationCorner, CalibrationPoint>, aspect: Double, tolerance: Double = TOLERANCE): CalibrationReading? {
        if (markers.size != 4 || !aspect.isFinite() || aspect <= 0 || !tolerance.isFinite() || tolerance <= 0) return null
        val points = CalibrationCorner.entries.map { corner ->
            val point = markers[corner] ?: return null
            if (!point.x.isFinite() || !point.y.isFinite() || point.x !in 0.0..1.0 || point.y !in 0.0..1.0) return null
            CalibrationPoint(point.x * aspect, point.y)
        }
        val edges = points.indices.map { points[(it + 1) % 4] - points[it] }
        if (edges.any { it.length() < 0.06 }) return null
        val turns = edges.indices.map { edges[it].cross(edges[(it + 1) % 4]) }
        if (!(turns.all { it > 1e-5 } || turns.all { it < -1e-5 })) return null
        val area = abs(points.indices.sumOf { points[it].cross(points[(it + 1) % 4]) }) / 2
        if (area / aspect < 0.025) return null
        val (p0, p1, p2, p3) = points
        val dx1 = p1.x - p2.x; val dx2 = p3.x - p2.x
        val dy1 = p1.y - p2.y; val dy2 = p3.y - p2.y
        val sx = p0.x - p1.x + p2.x - p3.x
        val sy = p0.y - p1.y + p2.y - p3.y
        val determinant = dx1 * dy2 - dx2 * dy1
        if (abs(determinant) < 1e-8) return null
        val g = (sx * dy2 - dx2 * sy) / determinant
        val h = (dx1 * sy - sx * dy1) / determinant
        if (listOf(1.0, 1 + g, 1 + h, 1 + g + h).any { it <= 0.05 }) return null
        val a = p1.x - p0.x + g * p1.x
        val b = p3.x - p0.x + h * p3.x
        val d = p1.y - p0.y + g * p1.y
        val e = p3.y - p0.y + h * p3.y
        // Last row of the inverse homography: inverse depth is affine in image coordinates.
        val nx = d * h - e * g
        val ny = b * g - a * h
        val c = a * e - b * d
        val centreX = points.sumOf { it.x } / 4
        val centreY = points.sumOf { it.y } / 4
        val depth = nx * centreX + ny * centreY + c
        if (abs(depth) < 1e-9) return null
        val spanX = points.maxOf { it.x } - points.minOf { it.x }
        val spanY = points.maxOf { it.y } - points.minOf { it.y }
        val right = nx / depth * spanX
        val bottom = ny / depth * spanY
        val angleError = edges.indices.maxOf { i ->
            val first = edges[i]; val second = edges[(i + 1) % 4]
            abs(first.x * second.x + first.y * second.y) / (first.length() * second.length())
        }
        val error = max(max(abs(right), abs(bottom)), angleError)
        return CalibrationReading(right, bottom, error, error <= tolerance)
    }
}

/** Uses elapsed time, never a count of potentially irregular camera frames. */
class CalibrationStability {
    private var alignedSince: Long? = null
    private var previousTime: Long? = null
    fun reset() { alignedSince = null; previousTime = null }
    fun update(reading: CalibrationReading?, now: Long): Boolean {
        if (previousTime?.let { now - it > 600 || now < it } == true) alignedSince = null
        previousTime = now
        if (reading?.aligned != true) { alignedSince = null; return false }
        val since = alignedSince ?: now.also { alignedSince = it }
        return now - since >= 1200
    }
}
