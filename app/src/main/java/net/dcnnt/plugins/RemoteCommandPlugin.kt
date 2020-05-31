package net.dcnnt.plugins

import android.util.Log
import net.dcnnt.core.*
import org.json.JSONArray
import org.json.JSONObject


data class RemoteCommand(val name: String, val description: String, val index: String?)


class RemoteCommandPluginConf(directory: String, uin: Int): PluginConf(directory, "rcmd", uin)


class RemoteCommandPlugin(app: App, device: Device): Plugin<RemoteCommandPluginConf>(app, device) {
    override val TAG = "DC/RCMD"
    override val MARK = "rcmd"
    override val NAME = "Remote Commands"

    override fun init(): Boolean {
        super.init()
        return true
    }

    fun list(): List<RemoteCommand> {
        val res = (rpc("list", mapOf()) as? JSONArray) ?: return listOf()
        val commandsList = mutableListOf<RemoteCommand>()
        for (i in 0 until res.length()) {
            (res[i] as? JSONObject)?.apply {
                val indexStr: String = getString("index")
                var index: String? = null
                if ((indexStr.isNotEmpty()) and (indexStr != "null")) index = indexStr
                Log.d(TAG, "index = $index, isNull = ${index == null}")
                commandsList.add(RemoteCommand(
                    optString("name", "NULL"),
                    optString("description", "NULL"),
                    index
                ))
            }
        }
        return commandsList
    }

    fun exec(index: String): DCResult {
        (rpc("exec", mapOf("index" to index)) as? JSONObject)?.also { res ->
            if (res.has("result") and res.has("message"))
                return DCResult(res.getBoolean("result"), res.getString("message"))
            return DCResult(false, "Incorrect response fields")
        }
        return DCResult(false, "Incorrect response format")
    }
}
