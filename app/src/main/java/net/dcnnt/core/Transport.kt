package net.dcnnt.core

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException


open class TransportDynamicIP(
    defaultTimeoutMilliseconds: Int = 3000,
    val tcpPort: Int = DEFAULT_TCP_PORT,
    val udpPort: Int = DEFAULT_UDP_PORT
): Transport(defaultTimeoutMilliseconds) {
    override val TRANSPORT_NAME: String = "dynamic_ip"

//    override fun getId(): String = "$TRANSPORT_NAME/$tcpPort/$udpPort/$defaultTimeoutMilliseconds"

    val ipByUin: MutableMap<Uin, String> = mutableMapOf()

    companion object {
        val DEFAULT_TCP_PORT = 5040
        val DEFAULT_UDP_PORT = 5040
    }

    /**
     * Create TCP connection directly to IP
     * @param ip - IP address or hostname
     * @return ConnectionTCP object
     */
    protected fun connectBase(ip: String): ConnectionTCP {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ip, tcpPort), defaultTimeoutMilliseconds)
        } catch (e: SocketTimeoutException) {
            throw DCTransportTimeout("TCP connection timeout ($defaultTimeoutMilliseconds ms)")
        } catch (e: Exception) {
            throw DCTransportError("$e")
        }
        return ConnectionTCP(this, socket)
    }

    fun getBroadcastAddresses(): List<String> {
        val res = mutableListOf("255.255.255.255")
        NetworkInterface.getNetworkInterfaces().toList().forEach {
            if (it.isUp and (!it.isVirtual) and (!it.isLoopback)) {
                it.interfaceAddresses.forEach { a ->
                    a.broadcast?.also { b ->
                        val ip = "${b.hostName}"
                        if (ip.count { c -> c == '.' } == 3) {
                            res.add(ip)
                        }
                    }
                }
            }
        }
        return res
    }

    override fun search(): List<Device> {
        TODO("Not yet implemented")
    }

    override fun connect(device: Device): ConnectionTCP =
        connectBase(ipByUin[device.uin] ?: throw DCOfflineError(device))

    override fun listen(): Connection {
        TODO("Not yet implemented")
    }

//    override fun toJson(): JSONObject {
//        return JSONObject(mapOf(
//            "transport_name" to TRANSPORT_NAME,
//            "default_timeout_ms" to defaultTimeoutMilliseconds,
//            "tcp_port" to tcpPort,
//            "udp_port" to udpPort,
//        ))
//    }

}

class TransportStaticIP(
    val host: String,
    defaultTimeoutMilliseconds: Int = 3000,
    tcpPort: Int = DEFAULT_TCP_PORT,
    udpPort: Int = DEFAULT_UDP_PORT
): TransportDynamicIP(defaultTimeoutMilliseconds, tcpPort, udpPort) {
    override val TRANSPORT_NAME: String = "static_ip"

//    override fun getId(): String = "$TRANSPORT_NAME/$tcpPort/$udpPort/$defaultTimeoutMilliseconds/$host"
//
//    override fun toJson(): JSONObject {
//        return super.toJson().apply {
//            this.put("host", host)
//        }
//    }

    override fun connect(device: Device): ConnectionTCP = connectBase(host)


}

/**
 * TCP network connection
 */
class ConnectionTCP(override val transport: Transport, val socket: Socket): Connection {

    override fun send(data: ByteArray, timeoutMilliseconds: Int?) {
        val out = socket.outputStream
        out.write(data)
        out.flush()
    }

    override fun recv(length: Int, timeoutMilliseconds: Int?): ByteArray = ByteArray(length).apply {
        val timeoutMs = timeoutMilliseconds ?: transport.defaultTimeoutMilliseconds
        var offset = 0
        val start = System.currentTimeMillis()
        while (offset < length) {
            offset += socket.inputStream.read(this, offset, length - offset)
            if ((System.currentTimeMillis() - start) > timeoutMs) {
                throw DCTransportTimeout("Network timeout ($timeoutMs ms)")
            }
        }
    }
}
