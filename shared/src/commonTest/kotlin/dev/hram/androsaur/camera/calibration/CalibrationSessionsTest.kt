package dev.hram.androsaur.camera.calibration

import kotlin.test.*

class CalibrationSessionsTest {
    @Test fun closingOneTabDoesNotReleaseAnother() {
        val sessions = CalibrationSessions()
        assertTrue(sessions.update("a", true, 0))
        assertTrue(sessions.update("b", true, 100))
        assertTrue(sessions.update("a", false, 200))
        assertTrue(sessions.hasClients(300))
        sessions.update("b", false, 400)
        assertFalse(sessions.hasClients(400))
    }
    @Test fun heartbeatExtendsLeaseButReadsDoNot() {
        val sessions = CalibrationSessions()
        sessions.update("a", true, 0)
        sessions.update("a", true, 10_000)
        assertTrue(sessions.hasClients(21_999))
        assertFalse(sessions.hasClients(22_000))
    }
    @Test fun limitsAreBoundedAndExpiredSlotsAreReusable() {
        val sessions = CalibrationSessions(limit = 1)
        assertTrue(sessions.update("a", true, 0))
        assertFalse(sessions.update("b", true, 1))
        assertTrue(sessions.update("a", true, 2))
        assertTrue(sessions.update("b", true, 12_002))
    }
}
