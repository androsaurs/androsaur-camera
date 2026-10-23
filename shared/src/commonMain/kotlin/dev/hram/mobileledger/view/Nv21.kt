package dev.hram.mobileledger.view

/**
 * Rotation of an NV21 frame (Y plane, then interleaved V/U at half resolution) before JPEG encoding, so the
 * preview needs one compression instead of compress, decode, rotate, compress again.
 * Width and height must be even; [degrees] is the clockwise turn that makes the image upright (0, 90, 180, 270).
 * For 90 and 270 the result is height x width.
 */
object Nv21 {
    fun canRotate(width: Int, height: Int, degrees: Int) =
        width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 && degrees in setOf(0, 90, 180, 270)

    fun rotate(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        require(canRotate(width, height, degrees) && src.size == width * height * 3 / 2)
        if (degrees == 0) return src
        val dst = ByteArray(src.size)
        val frame = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var i = 0
        when (degrees) {
            90 -> {
                for (x in 0 until width) for (y in height - 1 downTo 0) dst[i++] = src[y * width + x]
                i = frame
                for (cx in 0 until chromaWidth) for (cy in chromaHeight - 1 downTo 0) {
                    val o = frame + cy * width + cx * 2
                    dst[i++] = src[o]; dst[i++] = src[o + 1]
                }
            }
            180 -> {
                for (k in 0 until frame) dst[k] = src[frame - 1 - k]
                val pairs = frame / 4
                for (p in 0 until pairs) {
                    val s = frame + p * 2
                    val d = frame + (pairs - 1 - p) * 2
                    dst[d] = src[s]; dst[d + 1] = src[s + 1]
                }
            }
            else -> {
                for (x in width - 1 downTo 0) for (y in 0 until height) dst[i++] = src[y * width + x]
                i = frame
                for (cx in chromaWidth - 1 downTo 0) for (cy in 0 until chromaHeight) {
                    val o = frame + cy * width + cx * 2
                    dst[i++] = src[o]; dst[i++] = src[o + 1]
                }
            }
        }
        return dst
    }
}
