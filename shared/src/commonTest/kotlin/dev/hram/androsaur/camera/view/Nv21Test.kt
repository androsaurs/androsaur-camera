package dev.hram.androsaur.camera.view

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Nv21Test {
    // 4 x 2 image: Y = [0 1 2 3 / 4 5 6 7], chroma pairs (V,U): left (10,11), right (20,21).
    private val source = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 20, 21)

    @Test fun rotates90Clockwise() {
        // First row of the result is the first column read bottom to top.
        assertContentEquals(byteArrayOf(4, 0, 5, 1, 6, 2, 7, 3, 10, 11, 20, 21), Nv21.rotate(source, 4, 2, 90))
    }

    @Test fun rotates270Clockwise() {
        assertContentEquals(byteArrayOf(3, 7, 2, 6, 1, 5, 0, 4, 20, 21, 10, 11), Nv21.rotate(source, 4, 2, 270))
    }

    @Test fun rotates180KeepingChromaPairsIntact() {
        assertContentEquals(byteArrayOf(7, 6, 5, 4, 3, 2, 1, 0, 20, 21, 10, 11), Nv21.rotate(source, 4, 2, 180))
    }

    @Test fun zeroReturnsTheSameFrame() {
        assertTrue(Nv21.rotate(source, 4, 2, 0) === source)
    }

    @Test fun rotationsCompose() {
        val width = 8; val height = 6
        val frame = ByteArray(width * height * 3 / 2) { (it * 7 % 251).toByte() }
        var turned = frame; var w = width; var h = height
        repeat(4) { turned = Nv21.rotate(turned, w, h, 90); val t = w; w = h; h = t }
        assertContentEquals(frame, turned)
        val back = Nv21.rotate(Nv21.rotate(frame, width, height, 90), height, width, 270)
        assertContentEquals(frame, back)
        assertContentEquals(frame, Nv21.rotate(Nv21.rotate(frame, width, height, 180), width, height, 180))
    }

    @Test fun rotationNeedsEvenSizesAndKnownAngles() {
        assertTrue(Nv21.canRotate(640, 480, 90))
        assertFalse(Nv21.canRotate(641, 480, 90))
        assertFalse(Nv21.canRotate(640, 480, 45))
        assertEquals(4 * 2 * 3 / 2, Nv21.rotate(source, 4, 2, 90).size)
    }
}
