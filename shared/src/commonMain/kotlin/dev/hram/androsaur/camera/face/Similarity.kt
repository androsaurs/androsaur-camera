package dev.hram.androsaur.camera.face

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The rotation, scale and shift that bring the points [from] closest to [to] (least squares, no mirroring).
 * Used to put a face on the template the recognition model was trained with. Points are x,y pairs.
 */
class Similarity(val scale: Double, val angle: Double, val tx: Double, val ty: Double) {
    /** The 3x3 matrix, row-major, as android.graphics.Matrix.setValues takes it. */
    fun values(): FloatArray {
        val a = (scale * cos(angle)).toFloat(); val b = (scale * sin(angle)).toFloat()
        return floatArrayOf(a, -b, tx.toFloat(), b, a, ty.toFloat(), 0f, 0f, 1f)
    }

    fun apply(x: Double, y: Double): Pair<Double, Double> =
        (scale * cos(angle) * x - scale * sin(angle) * y + tx) to (scale * sin(angle) * x + scale * cos(angle) * y + ty)

    companion object {
        fun fit(from: DoubleArray, to: DoubleArray): Similarity {
            require(from.size == to.size && from.size >= 4 && from.size % 2 == 0) { "Need at least two matching points" }
            val n = from.size / 2
            var msx = 0.0; var msy = 0.0; var mdx = 0.0; var mdy = 0.0
            for (i in 0 until n) { msx += from[2 * i]; msy += from[2 * i + 1]; mdx += to[2 * i]; mdy += to[2 * i + 1] }
            msx /= n; msy /= n; mdx /= n; mdy /= n
            var a = 0.0; var b = 0.0; var variance = 0.0
            for (i in 0 until n) {
                val sx = from[2 * i] - msx; val sy = from[2 * i + 1] - msy
                val dx = to[2 * i] - mdx; val dy = to[2 * i + 1] - mdy
                a += sx * dx + sy * dy; b += sx * dy - sy * dx; variance += sx * sx + sy * sy
            }
            require(variance > 1e-9) { "The points coincide" }
            val scale = sqrt(a * a + b * b) / variance
            val angle = atan2(b, a)
            val c = scale * cos(angle); val s = scale * sin(angle)
            return Similarity(scale, angle, mdx - (c * msx - s * msy), mdy - (s * msx + c * msy))
        }
    }
}
