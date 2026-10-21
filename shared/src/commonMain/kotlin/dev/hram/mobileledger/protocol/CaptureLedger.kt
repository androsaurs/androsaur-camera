package dev.hram.mobileledger.protocol

/** Transport-independent state. Caller serializes access. Payloads remain platform-owned. */
class CaptureLedger {
    data class Request(val id: String, val client: String)
    enum class Decision { START, REPLAY, BUSY, FINISHED, CONFLICT }
    private val finished = mutableListOf<Request>()
    var current: Request? = null
        private set
    var capturing: Boolean = false
        private set

    fun existing(request: Request): Decision? {
        val previous = finished.firstOrNull { it.id == request.id }
        if (previous != null) return if (previous.client == request.client) Decision.FINISHED else Decision.CONFLICT
        if (current?.id == request.id) return if (current == request) Decision.REPLAY else Decision.CONFLICT
        return null
    }

    fun accept(request: Request): Decision {
        existing(request)?.let { return it }
        if (capturing) return Decision.BUSY
        current?.let { remember(it) }
        current = request
        capturing = true
        return Decision.START
    }

    fun captured(request: Request): Boolean {
        if (current != request) return false
        capturing = false
        return true
    }

    fun finish(request: Request): Boolean {
        if (current != request) return false
        remember(request)
        current = null
        // Cancellation during exposure keeps the camera busy until callback returns.
        return true
    }

    fun exposureEnded() { capturing = false }
    private fun remember(request: Request) {
        finished += request
        if (finished.size > 256) finished.removeAt(0)
    }
}
