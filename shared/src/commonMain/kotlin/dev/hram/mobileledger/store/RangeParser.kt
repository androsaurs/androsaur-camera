package dev.hram.mobileledger.store

/** Single-range subset of RFC 9110 `Range: bytes=...`. Anything else is ignored and served in full. */
object RangeParser {
    sealed class Result {
        object Full : Result()
        data class Partial(val start: Long, val end: Long) : Result()
        object Unsatisfiable : Result()
    }

    fun parse(header: String?, total: Long): Result {
        val spec = header?.trim()?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")?.trim() ?: return Result.Full
        if (',' in spec || total <= 0) return Result.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return Result.Full
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()
        if (first.isEmpty()) {
            val suffix = last.toLongOrNull()?.takeIf { it >= 0 } ?: return Result.Full
            return if (suffix == 0L) Result.Unsatisfiable else Result.Partial(maxOf(0, total - suffix), total - 1)
        }
        val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return Result.Full
        val end = if (last.isEmpty()) Long.MAX_VALUE else last.toLongOrNull() ?: return Result.Full
        if (end < start) return Result.Full
        if (start >= total) return Result.Unsatisfiable
        return Result.Partial(start, minOf(end, total - 1))
    }
}
