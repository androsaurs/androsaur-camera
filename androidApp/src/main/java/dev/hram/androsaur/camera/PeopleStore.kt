package dev.hram.androsaur.camera

import dev.hram.androsaur.camera.face.Gallery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.roundToLong

/**
 * The people the camera knows, kept in the app's private storage as names and face vectors only:
 * no photograph is ever saved. Small enough to rewrite whole on every change.
 *
 * Each person has a permanent id made when they are first taught. A rename changes the name only, so whatever the other
 * apps tie to the person (voice notes for them, roles) stays with them.
 */
class PeopleStore(private val file: File) {
    private val people = linkedMapOf<String, MutableList<FloatArray>>()
    private val ids = mutableMapOf<String, String>()

    init { load() }

    @Synchronized fun names(): List<String> = people.keys.toList()
    @Synchronized fun samples(name: String): Int = people[name]?.size ?: 0
    @Synchronized fun idOf(name: String?): String? = name?.let { ids[it] }
    @Synchronized fun nameOf(id: String): String? = ids.entries.firstOrNull { it.value == id }?.key

    /** The person an API call means: by [id] when given, otherwise by [name]. */
    @Synchronized fun find(id: String?, name: String?): String? = if (!id.isNullOrEmpty()) nameOf(id) else name?.takeIf { it in people }
    @Synchronized fun gallery() = Gallery(people.mapValues { it.value.toList() })

    /** Adds the vectors to the person (created if new). */
    @Synchronized fun add(name: String, vectors: List<FloatArray>) {
        require(valid(name)) { "Invalid name" }
        people.getOrPut(name) { mutableListOf() }.addAll(vectors)
        ids.getOrPut(name) { newId() }
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
        ids.remove(name)?.let { ids[to] = it }
        save()
        return null
    }

    /**
     * A fingerprint of a person's sets, so the hub can tell they changed without getting them: count and a checksum of the
     * values rounded the way the hub keeps them.
     */
    @Synchronized fun digest(name: String): String {
        val crc = CRC32()
        people[name].orEmpty().forEach { v -> crc.update(key(v).toByteArray()) }
        return "${people[name]?.size ?: 0}-${java.lang.Long.toHexString(crc.value)}"
    }

    /** Everybody with their sets (numbers only), for the hub. */
    @Synchronized fun export(): List<Triple<String, String, List<FloatArray>>> =
        people.map { (name, vectors) -> Triple(ids.getValue(name), name, vectors.toList()) }

    /**
     * Takes people from the hub, the people of the house: a person with the id already here takes the name and gains the sets
     * not here yet (up to [MAX_VECTORS]; the ones here stay); a new one is added. Returns the error of each person that could
     * not be taken (`invalid_name`, `name_taken` when somebody else here has that name).
     */
    @Synchronized fun import(list: List<Triple<String, String, List<FloatArray>>>): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        for ((id, name, vectors) in list) {
            if (!valid(name)) { errors[id] = "invalid_name"; continue }
            val current = nameOf(id)
            if (name in people && current != name) { errors[id] = "name_taken"; continue }
            if (current == null) { people[name] = mutableListOf(); ids[name] = id }
            else if (current != name) renameInPlace(current, name)
            addNew(people.getValue(name), vectors)
        }
        save()
        return errors
    }

    /**
     * The person [fromId] here is the person [intoId] of the house (called [name]): if that person is here too, the sets of the
     * first join theirs and the first goes; otherwise the first takes the id and the name. Returns an error code, or null.
     * Done twice it still ends well.
     */
    @Synchronized fun merge(fromId: String, intoId: String, name: String): String? {
        val from = nameOf(fromId) ?: return if (nameOf(intoId) != null) null else "unknown_person"
        val into = nameOf(intoId)
        if (into != null) {
            addNew(people.getValue(into), people.getValue(from))
            people.remove(from); ids.remove(from)
        } else {
            if (name != from && name in people) return "name_taken"
            if (!valid(name)) return "invalid_name"
            ids[from] = intoId
            if (name != from) renameInPlace(from, name)
        }
        save()
        return null
    }

    private fun renameInPlace(name: String, to: String) {
        val renamed = linkedMapOf<String, MutableList<FloatArray>>()
        people.forEach { (n, v) -> renamed[if (n == name) to else n] = v }
        people.clear(); people.putAll(renamed)
        ids.remove(name)?.let { ids[to] = it }
    }

    /** Adds the sets that are not there yet (compared as the hub rounds them), while there is room. */
    private fun addNew(target: MutableList<FloatArray>, vectors: List<FloatArray>) {
        val have = target.map { key(it) }.toMutableSet()
        for (v in vectors) {
            if (target.size >= MAX_VECTORS) break
            if (have.add(key(v))) target.add(v)
        }
    }

    private fun key(v: FloatArray) = v.joinToString(",") { ((it * 100_000.0).roundToLong()).toString() }

    @Synchronized fun forget(name: String): Boolean = (people.remove(name) != null).also { if (it) { ids.remove(name); save() } }

    private fun load() {
        if (!file.exists()) return
        var missing = false
        runCatching {
            val root = JSONObject(file.readText())
            val list = root.getJSONArray("people")
            for (i in 0 until list.length()) {
                val p = list.getJSONObject(i)
                val vectors = p.getJSONArray("vectors")
                val name = p.getString("name")
                people[name] = MutableList(vectors.length()) { k ->
                    val v = vectors.getJSONArray(k); FloatArray(v.length()) { v.getDouble(it).toFloat() }
                }
                // People taught before ids existed get one now, once, and keep it from then on.
                ids[name] = p.optString("id").ifEmpty { missing = true; newId() }
            }
        }
        if (missing) save()
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun save() {
        val list = JSONArray()
        people.forEach { (name, vectors) ->
            list.put(JSONObject().put("id", ids[name]).put("name", name).put("vectors", JSONArray(vectors.map { v -> JSONArray(v.map { it.toDouble() }) })))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("people", list).toString())
        tmp.renameTo(file)
    }

    companion object {
        /** No more sets per person than this, here and in the hub. */
        const val MAX_VECTORS = 60

        /** A name is shown on screen and carried by the API: letters, digits, space, dash, up to 40. */
        fun valid(name: String) = name.matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{0,39}"))
    }
}
