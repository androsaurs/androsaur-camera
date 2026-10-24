package dev.hram.androsaur.camera.calibration

/** Browser leases are independent: closing one tab must not stop another tab's calibration. */
class CalibrationSessions(private val leaseMs: Long = 12_000, private val limit: Int = 16) {
    private val clients = mutableMapOf<String, Long>()
    fun update(client: String, active: Boolean, now: Long): Boolean {
        expire(now)
        if (!active) { clients.remove(client); return true }
        if (client !in clients && clients.size >= limit) return false
        clients[client] = now
        return true
    }
    fun hasClients(now: Long): Boolean { expire(now); return clients.isNotEmpty() }
    private fun expire(now: Long) {
        clients.entries.removeAll { now - it.value >= leaseMs || now < it.value }
    }
}
