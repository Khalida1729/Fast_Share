package com.kashif1729.fastshare.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.kashif1729.fastshare.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class NsdDiscovery(private val context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    val deviceId: String = context.getSharedPreferences("identity", 0).let { p ->
        p.getString("id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
    }
    private val deviceName get() = context.getSharedPreferences("identity", 0).getString("name", "My Android") ?: "My Android"
    private var reg: NsdManager.RegistrationListener? = null
    private var disc: NsdManager.DiscoveryListener? = null

    fun start(port: Int) {
        stop()
        val service = NsdServiceInfo().apply {
            serviceName = "FastShare-$deviceName"
            serviceType = TransferProtocol.SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
        }
        reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, reg)
        disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith("FastShare-")) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val id = info.attributes["id"]?.toString(Charsets.UTF_8) ?: return
                        if (id == deviceId) return
                        val name = info.attributes["name"]?.toString(Charsets.UTF_8) ?: info.serviceName.removePrefix("FastShare-")
                        val host = info.host?.hostAddress ?: return
                        _devices.value = (_devices.value.filterNot { it.id == id } + Device(id, name, host, info.port))
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) { _devices.value = _devices.value.filterNot { it.name == info.serviceName.removePrefix("FastShare-") } }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { try { nsd.stopServiceDiscovery(this) } catch (_: Exception) {} }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        nsd.discoverServices(TransferProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
    }
    fun stop() {
        try { disc?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { reg?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        disc = null; reg = null
    }
}
