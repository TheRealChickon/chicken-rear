package dev.kb896.aiusage.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.kb896.aiusage.data.PanelConfig

/**
 * "At home", for the always-on rule's home detection.
 *
 * HOME IS A WI-FI NETWORK HE MARKS ("This Wi-Fi is home", pressed while on it). The network is
 * known by what any app may read of it without a permission: its gateway and its name servers.
 * Its NAME is not used: reading a Wi-Fi's name needs the location permission, and nothing here is
 * worth asking for that. (So another network with the very same router address and name servers
 * would count as home too: unlikely, and harmless.)
 */
object HomeNet {
    /** The Wi-Fi the phone is on now, as [PanelConfig.homeNet] keeps it; null when it is on none. */
    fun current(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } ?: return null
        val link = cm.getLinkProperties(net) ?: return null
        val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress ?: return null
        identity(gateway, link.dnsServers.mapNotNull { it.hostAddress })
    }.getOrNull()

    /** A network's identity from its [gateway] and name servers: the same whatever order the servers come in. */
    fun identity(gateway: String, dns: List<String>): String = "gw:$gateway|dns:${dns.sorted().joinToString(",")}"

    fun atHome(config: PanelConfig, context: Context): Boolean =
        config.homeDetect && config.homeNet.isNotEmpty() && current(context) == config.homeNet

    /** About to sleep: the night's hours ([NIGHT_FROM] to [NIGHT_TO]), or Do Not Disturb on at any hour. */
    fun bedtime(hourOfDay: Int, doNotDisturb: Boolean): Boolean = doNotDisturb || hourOfDay >= NIGHT_FROM || hourOfDay < NIGHT_TO

    const val NIGHT_FROM = 22
    const val NIGHT_TO = 7
}
