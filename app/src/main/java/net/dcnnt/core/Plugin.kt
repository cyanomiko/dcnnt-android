package net.dcnnt.core

import android.util.Log
import net.dcnnt.plugins.FileTransferPluginConf
import net.dcnnt.plugins.NotificationsPluginConf
import net.dcnnt.plugins.RemoteCommandPluginConf
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder


abstract class PluginConf(directory: String, val mark: String, uin: Int, val base: PluginConf? = null):
    DCConf("$directory/${(if (uin == 0) {""} else {"$uin."})}$mark.conf.json") {
    override val confName = mark
    val uin = IntEntry(this, "uin", uin, uin, uin).init()

    override fun loadEntries(json: JSONObject) {
        entries.forEach {
            val value = json.opt(it.name)
            if (it.check(value) and (value != null)) {
                Log.i(TAG, "Config '$confName': extract ${it.name} = $value - ok")
                it.setValue(value as Any)
            } else {
                Log.w(TAG, "Config '$confName': extract ${it.name} = $value - fall back to default (${it.default})")
                it.setValue(base?.entriesByName?.get(it.name)?.value)
                needDump = true
            }
        }
    }

    override fun loadExtra(json: JSONObject) {
        val entriesNames = List(entries.size) { entries[it].name }
        base?.also { b -> b.extra.keys().forEach {
            if (!entriesNames.contains(it)) extra.put(it, b.extra.opt(it))
        } }
        json.keys().forEach { if (!entriesNames.contains(it)) extra.put(it, json.opt(it)) }
    }
}

class PluginConnectionHeader (val preamble: ByteArray,
                              val plugin: ByteArray,
                              val dst: Int,
                              val src: Int)

data class DCResult(val success: Boolean, val message: String, val data: Any? = null)

class DCTimeoutException(message:String): Exception(message)
class DCAuthException(message:String): Exception(message)

abstract class Plugin<T: PluginConf>(val app: App, val device: Device) {
    abstract val TAG: String
    abstract val MARK: String
    abstract val NAME: String
    lateinit var conf: T
    lateinit var byteMark: ByteArray
    lateinit var sock: Socket

    fun createHeader(): ByteArray {
        val encPlg = encrypt(byteMark, device.keySend)
        return ByteBuffer.allocate(60)
            .order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            .putInt(device.uin)
            .putInt(app.conf.uin.value)
            .put(encPlg).array()
    }

    fun parseHeader(raw: ByteArray): PluginConnectionHeader {
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val preamble = ByteArray(16)
        val pluginMarkEncrypted = ByteArray(36)
        buf.get(preamble)
        val src = buf.int
        val dst = buf.int
        buf.get(pluginMarkEncrypted)
        val pluginMark = decrypt(pluginMarkEncrypted, device.keyRecv) ?: throw DCAuthException("Auth fail")
        return PluginConnectionHeader(preamble, pluginMark, src, dst)
    }

    fun sendAll(data: ByteArray) {
        val out = sock.outputStream
        out.write(data)
        out.flush()
    }

    fun readAll(length: Int, timeoutMillis: Long = 30000): ByteArray = ByteArray(length).apply {
        var offset = 0
        val start = System.currentTimeMillis()
        while (offset < length) {
            offset += sock.inputStream.read(this, offset, length - offset)
            if ((System.currentTimeMillis() - start) > timeoutMillis) {
                throw DCTimeoutException("Connection timeout")
            }
        }
    }

    fun send(data: ByteArray) {
        sendAll(uIntToBytes(data.size + 32) + encrypt(data, device.keySend))
    }

    fun read(): ByteArray {
        val lb = readAll(4)
        Log.d(TAG, "Length bytes: [${lb[0]} ${lb[1]} ${lb[2]} ${lb[3]}]")
        val length = uIntFromBytes(lb)
        Log.d(TAG, "Length to read = $length")
        return decrypt(readAll(length), device.keyRecv) ?: byteArrayOf()
    }

    open fun init(): Boolean {
        byteMark = MARK.toByteArray()
        conf = (app.pm.getConfig(MARK, device.uin) as? T) ?: return false
        return true
    }

    fun connect(): Boolean {
        if (device.ip == null) {
            Log.w(TAG, "Device ${device.uin} is offline")
            return false
        }
        Log.i(TAG, "Connecting to ${device.ip}...")
        sock = Socket(device.ip, PORT)
        Log.i(TAG, "TCP - OK, send header...")
        sendAll(createHeader())
        Log.i(TAG, "Waiting header response...")
        val res = parseHeader(readAll(60))
        Log.d(TAG, "${res.preamble}")
        if (!res.preamble.contentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))) {
            Log.w(TAG, "Only zero preamble allowed")
            return false
        }
        if (res.dst != app.conf.uin.value) {
            Log.w(TAG, "Incorrect destination ${res.dst}, expected ${app.conf.uin.value}")
            return false
        }
        if (res.src != device.uin) {
            Log.w(TAG, "Incorrect source ${res.src}, expected ${device.uin}")
            return false
        }
        if (!res.plugin.contentEquals(byteMark)) {
            Log.w(TAG, "Incorrect plugin mark ${res.plugin}, expected $MARK")
            return false
        }
        Log.i(TAG, "Connection established!")
        return true
    }

    fun rpcReadNotification(): Any? {
        try {
            return JSONObject(read().toString(Charsets.UTF_8)).opt("result")
        } catch (e: Exception) {
            Log.e(TAG, "$e")
        }
        return null
    }

    fun rpcSend(method: String, params: JSONObject, id: Int? = null) {
        send(JSONObject(mapOf(
            "jsonrpc" to "2.0",
            "method" to method,
            "params" to params
        )).apply { if (id is Int) put("id", id) }.toString().toByteArray())
    }

    fun rpcSend(method: String, params: Map<String, Any?>, id: Int? = null) {
        rpcSend(method, JSONObject(params), id)
    }

    fun rpc(method: String, params: JSONObject): Any? {
        rpcSend(method, params, 1)
        Log.d(TAG, "req = $method$params")
        try {
            return JSONObject(read().toString(Charsets.UTF_8)).opt("result")
        } catch (e: Exception) {
            Log.e(TAG, "$e")
        }
        return null
    }

    fun rpc(method: String, params: Map<String, Any?>): Any? {
        return rpc(method, JSONObject(params))
    }
}


class PluginManager(val app: App, val directory: String, private val pluginMarks: List<String>) {
    val TAG = "DC/PM"
    private val configs = mutableMapOf<Pair<String, Int>, PluginConf>()
    val dir = File(directory)

    fun init() {
        if (!dir.exists()) dir.mkdirs()
    }

    fun load() {
        pluginMarks.forEach { mark ->
            app.dm.devices.keys.forEach { uin ->
                createConfig(mark, uin)
            }
        }
    }

    private fun createConfig(mark: String, uin: Int): PluginConf = when(mark) {
        "file" -> FileTransferPluginConf(directory, uin)
        "rcmd" -> RemoteCommandPluginConf(directory, uin)
        "nots" -> NotificationsPluginConf(directory, uin)
        else -> throw IllegalArgumentException("Unknown plugin mark")
    }.apply {
        load()
        configs[Pair(mark, uin)] = this
    }

    fun getConfig(mark: String, uin: Int) = configs[Pair(mark, uin)] ?: createConfig(mark, uin)
}
