package dev.hram.androsaur.camera.focus

import kotlin.test.*

class FocusPointTest {
    @Test fun rotatedPreviewMapsToSensorSurface() {
        assertEquals(.25f to .75f, FocusPoint.toSurface(.25f, .75f, 0))
        assertEquals(.75f to .75f, FocusPoint.toSurface(.25f, .75f, 90))
        assertEquals(.75f to .25f, FocusPoint.toSurface(.25f, .75f, 180))
        assertEquals(.25f to .25f, FocusPoint.toSurface(.25f, .75f, 270))
        for (angle in listOf(0, 90, 180, 270)) assertEquals(.5f to .5f, FocusPoint.toSurface(.5f, .5f, angle))
    }
    @Test fun invalidPointsAreRejected() {
        for ((x, y) in listOf(-.1f to .5f, .5f to 1.1f, Float.NaN to .5f, .5f to Float.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { FocusPoint.toSurface(x, y, 0) }
        assertFailsWith<IllegalArgumentException> { FocusPoint.toSurface(.5f, .5f, 45) }
    }
}
