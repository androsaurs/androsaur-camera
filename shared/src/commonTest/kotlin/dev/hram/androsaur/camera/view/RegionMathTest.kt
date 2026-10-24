package dev.hram.androsaur.camera.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionMathTest {
    // Stored image 400 x 300 with a marker block in each displayed corner is easiest to reason about:
    // the displayed top-left quarter must map to the stored corner that rotation moves there.
    private val w = 400; private val h = 300

    @Test fun unrotated() {
        assertEquals(RegionMath.RawRect(0, 0, 200, 150), RegionMath.toRaw(0.0, 0.0, 0.5, 0.5, 0, w, h))
        assertEquals(RegionMath.RawRect(200, 150, 400, 300), RegionMath.toRaw(0.5, 0.5, 0.5, 0.5, 0, w, h))
    }

    @Test fun rotated90ShowsStoredTopLeftAtDisplayedTopRight() {
        assertEquals(RegionMath.RawRect(0, 0, 200, 150), RegionMath.toRaw(0.5, 0.0, 0.5, 0.5, 90, w, h))
        assertEquals(RegionMath.RawRect(0, 150, 200, 300), RegionMath.toRaw(0.0, 0.0, 0.5, 0.5, 90, w, h))
    }

    @Test fun rotated180() {
        assertEquals(RegionMath.RawRect(200, 150, 400, 300), RegionMath.toRaw(0.0, 0.0, 0.5, 0.5, 180, w, h))
    }

    @Test fun rotated270ShowsStoredTopLeftAtDisplayedBottomLeft() {
        assertEquals(RegionMath.RawRect(0, 0, 200, 150), RegionMath.toRaw(0.0, 0.5, 0.5, 0.5, 270, w, h))
        assertEquals(RegionMath.RawRect(200, 0, 400, 150), RegionMath.toRaw(0.0, 0.0, 0.5, 0.5, 270, w, h))
    }

    @Test fun wholeImageForEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270))
            assertEquals(RegionMath.RawRect(0, 0, w, h), RegionMath.toRaw(0.0, 0.0, 1.0, 1.0, rotation, w, h))
    }

    @Test fun rejectsInvalidRegions() {
        assertNull(RegionMath.toRaw(-0.1, 0.0, 0.5, 0.5, 0, w, h))
        assertNull(RegionMath.toRaw(0.6, 0.0, 0.5, 0.5, 0, w, h))
        assertNull(RegionMath.toRaw(0.0, 0.0, 0.0, 0.5, 0, w, h))
        assertNull(RegionMath.toRaw(0.0, 0.0, Double.NaN, 0.5, 0, w, h))
        assertNull(RegionMath.toRaw(0.0, 0.0, 0.5, 0.5, 45, w, h))
    }

    @Test fun orientedSizeSwapsForQuarterTurns() {
        assertEquals(300 to 400, RegionMath.orientedSize(w, h, 90))
        assertEquals(400 to 300, RegionMath.orientedSize(w, h, 180))
    }

    @Test fun fitHonoursBothLimitsAndNeverUpscales() {
        assertEquals(1200 to 900, RegionMath.fit(4080, 3060, 1200, 1_150_000))
        val (fw, fh) = RegionMath.fit(4080, 3060, 1568, 1_150_000)
        assertEquals(true, fw.toLong() * fh <= 1_150_000 && fw < 1568)
        assertEquals(300 to 200, RegionMath.fit(300, 200, 1568, 1_150_000))
    }
}
