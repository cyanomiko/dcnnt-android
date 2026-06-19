package net.dcnnt.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64

/**
 * Any transport or network error
 * @param message - short error message for logging, debug and (sometimes) UI
 */
open class DCTransportError(message: String): DCException(message)

/**
 * Connection timeout
 * @param message - short error message for logging, debug and (sometimes) UI
 */
open class DCTransportTimeout(message: String = "Transport timeout"): DCTransportError(message)

/**
 * Device is assumed offline
 * @param device - device object
 */
open class DCOfflineError(device: Device): DCTransportError("Device '$device' (${device.uin}) is offline")

/**
 * Object which can search devices, create connections to devices and listen for incoming connections
 */
abstract class Transport(val defaultTimeoutMilliseconds: Int) {
    abstract val TRANSPORT_NAME: String

//    /**
//     * Get persistent unique ID for transport config to store or load data
//     * @return short string
//     */
//    abstract fun getId(): String

    /**
     * Search device in current network
     * @return list of found devices
     */
    abstract fun search(): List<Device>

    /**
     * Establish connection to dcnnt device
     * @return ready connection object
     */
    abstract fun connect(device: Device): Connection

    /**
     * Wait for connection from any dcnnt device
     * @return ready connection object
     */
    abstract fun listen(): Connection
}

/**
 * Object which can reliable send/receive bytes to/from connected device
 */
interface Connection {
    val transport: Transport
    /**
     * Reliable send all bytes to connected device
     * @param data - data to send
     */
    fun send(data: ByteArray, timeoutMilliseconds: Int? = null)

    /**
     * Reliable read some count of bytes from connected device
     * @param length - number of bytes to read
     */
    fun recv(length: Int, timeoutMilliseconds: Int? = null): ByteArray

    /**
     * Just close this connection
     */
    fun close()
}


/**
 * Any protocol error in DC app
 * @param message - short error message for logging, debug and (sometimes) UI
 */
open class DCProtocolError(message: String): DCException(message)


enum class DeviceRole(val mask: UByte) {
    CLIENT(0b00000001u),
    SERVER(0b00000010u),
    PROXY(0b00000100u),
}

enum class DeviceSearchAction {
    REQUEST,
    RESPONSE
}

/**
 * Device search message in legacy JSON format
 */
class LegacySearchMessage(
    val uin: Uin,
    val name: String,
    val action: DeviceSearchAction = DeviceSearchAction.REQUEST,
    val role: DeviceRole = DeviceRole.CLIENT,
    val encryptedPairingData: ByteArray? = null
): JsonSerializable() {

    companion object: JsonDeserializer<LegacySearchMessage>() {
        const val PLUGIN_NAME: String = "search"
        override fun fromJson(j: JSONObject): LegacySearchMessage {
            if (!((j["plugin"] == PLUGIN_NAME) and (j["action"] is String) and (j["uin"] is Int)
                        and (j["name"] is String) and (j["role"] is String))) {
                throw DCProtocolError("Incorrect request content")
            }
            val uin: Uin
            val action: DeviceSearchAction
            val role: DeviceRole
            try {
                uin = j.getInt("uin")
                action = DeviceSearchAction.valueOf(j.getString("action").uppercase())
                role = DeviceRole.valueOf(j.getString("role").uppercase())
            } catch (e: Exception) {
                throw DCProtocolError("Incorrect search message ($e)")
            }
            val pairingValue: ByteArray = Base64.decode(j.optString("pair"))
            return LegacySearchMessage(uin, j.getString("name"), action, role, if (pairingValue.isEmpty()) null else pairingValue)
        }
    }

    val roleName: String
        get() = role.name.lowercase()

    override fun toJson(): JSONObject {
        val buf = mutableMapOf(
            "plugin" to PLUGIN_NAME,
            "action" to action.name.lowercase(),
            "role" to role.name.lowercase(),
            "uin" to uin,
            "name" to name
        )
        if (encryptedPairingData != null) {
            buf["pair"] = Base64.encode(encryptedPairingData)
        }
        return JSONObject(buf)
    }
}

/**
 * Binary version of search message
 * @param ver - version info, 8 bytes, all zeros now.
 * @param enc - encryption and encoding method, 8 bytes, all zeros now.
 * @param dst - destination UIN, 4 bytes, 32-bit unsigned integer in big-endian.
 * @param src - source UIN, 4 bytes, 32-bit unsigned integer in big-endian.
 * @param name - sender name in UTF-8
 * @param encryptedPairingData - password encrypted with pairing code as key
 */
class SearchMessage(
    val ver: ULong,
    val enc: ULong,
    val dst: Uin,
    val src: Uin,
    val name: String,
    val encryptedPairingData: ByteArray? = null
): BytesSerializable {
    companion object: BytesDeserializer<SearchMessage> {
        override fun fromByteArray(a: ByteArray): SearchMessage {
            val buf = ByteBuffer.wrap(a)
            val ver = buf.getLong().toULong()
            val enc = buf.getLong().toULong()
            val dst = buf.getInt()
            val src = buf.getInt()
            val nameBytes = ByteArray(buf.getInt())
            buf.get(nameBytes)
            var encryptedPairingData: ByteArray? = null
            if (buf.hasRemaining()) {
                encryptedPairingData = ByteArray(buf.getInt())
                buf.get(encryptedPairingData)
            }
            return SearchMessage(ver, enc, dst, src, nameBytes.decodeToString(), encryptedPairingData)
        }
    }

    override fun toByteArray(): ByteArray {
        val nameBytes = name.toByteArray()
        val buf = ByteBuffer.allocate(1024)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(ver.toLong())
            .putLong(enc.toLong())
            .putInt(dst)
            .putInt(src)
            .putInt(nameBytes.size)
            .put(nameBytes)
        if (encryptedPairingData != null) {
            buf.putInt(encryptedPairingData.size)
                .put(encryptedPairingData)
        }
        return buf.array()
    }
}


/**
 * Plugin code is 4 ASCII characters ID to determine plugin to process connection
 */
@JvmInline
value class PluginCode(val value: String) {
    companion object {
        const val PLUGIN_CODE_LENGTH = 4
    }

    override fun toString() = value

    init {
        require(value.length == PLUGIN_CODE_LENGTH) {
            "Plugin code should be 4-character length"
        }
        require(value.all { it.code < 127 }) {
            "Plugin code should contain ASCII characters only"
        }
    }
}


open class DCHandshakeError(message: String): DCProtocolError(message)
class DCAuthError(src: Uin, dst: Uin): DCHandshakeError("Authentication fail ($src -> $dst)")

/**
 * Handshake message on connection open
 * @param ver - version info, 8 bytes, all zeros now.
 * @param enc - encryption and encoding method, 8 bytes, all zeros now.
 * @param dst - destination UIN, 4 bytes, 32-bit unsigned integer in big-endian.
 * @param src - source UIN, 4 bytes, 32-bit unsigned integer in big-endian.
 * @param plg - encrypted plugin code of 4 ASCII characters, 36 bytes (depends on encryption method, but only one available now).
 */
class Handshake(
    val ver: ULong,
    val enc: ULong,
    val dst: Uin,
    val src: Uin,
    val plg: ByteArray
): BytesSerializable {
    init {
        val plgLength = EncryptionMethod.getPlgFieldSize(enc.toInt())
        require(plg.size == plgLength) {
            "Incorrect length of 'plg' field (${plg.size}/$plgLength)"
        }
    }
    companion object: BytesDeserializer<Handshake> {
        const val VER_LENGTH = 8
        const val ENC_LENGTH = 8
        const val DST_LENGTH = 4
        const val SRC_LENGTH = 4

        const val PLG_MIN_LENGTH = 4
        const val BASE_LENGTH = VER_LENGTH + ENC_LENGTH + DST_LENGTH + SRC_LENGTH
        const val TOTAL_MIN_LENGTH = BASE_LENGTH + PLG_MIN_LENGTH

        override fun fromByteArray(a: ByteArray): Handshake {
            if (a.size < TOTAL_MIN_LENGTH) {
                throw DCHandshakeError("Incorrect size of handshake message (${a.size} B of min $TOTAL_MIN_LENGTH B)")
            }
            val buf = ByteBuffer.wrap(a).order(ByteOrder.BIG_ENDIAN)
            val ver = buf.long.toULong()
            val enc = buf.long.toULong()
            val dst = buf.int
            val src = buf.int
            val plgLength = EncryptionMethod.getPlgFieldSize(enc.toInt())
            val handshakeLength = BASE_LENGTH + plgLength
            if (a.size != handshakeLength) {
                throw DCHandshakeError("Incomplete handshake message (${a.size} B of $handshakeLength B)")
            }
            val plg = ByteArray(plgLength)
            buf.get(plg)
            return Handshake(ver, enc, dst, src, plg)
        }

    }

    override fun toByteArray(): ByteArray {
        return ByteBuffer.allocate(BASE_LENGTH + EncryptionMethod.getPlgFieldSize(enc.toInt()))
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(ver.toLong())
            .putLong(enc.toLong())
            .putInt(dst)
            .putInt(src)
            .put(plg).array()
    }
}

/**
 * Main Device Connect protocol over stable connection
 */
class Proto(val localUin: Uin, val localPassword: String, val remote: Device, val connection: Connection, val encMethodCode: Int, val pluginCode: PluginCode) {
    val sendEnc = EncryptionMethod.new(
        deriveSendKey(
            localUin, remote.uin,
            localPassword, remote.password
        ), encMethodCode
    )
    val recvEnc = EncryptionMethod.new(
        deriveRecvKey(
            localUin, remote.uin,
            localPassword, remote.password
        ), encMethodCode
    )

    companion object {
        const val LENGTH_LENGTH: Int = 4
    }

    fun createPackedHandshake(): ByteArray {
        val plg = sendEnc.encrypt(pluginCode.value.encodeToByteArray())
        return Handshake(0UL, 0UL, remote.uin, localUin, plg).toByteArray()
    }

    fun connect() {
        connection.send(createPackedHandshake())
        val res = Handshake.fromByteArray(
            connection.recv(
                Handshake.BASE_LENGTH + getEncryptedPlgLength(encMethodCode)
            )
        )
        if (res.ver != 0UL) {
            throw DCHandshakeError("Unsupported handshake version (${res.ver})")
        }
        if (res.enc.toInt() != encMethodCode) {
            throw DCHandshakeError("Unsupported encryption method (${res.enc.toInt()})")
        }
        if (res.dst != localUin) {
            throw DCHandshakeError("Incorrect destination ${res.dst}, expected ${localUin}")
        }
        if (res.src != remote.uin) {
            throw DCHandshakeError("Incorrect source ${res.src}, expected ${remote.uin}")
        }
        val responsePluginCode: PluginCode
        try {
            responsePluginCode = PluginCode(recvEnc.decrypt(res.plg).decodeToString())
        } catch (_: DCDecryptionError) {
            throw DCAuthError(localUin, remote.uin)
        }
        if (responsePluginCode != pluginCode) {
            throw DCHandshakeError("Incorrect plugin code '$responsePluginCode', expected '$pluginCode'")
        }
    }

    /**
     * Send bytes to connected device
     * @param data - data to send
     */
    fun send(data: ByteArray) {
        val encryptedData = sendEnc.encrypt(data)
        connection.send(uIntToBytes(encryptedData.size))
        connection.send(encryptedData)
    }

    /**
     * Read message bytes from connected device
     */
    fun recv(): ByteArray {
        val encryptedDataLength = uIntFromBytes(connection.recv(LENGTH_LENGTH))
        return recvEnc.decrypt(connection.recv(encryptedDataLength))
    }
}
