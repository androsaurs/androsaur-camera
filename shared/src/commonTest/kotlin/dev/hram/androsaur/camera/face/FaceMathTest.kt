package dev.hram.androsaur.camera.face

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceMathTest {
    private fun near(expected: Double, actual: Double, tolerance: Double = 1e-6) = assertTrue(abs(expected - actual) <= tolerance, "$expected vs $actual")

    @Test fun normalizeGivesUnitLength() {
        val v = Embeddings.normalize(floatArrayOf(3f, 4f))
        near(1.0, Embeddings.cosine(v, v)); near(0.6, v[0].toDouble(), 1e-6); near(0.8, v[1].toDouble(), 1e-6)
    }

    @Test fun cosineOfSameOppositeAndOrthogonal() {
        near(1.0, Embeddings.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)))
        near(-1.0, Embeddings.cosine(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f)))
        near(0.0, Embeddings.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
    }

    private val gallery = Gallery(mapOf(
        "Женя" to listOf(floatArrayOf(1f, 0f), floatArrayOf(0.9f, 0.1f), floatArrayOf(0.95f, -0.05f)),
        "Маша" to listOf(floatArrayOf(0f, 1f)),
    ))

    @Test fun knownFaceIsNamed() {
        val d = gallery.decide(floatArrayOf(1f, 0.05f), 0.5)
        assertEquals("Женя", d.name); assertEquals("Маша", d.second?.name)
    }

    @Test fun unknownFaceBelowThresholdHasNoName() {
        val d = gallery.decide(floatArrayOf(1f, 1f), 0.9)
        assertNull(d.name); assertNotNull(d.best)
    }

    @Test fun emptyGalleryKnowsNobody() {
        val d = Gallery(emptyMap()).decide(floatArrayOf(1f, 0f), 0.1)
        assertNull(d.name); assertNull(d.best)
    }

    @Test fun oneLuckySampleDoesNotDecide() {
        val g = Gallery(mapOf("A" to listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, 1f), floatArrayOf(0f, 1f))))
        // best sample matches fully, the others not at all: the mean of the top three is 1/3
        near(1.0 / 3, g.scores(floatArrayOf(1f, 0f), topK = 3).first().similarity, 1e-6)
    }

    @Test fun similarityRecoversATransform() {
        val from = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 5.0, 8.0, 2.0, 6.0)
        val real = Similarity(2.0, 0.5, 30.0, -4.0)
        val to = DoubleArray(from.size)
        for (i in 0 until from.size / 2) { val (x, y) = real.apply(from[2 * i], from[2 * i + 1]); to[2 * i] = x; to[2 * i + 1] = y }
        val fit = Similarity.fit(from, to)
        near(2.0, fit.scale); near(0.5, fit.angle); near(30.0, fit.tx, 1e-6); near(-4.0, fit.ty, 1e-6)
    }

    @Test fun similarityKeepsEyesLevelOnATemplate() {
        // a head tilted by 20 degrees: eyes at (40,60) and (60,70); the template has them level at (38,52) and (74,52)
        val fit = Similarity.fit(doubleArrayOf(40.0, 60.0, 60.0, 70.0), doubleArrayOf(38.0, 52.0, 74.0, 52.0))
        val (lx, ly) = fit.apply(40.0, 60.0); val (rx, ry) = fit.apply(60.0, 70.0)
        near(38.0, lx, 1e-6); near(52.0, ly, 1e-6); near(74.0, rx, 1e-6); near(52.0, ry, 1e-6)
    }
}
