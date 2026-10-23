package dev.hram.mobileledger

import android.os.SystemClock
import dev.hram.mobileledger.store.CaptureStoreLedger
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Private store for photographs taken on behalf of an agent. It is independent of the browser's single
 * unacknowledged-result slot, so neither side can delete the other's files. Everything is removed at start.
 */
class AgentStore(private val dir: File) {
    private val random = SecureRandom()
    private val bootTag = hex(2)
    private val ledger = CaptureStoreLedger(bootTag)

    init { dir.mkdirs(); dir.listFiles()?.forEach { it.delete() } }

    private fun now() = SystemClock.elapsedRealtime()
    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    fun file(handle: String) = File(dir, "$handle.jpg")

    @Synchronized fun sweep() { ledger.sweep(now()).forEach { file(it.handle).delete() } }
    /** Evicts the least recently used photos so a new one of [bytes] fits; returns their handles. */
    @Synchronized fun makeRoom(bytes: Long): List<String> =
        ledger.makeRoom(bytes, now()).onEach { file(it.handle).delete() }.map { it.handle }
    @Synchronized fun byRequest(requestId: String) = ledger.byRequest(requestId)
    @Synchronized fun list() = ledger.list()
    @Synchronized fun lookup(handle: String, touch: Boolean = true) = ledger.lookup(handle, now(), touch)

    @Synchronized fun release(handle: String): Boolean =
        ledger.release(handle)?.also { file(it.handle).delete() } != null

    @Synchronized fun clear() { ledger.list().forEach { ledger.release(it.handle) }; dir.listFiles()?.forEach { it.delete() } }

    fun usage(): JSONObject = synchronized(this) {
        JSONObject().put("bootId", bootTag).put("count", ledger.count).put("maxCount", ledger.maxCount)
            .put("bytes", ledger.totalBytes).put("maxBytes", ledger.maxBytes).put("ttlSeconds", ledger.ttlMs / 1000)
    }

    fun ageSeconds(entry: CaptureStoreLedger.Entry) = (now() - entry.createdAt) / 1000
    @Synchronized fun expiresInSeconds(entry: CaptureStoreLedger.Entry) = maxOf(0, (ledger.expiresAt(entry) - now()) / 1000)

    /** Writes the JPEG, lets [describe] compute metadata from the stored file, then registers the handle. */
    fun save(requestId: String?, jpeg: ByteArray, describe: (File) -> JSONObject): CaptureStoreLedger.Entry {
        val handle = "$bootTag-${hex(4)}"
        val target = file(handle)
        try {
            target.writeBytes(jpeg)
            val sha = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
            val metadata = describe(target).toString()
            val time = now()
            val entry = CaptureStoreLedger.Entry(handle, requestId, jpeg.size.toLong(), sha, time, time, metadata)
            synchronized(this) { ledger.add(entry) }
            return entry
        } catch (e: Exception) {
            target.delete(); throw e
        }
    }

    /** The token is unguessable and is the only credential of a download link; never log it. */
    fun createLink(handle: String, ttlSeconds: Long, oneTime: Boolean): String? = synchronized(this) {
        val token = hex(16)
        if (ledger.createLink(token, handle, now(), ttlSeconds * 1000, oneTime)) token else null
    }

    class Download(val outcome: CaptureStoreLedger.LinkOutcome, val bytes: ByteArray? = null)

    fun openLink(token: String, rangeHeader: String?): Download {
        val outcome = synchronized(this) { ledger.sweep(now()).forEach { file(it.handle).delete() }; ledger.redeem(token, now(), rangeHeader) }
        if (outcome !is CaptureStoreLedger.LinkOutcome.Served) return Download(outcome)
        return try {
            val length = (outcome.end - outcome.start + 1).toInt()
            val slice = ByteArray(length)
            RandomAccessFile(file(outcome.entry.handle), "r").use { it.seek(outcome.start); it.readFully(slice) }
            Download(outcome, slice)
        } catch (e: java.io.IOException) {
            Download(CaptureStoreLedger.LinkOutcome.Invalid)
        } finally {
            // The bytes are already in memory; a one-time link that delivered the last byte also ends the photo.
            if (outcome.delivered) file(outcome.entry.handle).delete()
        }
    }
}
