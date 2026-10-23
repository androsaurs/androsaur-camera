package dev.hram.mobileledger

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import dev.hram.mobileledger.view.RegionMath
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Derived views of a stored JPEG. The original file is never modified. All coordinates use the displayed (EXIF-rotated) image. */
object ImageTools {
    const val MAX_VIEW_PIXELS = 1_150_000L
    const val MAX_VIEW_EDGE = 1568

    class Rendered(val jpeg: ByteArray, val width: Int, val height: Int, val sourceWidth: Int, val sourceHeight: Int)

    fun rotationOf(file: File): Int = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    fun rawSize(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    fun orientedSize(file: File): Pair<Int, Int> {
        val (w, h) = rawSize(file)
        return RegionMath.orientedSize(w, h, rotationOf(file))
    }

    private fun sampleFor(sourceLongest: Int, targetLongest: Int): Int {
        var sample = 1
        while (sourceLongest / (sample * 2) >= targetLongest) sample *= 2
        return sample
    }

    private fun transform(decoded: Bitmap, rotation: Int, targetWidth: Int): Bitmap {
        val (orientedWidth, _) = RegionMath.orientedSize(decoded.width, decoded.height, rotation)
        val scale = minOf(1f, targetWidth.toFloat() / orientedWidth)
        if (scale == 1f && rotation == 0) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }, true)
    }

    private fun encode(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int) = Rendered(
        ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it); it.toByteArray() },
        bitmap.width, bitmap.height, sourceWidth, sourceHeight)

    fun renderWhole(file: File, maxEdge: Int): Rendered? {
        val rotation = rotationOf(file)
        val (rawW, rawH) = rawSize(file)
        if (rawW <= 0 || rawH <= 0) return null
        val (ow, oh) = RegionMath.orientedSize(rawW, rawH, rotation)
        val (tw, th) = RegionMath.fit(ow, oh, minOf(maxEdge, MAX_VIEW_EDGE), MAX_VIEW_PIXELS)
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleFor(maxOf(rawW, rawH), maxOf(tw, th))
        }) ?: return null
        val shown = transform(decoded, rotation, tw)
        try { return encode(shown, ow, oh) } finally { if (shown !== decoded) shown.recycle(); decoded.recycle() }
    }

    /** Region as fractions of the displayed image; null when the region is invalid. Native resolution, never upscaled. */
    fun renderRegion(file: File, x: Double, y: Double, w: Double, h: Double, maxEdge: Int): Rendered? {
        val rotation = rotationOf(file)
        val (rawW, rawH) = rawSize(file)
        val rect = RegionMath.toRaw(x, y, w, h, rotation, rawW, rawH) ?: return null
        val (sw, sh) = RegionMath.orientedSize(rect.width, rect.height, rotation)
        val (tw, th) = RegionMath.fit(sw, sh, minOf(maxEdge, MAX_VIEW_EDGE), MAX_VIEW_PIXELS)
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: return null
        val decodedOrNull = try {
            decoder.decodeRegion(Rect(rect.left, rect.top, rect.right, rect.bottom), BitmapFactory.Options().apply {
                inSampleSize = sampleFor(maxOf(sw, sh), maxOf(tw, th))
            })
        } finally { decoder.recycle() }
        val decoded = decodedOrNull ?: return null
        val shown = transform(decoded, rotation, tw)
        try { return encode(shown, sw, sh) } finally { if (shown !== decoded) shown.recycle(); decoded.recycle() }
    }

    /**
     * Quality numbers from a 1024 px copy of the displayed image. Sharpness is the variance of the Laplacian
     * (higher is sharper), overall, in the central half and per tile of a 4x4 grid (rows top to bottom).
     * Blank paper has a low value everywhere without being blurry, so compare the same tile between shots.
     */
    fun metrics(file: File): JSONObject {
        val rotation = rotationOf(file)
        val (rawW, rawH) = rawSize(file)
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleFor(maxOf(rawW, rawH), 1024)
        }) ?: return JSONObject().put("error", "undecodable")
        val shown = transform(decoded, rotation, Int.MAX_VALUE)
        val longest = maxOf(shown.width, shown.height)
        val small = if (longest > 1024) Bitmap.createScaledBitmap(shown, shown.width * 1024 / longest, shown.height * 1024 / longest, true) else shown
        try { return compute(small) } finally {
            if (small !== shown) small.recycle(); if (shown !== decoded) shown.recycle(); decoded.recycle()
        }
    }

    private class Sums { var sum = 0.0; var squares = 0.0; var count = 0L
        fun add(value: Int) { sum += value; squares += value.toDouble() * value; count++ }
        fun variance(): Double { if (count == 0L) return 0.0; val mean = sum / count; return squares / count - mean * mean }
    }

    private fun round1(value: Double) = Math.round(value * 10) / 10.0

    private fun compute(bitmap: Bitmap): JSONObject {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { val p = pixels[it]; (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000 }
        val overall = Sums(); val center = Sums(); val brightness = Sums()
        val tiles = Array(16) { Sums() }
        var overexposed = 0L
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val c = y * w + x
            val laplacian = gray[c - 1] + gray[c + 1] + gray[c - w] + gray[c + w] - 4 * gray[c]
            overall.add(laplacian)
            tiles[(y * 4 / h) * 4 + x * 4 / w].add(laplacian)
            if (x in w / 4 until w * 3 / 4 && y in h / 4 until h * 3 / 4) center.add(laplacian)
            brightness.add(gray[c])
            if (gray[c] >= 250) overexposed++
        }
        val grid = JSONArray()
        for (row in 0 until 4) grid.put(JSONArray().also { r -> for (col in 0 until 4) r.put(round1(tiles[row * 4 + col].variance())) })
        val mean = if (brightness.count == 0L) 0.0 else brightness.sum / brightness.count
        return JSONObject().put("analysisWidth", w).put("analysisHeight", h)
            .put("sharpness", round1(overall.variance())).put("sharpnessCenter", round1(center.variance()))
            .put("sharpnessGrid", grid).put("meanBrightness", round1(mean))
            .put("contrast", round1(Math.sqrt(maxOf(0.0, brightness.variance()))))
            .put("overexposedPercent", round1(100.0 * overexposed / maxOf(1L, brightness.count)))
    }
}
