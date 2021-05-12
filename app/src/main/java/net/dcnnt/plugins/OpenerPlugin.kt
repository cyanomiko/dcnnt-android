package net.dcnnt.plugins

import android.content.ContentResolver
import android.content.Context
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


class OpenerPluginConf(directory: String, uin: Int): PluginConf(directory, "open", uin)

class OpenerPlugin(app: App, device: Device): BaseFilePlugin<OpenerPluginConf>(app, device) {
    override val TAG = "DC/Open"
    override val MARK = "open"
    override val NAME = "Opener"
    lateinit var context: Context

    override fun init(context: Context?): Boolean {
        super.init(context)
        this.context = context ?: throw PluginException("No context passed")
        return true
    }

    fun openFile(file: FileEntry, contentResolver: ContentResolver,
                   progressCallback: (cur: Long, total: Long, part: Long) -> Unit): DCResult {
        APP.log("Open file '${file.name}' (${file.localUri}) on device ${device.uin}")
        return sendFile(file, contentResolver, progressCallback, "open_file")
    }

    fun openLink(url: String): DCResult {
        APP.log("Open link '$url' on device ${device.uin}")
        rpc("open_link", mapOf("link" to url))
        return DCResult(true, "OK")
    }
}
