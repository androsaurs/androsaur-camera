package dev.hram.mobileledger.store

import kotlin.test.Test
import kotlin.test.assertEquals

class RangeParserTest {
    private val full = RangeParser.Result.Full
    private val unsatisfiable = RangeParser.Result.Unsatisfiable

    @Test fun forms() {
        assertEquals(RangeParser.Result.Partial(0, 9), RangeParser.parse("bytes=0-9", 100))
        assertEquals(RangeParser.Result.Partial(90, 99), RangeParser.parse("bytes=90-", 100))
        assertEquals(RangeParser.Result.Partial(90, 99), RangeParser.parse("bytes=-10", 100))
        assertEquals(RangeParser.Result.Partial(0, 99), RangeParser.parse("bytes=-500", 100))
        assertEquals(RangeParser.Result.Partial(50, 99), RangeParser.parse("bytes=50-5000", 100))
    }

    @Test fun ignoredAndRejected() {
        assertEquals(full, RangeParser.parse(null, 100))
        assertEquals(full, RangeParser.parse("items=0-1", 100))
        assertEquals(full, RangeParser.parse("bytes=0-1,5-6", 100))
        assertEquals(full, RangeParser.parse("bytes=9-3", 100))
        assertEquals(full, RangeParser.parse("bytes=x-y", 100))
        assertEquals(unsatisfiable, RangeParser.parse("bytes=100-", 100))
        assertEquals(unsatisfiable, RangeParser.parse("bytes=-0", 100))
    }
}
