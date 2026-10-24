package dev.hram.androsaur.camera

import dev.hram.androsaur.camera.face.Gallery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The people the camera knows, kept in the app's private storage as names and face vectors only:
 * no photograph is ever saved. Small enough to rewrite whole on every change.
 */
class PeopleStore(private val file: File) {
    private val people = linkedMapOf<String, MutableList<FloatArray>>()

    init { load() }

    @Synchronized fun names(): List<String> = people.keys.toList()
    @Synchronized fun samples(name: String): Int = people[name]?.size ?: 0
    @Synchronized fun gallery() = Gallery(people.mapValues { it.value.toList() })

    /** Adds the vectors to the person (created if new). */
    @Synchronized fun add(name: String, vectors: List<FloatArray>) {
        require(valid(name)) { "Invalid name" }
        people.getOrPut(name) { mutableListOf() }.addAll(vectors)
        save()
    }

    /** Gives a person another name, keeping their place in the list. Returns an error code, or null on success. */
    @Synchronized fun rename(name: String, to: String): String? {
        if (name !in people) return "unknown_person"
        if (!valid(to)) return "invalid_name"
        if (to == name) return null
        if (to in people) return "name_taken"
        val renamed = linkedMapOf<String, MutableList<FloatArray>>()
        people.forEach { (n, v) -> renamed[if (n == name) to else n] = v }
        people.clear(); people.putAll(renamed)
        save()
        return null
    }

    @Synchronized fun forget(name: String): Boolean = (people.remove(name) != null).also { if (it) save() }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val list = root.getJSONArray("people")
            for (i in 0 until list.length()) {
                val p = list.getJSONObject(i)
                val vectors = p.getJSONArray("vectors")
                people[p.getString("name")] = MutableList(vectors.length()) { k ->
                    val v = vectors.getJSONArray(k); FloatArray(v.length()) { v.getDouble(it).toFloat() }
                }
            }
        }
    }

    private fun save() {
        val list = JSONArray()
        people.forEach { (name, vectors) ->
            list.put(JSONObject().put("name", name).put("vectors", JSONArray(vectors.map { v -> JSONArray(v.map { it.toDouble() }) })))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("people", list).toString())
        tmp.renameTo(file)
    }

    companion object {
        /** A name is shown on screen and carried by the API: letters, digits, space, dash, up to 40. */
        fun valid(name: String) = name.matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{0,39}"))
    }
}
