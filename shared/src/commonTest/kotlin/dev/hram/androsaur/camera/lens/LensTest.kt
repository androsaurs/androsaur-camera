package dev.hram.androsaur.camera.lens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LensTest {
    @Test fun parsesKnownIds() { assertEquals(Lens.BACK, Lens.parse("back")); assertEquals(Lens.FRONT, Lens.parse("front")) }

    @Test fun rejectsEverythingElse() {
        listOf(null, "", "FRONT", "selfie", " front", "rear").forEach { assertNull(Lens.parse(it), it.toString()) }
    }
}
