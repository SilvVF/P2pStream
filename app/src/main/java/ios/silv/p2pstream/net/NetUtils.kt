package ios.silv.p2pstream.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetUtils {

    fun privateNetworkAddress(): InetAddress {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
        val addresses = interfaces.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .sortedBy { it.hostAddress }
        return if (addresses.isNotEmpty()) {
            addresses[0]
        } else {
            InetAddress.getLoopbackAddress()
        }
    }
}