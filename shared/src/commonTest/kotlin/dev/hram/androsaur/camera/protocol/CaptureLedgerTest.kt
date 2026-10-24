package dev.hram.androsaur.camera.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureLedgerTest {
    @Test fun retriesAndReplacement() {
        val ledger = CaptureLedger()
        val first = CaptureLedger.Request("one", "tab-a")
        val next = CaptureLedger.Request("two", "tab-b")
        assertEquals(CaptureLedger.Decision.START, ledger.accept(first))
        assertEquals(CaptureLedger.Decision.REPLAY, ledger.accept(first))
        assertEquals(CaptureLedger.Decision.CONFLICT, ledger.accept(first.copy(client = "tab-b")))
        assertEquals(CaptureLedger.Decision.BUSY, ledger.accept(next))
        assertTrue(ledger.captured(first))
        assertEquals(CaptureLedger.Decision.START, ledger.accept(next))
        assertFalse(ledger.captured(first))
        assertEquals(CaptureLedger.Decision.FINISHED, ledger.accept(first))
    }
    @Test fun cancellationDoesNotOverlapExposure() {
        val ledger = CaptureLedger()
        val request = CaptureLedger.Request("one", "a")
        ledger.accept(request)
        assertTrue(ledger.finish(request))
        assertEquals(CaptureLedger.Decision.BUSY, ledger.accept(CaptureLedger.Request("two", "b")))
        ledger.exposureEnded()
        assertEquals(CaptureLedger.Decision.START, ledger.accept(CaptureLedger.Request("two", "b")))
    }
    @Test fun replayRemainsAvailableWithoutStartingCamera() {
        val ledger = CaptureLedger()
        val request = CaptureLedger.Request("one", "tab")
        ledger.accept(request)
        ledger.captured(request)
        assertEquals(CaptureLedger.Decision.REPLAY, ledger.existing(request))
        assertFalse(ledger.capturing)
        ledger.finish(request)
        assertEquals(CaptureLedger.Decision.FINISHED, ledger.existing(request))
    }
}
