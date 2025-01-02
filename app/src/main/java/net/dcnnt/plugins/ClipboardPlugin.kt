package net.dcnnt.plugins

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import net.dcnnt.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer


data class ClipboardInfo(val key: String, val name: String,
                         val isReadable: Boolean, val isWritable: Boolean)


class ClipboardPluginConf(directory: String, uin: Int): PluginConf(directory, "clip", uin)


class ClipboardPlugin(app: App, device: Device): Plugin<ClipboardPluginConf>(app, device) {
    override val TAG = "DC/Clipboard"
    override val MARK = "clip"
    override val NAME = "Clipboard"
    lateinit var context: Context

    override fun init(context: Context?): Boolean {
        super.init(context)
        this.context = context ?: throw PluginException("No context passed")
        return true
    }

    fun getClipboardText(context: Context): String? {
        val clipboardManager = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.primaryClip?.also {
            return it.getItemAt(0)?.coerceToText(context).toString()
        }
        return null
    }

    fun setClipboardText(context: Context, label: String, text: String) {
        val clipboardManager = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(
            ClipData(
                ClipDescription(label, listOf("text/plain").toTypedArray()),
                ClipData.Item(text)
            )
        )
    }

    fun getRemoteClipboardsList(): List<ClipboardInfo> {
        val result = mutableListOf<ClipboardInfo>()
        val response = (rpc("list", mapOf()) as? JSONArray) ?: throw PluginException("Incorrect response")
        for (i in 0 ..< response.length()) {
            response.optJSONObject(i).also { j ->
                val key = j.optString("key")
                if (key.isNotEmpty()) {
                    var name = j.optString("name")
                    if (name.isEmpty()) {
                        name = "Clipboard ${key}"
                    }
                    ClipboardInfo(j.optString("key"), name,
                        j.optBoolean("readable"), j.optBoolean("writeable")).also {
                        if (it.isReadable or it.isWritable) {
                            result.add(it)
                        }
                    }
                }
            }
        }
        return result
    }

    fun readRemoteClipboard(clipboard: ClipboardInfo): String {
        if (!clipboard.isReadable) {
            throw PluginException("Clipboard '${clipboard.name}' is not readable")
        }
        return rpcChecked("read", mapOf("clipboard" to clipboard.key)).optString("text")
    }

    fun writeRemoteClipboard(clipboard: ClipboardInfo, text: String): String {
        if (!clipboard.isWritable) {
            throw PluginException("Clipboard '${clipboard.name}' is not writeable")
        }
        return rpcChecked("write", mapOf("clipboard" to clipboard.key, "text" to text)).optString("message")
    }
}
