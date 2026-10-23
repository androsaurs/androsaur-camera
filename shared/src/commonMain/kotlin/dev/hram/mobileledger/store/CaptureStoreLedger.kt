package dev.hram.mobileledger.store

/**
 * Bookkeeping for the agent's capture store: handles, quota, expiry and download links.
 * Platform code owns the files and must delete the files of every entry returned by [sweep] and [release].
 * Callers serialize access. Time is passed in so expiry is testable.
 */
class CaptureStoreLedger(
    val bootTag: String,
    val maxCount: Int = 24,
    val maxBytes: Long = 120L * 1024 * 1024,
    val ttlMs: Long = 60_000,
    private val maxLinks: Int = 32,
) {
    data class Entry(
        val handle: String, val requestId: String?, val bytes: Long, val sha256: String,
        val createdAt: Long, val lastAccess: Long, val metadata: String,
    )

    sealed class Lookup {
        data class Found(val entry: Entry) : Lookup()
        /** reason: released, delivered, evicted, expired, restarted or never_existed. */
        data class Gone(val reason: String) : Lookup()
    }

    sealed class LinkOutcome {
        object Invalid : LinkOutcome()
        object Expired : LinkOutcome()
        data class Unsatisfiable(val total: Long) : LinkOutcome()
        /** [delivered]: a one-time link has just delivered the last byte, so the entry is already removed and its file must be deleted. */
        data class Served(val entry: Entry, val start: Long, val end: Long, val partial: Boolean, val delivered: Boolean = false) : LinkOutcome()
    }

    private class Link(val handle: String, val expiresAt: Long, val oneTime: Boolean)

    private val entries = LinkedHashMap<String, Entry>()
    private val tombstones = LinkedHashMap<String, String>()
    private val requests = HashMap<String, String>()
    private val links = LinkedHashMap<String, Link>()

    val count: Int get() = entries.size
    val totalBytes: Long get() = entries.values.sumOf { it.bytes }

    /**
     * Makes room for one more entry of [reserveBytes] by evicting, never by refusing: a new shot must always succeed.
     * Victims are the least recently used; photos with a live link go last. Returns them so their files can be deleted.
     */
    fun makeRoom(reserveBytes: Long, now: Long): List<Entry> {
        val evicted = mutableListOf<Entry>()
        while (entries.isNotEmpty() && (entries.size >= maxCount || totalBytes + reserveBytes > maxBytes)) {
            val victim = entries.values.minWith(compareBy<Entry>({ hasLiveLink(it.handle, now) }, { it.lastAccess }))
            entries.remove(victim.handle)
            forget(victim, "evicted")
            evicted += victim
        }
        return evicted
    }

    private fun hasLiveLink(handle: String, now: Long) = links.values.any { it.handle == handle && now < it.expiresAt }

    fun add(entry: Entry) {
        entries[entry.handle] = entry
        entry.requestId?.let { requests[it] = entry.handle }
    }

    fun byRequest(requestId: String): Entry? = requests[requestId]?.let { entries[it] }

    fun list(): List<Entry> = entries.values.toList()

    fun lookup(handle: String, now: Long, touch: Boolean = true): Lookup {
        entries[handle]?.let { entry ->
            if (!touch) return Lookup.Found(entry)
            val touched = entry.copy(lastAccess = now)
            entries[handle] = touched
            return Lookup.Found(touched)
        }
        tombstones[handle]?.let { return Lookup.Gone(it) }
        return Lookup.Gone(if (!handle.startsWith("$bootTag-")) "restarted" else "never_existed")
    }

    fun release(handle: String): Entry? {
        val entry = entries.remove(handle) ?: return null
        forget(entry, "released")
        return entry
    }

    /** When an entry goes away: [ttlMs] after its last use, but never before its latest link expires. */
    fun expiresAt(entry: Entry): Long =
        maxOf(entry.lastAccess + ttlMs, links.values.filter { it.handle == entry.handle }.maxOfOrNull { it.expiresAt } ?: 0L)

    /** Removes entries past [expiresAt]; returns them so the caller can delete their files. Expired links linger until pruned by [createLink] so they can answer "expired". */
    fun sweep(now: Long): List<Entry> {
        val expired = entries.values.filter { now >= expiresAt(it) }
        expired.forEach { entries.remove(it.handle); forget(it, "expired") }
        return expired
    }

    fun createLink(token: String, handle: String, now: Long, ttlMs: Long, oneTime: Boolean): Boolean {
        if (handle !in entries) return false
        links.entries.removeAll { now >= it.value.expiresAt }
        if (links.size >= maxLinks) return false
        links[token] = Link(handle, now + ttlMs, oneTime)
        return true
    }

    fun redeem(token: String, now: Long, rangeHeader: String?): LinkOutcome {
        val link = links[token] ?: return LinkOutcome.Invalid
        if (now >= link.expiresAt) { links.remove(token); return LinkOutcome.Expired }
        val entry = entries[link.handle] ?: run { links.remove(token); return LinkOutcome.Invalid }
        val served = when (val range = RangeParser.parse(rangeHeader, entry.bytes)) {
            RangeParser.Result.Full -> LinkOutcome.Served(entry, 0, entry.bytes - 1, false)
            is RangeParser.Result.Partial -> LinkOutcome.Served(entry, range.start, range.end, true)
            RangeParser.Result.Unsatisfiable -> return LinkOutcome.Unsatisfiable(entry.bytes)
        }
        // A one-time link ends with the response that delivers the last byte; earlier ranges allow resuming.
        // Delivered means done: the photo is removed with it.
        if (link.oneTime && served.end == entry.bytes - 1) {
            entries.remove(entry.handle)
            forget(entry, "delivered")
            return served.copy(delivered = true)
        }
        entries[entry.handle] = entry.copy(lastAccess = now)
        return served
    }

    private fun forget(entry: Entry, reason: String) {
        tombstones[entry.handle] = reason
        while (tombstones.size > 512) tombstones.remove(tombstones.keys.first())
        entry.requestId?.let { requests.remove(it) }
        links.entries.removeAll { it.value.handle == entry.handle }
    }
}
