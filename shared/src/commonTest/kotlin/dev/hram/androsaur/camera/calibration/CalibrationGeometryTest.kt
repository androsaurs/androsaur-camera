package dev.hram.androsaur.camera.calibration

import kotlin.math.*
import kotlin.test.*

class CalibrationGeometryTest {
    private fun projected(tiltX: Double = 0.0, tiltY: Double = 0.0, rotation: Double = 0.0): Map<CalibrationCorner, CalibrationPoint> {
        val positions = listOf(-0.8 to -1.0, 0.8 to -1.0, 0.8 to 1.0, -0.8 to 1.0)
        return CalibrationCorner.entries.zip(positions.map { (x, y) ->
            val cameraX = cos(tiltY) * x + sin(tiltY) * sin(tiltX) * y
            val cameraY = cos(tiltX) * y
            val depth = 4 - sin(tiltY) * x + cos(tiltY) * sin(tiltX) * y
            CalibrationPoint(0.5 + (cos(rotation) * cameraX - sin(rotation) * cameraY) / depth,
                0.5 + (sin(rotation) * cameraX + cos(rotation) * cameraY) / depth)
        }).toMap()
    }

    @Test fun frontParallelWorksWithoutKnownDimensionsAndDespiteInPlaneRotation() {
        for (rotation in listOf(0.0, 0.4, PI / 2, PI)) {
            val reading = assertNotNull(CalibrationGeometry.evaluate(projected(rotation = rotation), 1.0))
            assertTrue(reading.aligned)
            assertEquals(0.0, reading.error, 1e-10)
        }
    }

    @Test fun tiltDirectionsFollowCameraProjectionAndScreenRotation() {
        val rightNear = assertNotNull(CalibrationGeometry.evaluate(projected(tiltY = 0.25), 1.0))
        assertTrue(rightNear.liftRight > 0.025)
        assertFalse(rightNear.aligned)
        val leftNear = assertNotNull(CalibrationGeometry.evaluate(projected(tiltY = -0.25), 1.0))
        assertTrue(leftNear.liftRight < -0.025)
        val topNear = assertNotNull(CalibrationGeometry.evaluate(projected(tiltX = 0.25), 1.0))
        assertTrue(topNear.liftBottom < -0.025)
        val bottomNear = assertNotNull(CalibrationGeometry.evaluate(projected(tiltX = -0.25), 1.0))
        assertTrue(bottomNear.liftBottom > 0.025)
        val rotated = assertNotNull(CalibrationGeometry.evaluate(projected(tiltY = 0.25, rotation = PI / 2), 1.0))
        assertTrue(rotated.liftBottom > 0.025)
        assertEquals(0.0, rotated.liftRight, 1e-10)
    }

    @Test fun missingCrossedTinyOrNonFiniteMarkersNeverReportSuccess() {
        val markers = projected()
        assertNull(CalibrationGeometry.evaluate(markers - CalibrationCorner.BL, 1.0))
        assertNull(CalibrationGeometry.evaluate(markers + mapOf(CalibrationCorner.TR to markers.getValue(CalibrationCorner.BR),
            CalibrationCorner.BR to markers.getValue(CalibrationCorner.TR)), 1.0))
        assertNull(CalibrationGeometry.evaluate(markers.mapValues { CalibrationPoint(0.5, 0.5) }, 1.0))
        assertNull(CalibrationGeometry.evaluate(markers + (CalibrationCorner.TL to CalibrationPoint(Double.NaN, 0.0)), 1.0))
    }

    @Test fun skewedParallelogramIsNotAligned() {
        val skewed = projected().mapValues { (_, p) -> CalibrationPoint(p.x + 0.2 * (p.y - 0.5), p.y) }
        assertFalse(assertNotNull(CalibrationGeometry.evaluate(skewed, 1.0)).aligned)
    }

    @Test fun projectiveCentreUsesIntersectionRatherThanAverage() {
        val quad = listOf(CalibrationPoint(0.0, 0.0), CalibrationPoint(1.0, 0.0), CalibrationPoint(0.8, 0.8), CalibrationPoint(0.2, 0.8))
        val centre = assertNotNull(CalibrationGeometry.centre(quad))
        assertEquals(0.5, centre.x, 1e-10)
        assertEquals(0.5, centre.y, 1e-10)
    }

    @Test fun stabilityResetsOnMissingMarkersGapsAndTilt() {
        val tracker = CalibrationStability()
        val aligned = CalibrationGeometry.evaluate(projected(), 1.0)
        assertFalse(tracker.update(aligned, 0))
        assertFalse(tracker.update(aligned, 400))
        assertFalse(tracker.update(aligned, 800))
        assertTrue(tracker.update(aligned, 1200))
        assertFalse(tracker.update(null, 1400))
        assertFalse(tracker.update(aligned, 1600))
        assertFalse(tracker.update(aligned, 3000))
        assertFalse(tracker.update(CalibrationGeometry.evaluate(projected(tiltY = 0.3), 1.0), 3200))
    }

    @Test fun onlyAgreedPayloadsAreAccepted() {
        CalibrationCorner.entries.forEach { assertEquals(it, CalibrationCorner.parse(it.payload)) }
        assertNull(CalibrationCorner.parse("TL"))
        assertNull(CalibrationCorner.parse("mobile-ledger:calibration:v2:TL"))
    }

    @Test fun sameFrameCanBeAcceptedOrRejectedByRuntimeTolerance() {
        val markers = projected(tiltY = 0.04)
        val strict = assertNotNull(CalibrationGeometry.evaluate(markers, 1.0, 0.01))
        val relaxed = assertNotNull(CalibrationGeometry.evaluate(markers, 1.0, 0.025))
        assertFalse(strict.aligned)
        assertTrue(relaxed.aligned)
        assertEquals(strict.error, relaxed.error)
        assertNull(CalibrationGeometry.evaluate(markers, 1.0, 0.0))
        assertNull(CalibrationGeometry.evaluate(markers, 1.0, Double.NaN))
    }

    @Test fun changingToleranceRequiresANewStabilityInterval() {
        val tracker = CalibrationStability()
        val reading = CalibrationGeometry.evaluate(projected(), 1.0)
        for (time in listOf(0L, 400L, 800L)) assertFalse(tracker.update(reading, time))
        assertTrue(tracker.update(reading, 1200))
        tracker.reset()
        assertFalse(tracker.update(reading, 1300))
    }
}
