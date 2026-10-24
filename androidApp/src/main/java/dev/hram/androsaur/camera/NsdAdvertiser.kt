package dev.hram.androsaur.camera

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Announces the service as `_androsaur-camera._tcp` so other Androsaur apps find it without a fixed address. */
class NsdAdvertiser(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun start(port: Int) {
        val info = NsdServiceInfo().apply { serviceName = "androsaur-camera"; serviceType = "_androsaur-camera._tcp"; this.port = port }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.d(TAG, "mDNS registered as ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "mDNS registration failed: $errorCode") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = registration
        manager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    fun stop() {
        listener?.let { runCatching { manager?.unregisterService(it) } }
        listener = null
    }

    private companion object { const val TAG = "AndrosaurCamera" }
}
