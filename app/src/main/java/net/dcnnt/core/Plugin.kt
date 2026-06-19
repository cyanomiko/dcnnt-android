package net.dcnnt.core

import android.content.Context
import android.util.Log
import net.dcnnt.plugins.*
import org.json.JSONObject
import java.io.File


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

data class DCResult(val success: Boolean, val message: String, val data: Any? = null)

open class PluginException(message: String): Exception(message)

abstract class Plugin<T: PluginConf>(val app: App, val device: Device) {
    abstract val TAG: String
    abstract val MARK: String
    abstract val NAME: String
    lateinit var conf: T
    lateinit var transport: Transport
    lateinit var connection: Connection
    lateinit var proto: Proto

    fun send(data: ByteArray) = proto.send(data)

    fun read(): ByteArray = proto.recv()

    open fun init(context: Context? = null): Boolean {
        transport = TransportDynamicIP()
        conf = (app.pm.getConfig(MARK, device.uin) as? T) ?: return false
        return true
    }

    fun connect() {
        Log.i(TAG, "Connecting to ${device.ip}...")
        connection = transport.connect(device)
        proto = Proto(app.conf.uin.value, app.conf.password.value,
            device, connection, DefaultEncryption.CODE, PluginCode(MARK))
        proto.connect()
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

    fun rpcChecked(method: String, params: Map<String, Any?>): JSONObject {
        val response = (rpc(method, JSONObject(params)) as? JSONObject)
            ?: throw PluginException("Incorrect response")
        val code = response.optInt("code", 0)
        if (code != 0) {
            var errorText = response.optString("message")
            if (errorText.isEmpty()) {
                errorText = "Error ${code}"
            }
            throw PluginException(errorText)
        }
        return response
    }
}

class PluginManager(val app: App, val directory: String, private val pluginMarks: List<String>) {
    val TAG = "DC/PM"
    private val configs = mutableMapOf<Pair<String, Int>, PluginConf>()
    val dir = File(directory)
    val SUFFIX = ".conf.json"

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
        "open" -> OpenerPluginConf(directory, uin)
        "rcmd" -> RemoteCommandPluginConf(directory, uin)
        "clip" -> ClipboardPluginConf(directory, uin)
        "nots" -> NotificationsPluginConf(directory, uin)
        "sync" -> SyncPluginConf(directory, uin)
        else -> throw IllegalArgumentException("Unknown plugin mark")
    }.apply {
        load()
        configs[Pair(mark, uin)] = this
    }

    fun getConfig(mark: String, uin: Int) = configs[Pair(mark, uin)] ?: createConfig(mark, uin)
}
