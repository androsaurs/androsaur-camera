package dev.hram.androsaur.camera.face

import kotlin.math.sqrt

/** Face vectors: unit length, compared by cosine similarity (a dot product of unit vectors). */
object Embeddings {
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum)
        require(norm > 1e-9) { "Zero vector" }
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Different sizes" }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i].toDouble() * b[i]; na += a[i].toDouble() * a[i]; nb += b[i].toDouble() * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d < 1e-12) 0.0 else dot / d
    }
}

/** The people the phone knows: for each, the vectors of the samples taken while teaching. */
class Gallery(private val people: Map<String, List<FloatArray>>) {
    class Score(val name: String, val similarity: Double)
    class Decision(val name: String?, val best: Score?, val second: Score?, val scores: List<Score>)

    /** A person's score is the mean of their [topK] best matches: one lucky sample does not decide. */
    fun scores(probe: FloatArray, topK: Int = 3): List<Score> = people.map { (name, samples) ->
        val best = samples.map { Embeddings.cosine(probe, it) }.sortedDescending().take(topK.coerceAtLeast(1))
        Score(name, if (best.isEmpty()) -1.0 else best.average())
    }.sortedByDescending { it.similarity }

    /** The name when the best score reaches [threshold], otherwise null ("I do not know this face"). */
    fun decide(probe: FloatArray, threshold: Double, topK: Int = 3): Decision {
        val all = scores(probe, topK)
        val best = all.firstOrNull()
        return Decision(if (best != null && best.similarity >= threshold) best.name else null, best, all.getOrNull(1), all)
    }
}
