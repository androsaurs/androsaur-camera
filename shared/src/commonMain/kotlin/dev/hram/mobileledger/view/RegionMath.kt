package dev.hram.mobileledger.view

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Geometry for viewing a stored JPEG: fractions are relative to the image as displayed (EXIF rotation applied). */
object RegionMath {
    data class RawRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    fun orientedSize(rawWidth: Int, rawHeight: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == 90 || rotation == 270) rawHeight to rawWidth else rawWidth to rawHeight

    /** Maps a displayed-image region to stored pixel coordinates; null when invalid or smaller than one pixel. */
    fun toRaw(x: Double, y: Double, w: Double, h: Double, rotation: Int, rawWidth: Int, rawHeight: Int): RawRect? {
        if (rotation !in setOf(0, 90, 180, 270) || rawWidth <= 0 || rawHeight <= 0) return null
        if (listOf(x, y, w, h).any { !it.isFinite() }) return null
        val eps = 1e-9
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > 1 + eps || y + h > 1 + eps) return null
        val u0 = x; val u1 = min(1.0, x + w); val v0 = y; val v1 = min(1.0, y + h)
        // Stored fractions (xr, yr) for the displayed fractions (u, v), per clockwise rotation applied for display.
        val (xr0, xr1, yr0, yr1) = when (rotation) {
            0 -> listOf(u0, u1, v0, v1)
            90 -> listOf(v0, v1, 1 - u1, 1 - u0)
            180 -> listOf(1 - u1, 1 - u0, 1 - v1, 1 - v0)
            else -> listOf(1 - v1, 1 - v0, u0, u1)
        }
        val left = max(0, floor(xr0 * rawWidth + eps).toInt())
        val right = min(rawWidth, ceil(xr1 * rawWidth - eps).toInt())
        val top = max(0, floor(yr0 * rawHeight + eps).toInt())
        val bottom = min(rawHeight, ceil(yr1 * rawHeight - eps).toInt())
        if (right - left < 1 || bottom - top < 1) return null
        return RawRect(left, top, right, bottom)
    }

    /** Largest size within both limits that keeps the aspect ratio; never upscales. */
    fun fit(width: Int, height: Int, maxEdge: Int, maxPixels: Long): Pair<Int, Int> {
        val scale = minOf(1.0, maxEdge.toDouble() / max(width, height), sqrt(maxPixels.toDouble() / (width.toLong() * height)))
        return max(1, floor(width * scale).toInt()) to max(1, floor(height * scale).toInt())
    }
}
