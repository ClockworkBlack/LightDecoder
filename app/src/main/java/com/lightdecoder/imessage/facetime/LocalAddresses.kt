package com.lightdecoder.imessage.facetime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * This phone's own network addresses, which the library offers the other side as places to send a
 * call's sound and picture to (`setLocalInterfaces`). It must be told them before any call is
 * placed or joined: until then its first use of them takes the process down. As OpenBubbles 2.0.0
 * picks them: the IPv4 addresses of the interfaces that are up and carry traffic (Wi-Fi, mobile
 * data, Ethernet, tethering), none of loopback or multicast; the "default" (which the library only
 * logs) is the active network's. An empty list is told too: a call then has no address to offer,
 * but nothing breaks.
 */
object LocalAddresses {

    /** One interface, as [pick] needs it. */
    data class Iface(val name: String, val up: Boolean, val loopback: Boolean, val ipv4: List<String>)

    /** The addresses to tell, and the default one ("" for none). */
    data class Picked(val default: String, val all: List<String>)

    /** The interface name prefixes whose addresses are told. */
    private val PREFIXES = listOf("wlan", "rmnet", "v4-", "eth", "p2p", "rndis")

    /** Pure: which of [interfaces]' addresses are told, [activeName] being the active network's interface (null: none known). */
    fun pick(interfaces: List<Iface>, activeName: String?): Picked {
        val byName = LinkedHashMap<String, List<String>>()
        for (i in interfaces) {
            if (!i.up || i.loopback) continue
            val name = i.name.lowercase(Locale.ROOT)
            if (PREFIXES.none { name.startsWith(it) }) continue
            val usable = i.ipv4.filter(::usable)
            if (usable.isNotEmpty()) byName[i.name] = (byName[i.name].orEmpty() + usable).distinct()
        }
        val all = byName.values.flatten().distinct().take(MAX_ADDRESSES)
        // The active network's own address; on a network that reaches IPv4 through a translation, that one's ("v4-...").
        val active = activeName?.let { byName[it] ?: byName["v4-$it"] }?.firstOrNull()
        return Picked(active ?: all.firstOrNull().orEmpty(), all)
    }

    /** A dotted IPv4 address that is neither loopback, multicast nor "any". */
    private fun usable(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] != 127 && parts[0] !in 224..255 && parts[0] != 0
    }

    /** As they are now. Reads the interfaces: not for the main thread. */
    fun current(context: Context): Picked {
        val interfaces = ArrayList<Iface>()
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all != null && all.hasMoreElements()) {
                val n = all.nextElement()
                val addresses = n.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                interfaces += Iface(n.name.orEmpty(), runCatching { n.isUp }.getOrDefault(false), runCatching { n.isLoopback }.getOrDefault(true), addresses)
            }
        } catch (e: Exception) {
            FaceTimeLog.note("this phone's network addresses could not be read (${e::class.java.simpleName})")
        }
        val active = try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.getLinkProperties(cm.activeNetwork)?.interfaceName
        } catch (e: RuntimeException) {
            null
        }
        return pick(interfaces, active)
    }

    /** The most addresses told. */
    const val MAX_ADDRESSES = 16

    /**
     * Calls [changed] (on the main thread) [DEBOUNCE_MS] after the phone's networks last changed: the
     * library is then told the addresses again, also in the middle of a call.
     */
    class Watcher(context: Context, private val changed: () -> Unit) {
        private val app = context.applicationContext
        private val handler = Handler(Looper.getMainLooper())
        private val run = Runnable { changed() }
        private var registered = false

        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = debounce()
            override fun onLost(network: Network) = debounce()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = debounce()
        }

        private fun debounce() {
            handler.removeCallbacks(run)
            handler.postDelayed(run, DEBOUNCE_MS)
        }

        @Synchronized
        fun start() {
            if (registered) return
            try {
                app.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
                registered = true
            } catch (e: RuntimeException) {
                FaceTimeLog.note("network changes cannot be watched (${e::class.java.simpleName})")
            }
        }

        @Synchronized
        fun stop() {
            handler.removeCallbacks(run)
            if (!registered) return
            registered = false
            try {
                app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                // Not registered any more.
            }
        }

        companion object {
            const val DEBOUNCE_MS = 250L
        }
    }
}
