package dev.hram.androsaur.camera.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureStoreLedgerTest {
    private fun entry(handle: String, bytes: Long = 1000, at: Long = 0, request: String? = null) =
        CaptureStoreLedger.Entry(handle, request, bytes, "sha-$handle", at, at, "{}")

    private fun ledger() = CaptureStoreLedger("ab12", maxCount = 3, maxBytes = 10_000, ttlMs = 1000)

    @Test fun evictsLeastRecentlyUsedWhenCountIsFull() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", at = 0)); ledger.add(entry("ab12-b", at = 1)); ledger.add(entry("ab12-c", at = 2))
        ledger.lookup("ab12-a", now = 5)
        assertEquals(listOf("ab12-b"), ledger.makeRoom(0, now = 6).map { it.handle })
        assertEquals(CaptureStoreLedger.Lookup.Gone("evicted"), ledger.lookup("ab12-b", 7))
        assertEquals(2, ledger.count)
    }

    @Test fun evictsUntilBytesFit() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", 4000, at = 0)); ledger.add(entry("ab12-b", 4000, at = 1))
        assertTrue(ledger.makeRoom(2000, now = 2).isEmpty())
        assertEquals(listOf("ab12-a"), ledger.makeRoom(3000, now = 2).map { it.handle })
        assertEquals(listOf("ab12-b"), ledger.makeRoom(10_000, now = 2).map { it.handle })
        assertEquals(0, ledger.count)
    }

    @Test fun photosWithLiveLinksAreEvictedLast() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", at = 0)); ledger.add(entry("ab12-b", at = 1)); ledger.add(entry("ab12-c", at = 2))
        ledger.createLink("t", "ab12-a", now = 3, ttlMs = 500, oneTime = false)
        assertEquals(listOf("ab12-b"), ledger.makeRoom(0, now = 4).map { it.handle })
    }

    @Test fun neverRefusesEvenWhenEveryPhotoHasALink() {
        val ledger = CaptureStoreLedger("ab12", maxCount = 1, ttlMs = 1000)
        ledger.add(entry("ab12-a")); ledger.createLink("t", "ab12-a", 0, 500, false)
        assertEquals(listOf("ab12-a"), ledger.makeRoom(0, now = 1).map { it.handle })
        assertEquals(CaptureStoreLedger.LinkOutcome.Invalid, ledger.redeem("t", 2, null), "the link dies with its photo")
        assertTrue(ledger.makeRoom(0, now = 3).isEmpty())
    }

    @Test fun metadataCanBeUpdatedLaterWithoutTouchingTheRest() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", bytes = 777, at = 5, request = "r"))
        val updated = ledger.updateMetadata("ab12-a", "{\"metrics\":1}")!!
        assertEquals("{\"metrics\":1}", updated.metadata)
        assertEquals(777L to 5L, updated.bytes to updated.lastAccess)
        assertEquals("ab12-a", ledger.byRequest("r")?.handle)
        assertNull(ledger.updateMetadata("ab12-none", "{}"))
    }

    @Test fun expiryIsMeasuredFromLastAccess() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", at = 0))
        assertTrue(ledger.lookup("ab12-a", now = 900) is CaptureStoreLedger.Lookup.Found)
        assertTrue(ledger.sweep(1500).isEmpty(), "access at 900 moved the deadline to 1900")
        assertEquals(listOf("ab12-a"), ledger.sweep(1900).map { it.handle })
        assertEquals(CaptureStoreLedger.Lookup.Gone("expired"), ledger.lookup("ab12-a", 2000))
    }

    @Test fun goneReasons() {
        val ledger = ledger()
        ledger.add(entry("ab12-a"))
        assertNotNull(ledger.release("ab12-a"))
        assertNull(ledger.release("ab12-a"))
        assertEquals(CaptureStoreLedger.Lookup.Gone("released"), ledger.lookup("ab12-a", 0))
        assertEquals(CaptureStoreLedger.Lookup.Gone("never_existed"), ledger.lookup("ab12-zzz", 0))
        assertEquals(CaptureStoreLedger.Lookup.Gone("restarted"), ledger.lookup("ff00-a", 0))
    }

    @Test fun requestIdIsIdempotentWhileAlive() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", request = "req-1"))
        assertEquals("ab12-a", ledger.byRequest("req-1")?.handle)
        ledger.release("ab12-a")
        assertNull(ledger.byRequest("req-1"))
    }

    @Test fun oneTimeLinkEndsWithLastByte() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", bytes = 100))
        assertTrue(ledger.createLink("t", "ab12-a", now = 0, ttlMs = 500, oneTime = true))
        val partial = ledger.redeem("t", 10, "bytes=0-49") as CaptureStoreLedger.LinkOutcome.Served
        assertTrue(partial.partial); assertEquals(0L to 49L, partial.start to partial.end)
        val rest = ledger.redeem("t", 20, "bytes=50-") as CaptureStoreLedger.LinkOutcome.Served
        assertEquals(99L, rest.end)
        assertTrue(rest.delivered, "the last byte of a one-time link ends the photo")
        assertEquals(CaptureStoreLedger.LinkOutcome.Invalid, ledger.redeem("t", 30, null))
        assertEquals(CaptureStoreLedger.Lookup.Gone("delivered"), ledger.lookup("ab12-a", 40))
        assertEquals(0, ledger.count)
    }

    @Test fun partialRangesKeepThePhoto() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", bytes = 100))
        ledger.createLink("t", "ab12-a", 0, 500, oneTime = true)
        val head = ledger.redeem("t", 10, "bytes=0-49") as CaptureStoreLedger.LinkOutcome.Served
        assertFalse(head.delivered)
        assertTrue(ledger.lookup("ab12-a", 20) is CaptureStoreLedger.Lookup.Found)
    }

    @Test fun reusableLinkNeverDeletesThePhoto() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", bytes = 100))
        ledger.createLink("t", "ab12-a", 0, 500, oneTime = false)
        val full = ledger.redeem("t", 10, null) as CaptureStoreLedger.LinkOutcome.Served
        assertFalse(full.delivered)
        assertTrue(ledger.lookup("ab12-a", 20) is CaptureStoreLedger.Lookup.Found)
    }

    @Test fun liveLinkKeepsThePhotoPastItsTtl() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", at = 0))
        ledger.createLink("t", "ab12-a", now = 0, ttlMs = 5000, oneTime = false)
        assertTrue(ledger.sweep(3000).isEmpty(), "ttl is 1000 but the link lives until 5000")
        assertEquals(5000L, ledger.expiresAt(ledger.list().single()))
        assertEquals(listOf("ab12-a"), ledger.sweep(5000).map { it.handle })
    }

    @Test fun reusableLinkSurvivesFullDownload() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", bytes = 100))
        ledger.createLink("t", "ab12-a", 0, 500, oneTime = false)
        assertTrue(ledger.redeem("t", 10, null) is CaptureStoreLedger.LinkOutcome.Served)
        assertTrue(ledger.redeem("t", 20, null) is CaptureStoreLedger.LinkOutcome.Served)
    }

    @Test fun linkExpiresAndDiesWithHandle() {
        val ledger = ledger()
        ledger.add(entry("ab12-a")); ledger.add(entry("ab12-b"))
        ledger.createLink("t1", "ab12-a", 0, 500, true)
        ledger.createLink("t2", "ab12-b", 0, 500, true)
        assertEquals(CaptureStoreLedger.LinkOutcome.Expired, ledger.redeem("t1", 500, null))
        ledger.release("ab12-b")
        assertEquals(CaptureStoreLedger.LinkOutcome.Invalid, ledger.redeem("t2", 10, null))
    }

    @Test fun linkNeedsAliveHandleAndHasLimit() {
        val ledger = CaptureStoreLedger("ab12", maxCount = 3, ttlMs = 1000, maxLinks = 2)
        assertFalse(ledger.createLink("t", "ab12-none", 0, 500, true))
        ledger.add(entry("ab12-a"))
        assertTrue(ledger.createLink("t1", "ab12-a", 0, 500, true))
        assertTrue(ledger.createLink("t2", "ab12-a", 0, 500, true))
        assertFalse(ledger.createLink("t3", "ab12-a", 0, 500, true))
        assertTrue(ledger.createLink("t3", "ab12-a", 600, 500, true), "expired links free the slots")
    }

    @Test fun unsatisfiableRangeDoesNotConsumeLink() {
        val ledger = ledger()
        ledger.add(entry("ab12-a", bytes = 100))
        ledger.createLink("t", "ab12-a", 0, 500, true)
        assertEquals(CaptureStoreLedger.LinkOutcome.Unsatisfiable(100), ledger.redeem("t", 10, "bytes=100-"))
        assertTrue(ledger.redeem("t", 20, null) is CaptureStoreLedger.LinkOutcome.Served)
    }
}
