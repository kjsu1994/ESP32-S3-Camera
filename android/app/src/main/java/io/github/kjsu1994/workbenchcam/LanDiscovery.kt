package io.github.kjsu1994.workbenchcam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object LanDiscovery {
    fun findActiveWifiNetwork(connectivity: ConnectivityManager): Network? {
        val active = connectivity.activeNetwork
        if (active != null) {
            val caps = connectivity.getNetworkCapabilities(active)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return active
        }
        return null
    }

    fun discoverOnLan(
        context: Context,
        wifiNetwork: Network,
        lastKnownIp: String?,
        manualIp: String?
    ): BoardTarget? {
        // 1. Try explicit manual IP or last known STA IP first (sub-second hit).
        val candidates = linkedSetOf<String>()
        if (!manualIp.isNullOrBlank() && isAllowedLocalHost(manualIp)) candidates += manualIp.trim()
        if (!lastKnownIp.isNullOrBlank() && isAllowedLocalHost(lastKnownIp)) candidates += lastKnownIp.trim()
        for (ip in candidates) {
            val target = BoardTarget(wifiNetwork, ip, viaLan = true)
            if (probeBoard(target)) return target
        }

        // 2. Try direct mDNS hostname resolution (supported natively on Android 12+).
        val resolved = runCatching {
            wifiNetwork.getAllByName("dockycam.local")
                .firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull() ?: runCatching {
            InetAddress.getAllByName("dockycam.local")
                .firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()
        if (!resolved.isNullOrBlank() && isAllowedLocalHost(resolved)) {
            val target = BoardTarget(wifiNetwork, resolved, viaLan = true)
            if (probeBoard(target)) return target
        }

        // 3. Fallback to Android NsdManager mDNS browsing (_http._tcp.).
        val nsdIp = discoverViaNsd(context)
        if (!nsdIp.isNullOrBlank() && isAllowedLocalHost(nsdIp)) {
            val target = BoardTarget(wifiNetwork, nsdIp, viaLan = true)
            if (probeBoard(target)) return target
        }
        return null
    }

    private fun probeBoard(target: BoardTarget): Boolean {
        return runCatching {
            val st = BoardApi(target).getWifiStatus(timeoutMs = 1100)
            st.host.contains("dockycam", ignoreCase = true) ||
                st.host.contains("workbenchcam", ignoreCase = true)
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun discoverViaNsd(context: Context): String? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val foundIp = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val matched = serviceInfo.serviceName.contains("dockycam", ignoreCase = true) ||
                    serviceInfo.serviceName.contains("workbenchcam", ignoreCase = true)
                if (!matched) return
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val addr = info.host?.hostAddress
                            if (!addr.isNullOrBlank() && foundIp.compareAndSet(null, addr)) {
                                latch.countDown()
                            }
                        }
                    })
                }
            }
        }
        return try {
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(1800, TimeUnit.MILLISECONDS)
            foundIp.get()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}
