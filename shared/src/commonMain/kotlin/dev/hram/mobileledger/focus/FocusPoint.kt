package dev.hram.mobileledger.focus

/** Inverse of the clockwise rotation used to encode the browser preview. Back camera, no mirror. */
object FocusPoint {
    fun toSurface(x: Float, y: Float, rotation: Int): Pair<Float, Float> {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        return when (rotation) {
            0 -> x to y
            90 -> y to 1f - x
            180 -> 1f - x to 1f - y
            270 -> 1f - y to x
            else -> throw IllegalArgumentException("Unsupported camera rotation")
        }
    }
}
