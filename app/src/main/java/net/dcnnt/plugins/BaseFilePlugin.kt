package net.dcnnt.plugins

import android.content.ContentResolver
import android.util.Log
import net.dcnnt.core.*
import org.json.JSONObject
import java.io.ByteArrayInputStream


abstract class BaseFilePlugin<T: PluginConf>(app: App, device: Device):
    Plugin<T>(app, device) {
    var breakTransfer = false

    companion object {
        const val PART = 65532
    }

    fun sendFile(file: FileEntry, contentResolver: ContentResolver,
                 progressCallback: (cur: Long, total: Long, part: Long) -> Unit,
                 rpcMethod: String = "upload"): DCResult {
        val inp = if (file.data != null) {
            ByteArrayInputStream(file.data)
        } else {
            val uri = file.localUri ?: return DCResult(false, "No URI presented")
            contentResolver.openInputStream(uri) ?: return DCResult(false, "URI open fail")
        }
        val size = file.size
        val resp = (rpc(rpcMethod, mapOf("name" to file.name, "size" to size))
                as? JSONObject) ?: return DCResult(false,"Incorrect response")
        Log.d(TAG, "$resp")
        var sentBytes: Long = 0
        var chunkSize = 0
        val buf = ByteArray(PART)
        if (resp.getInt("code") != 0) return DCResult(false,"Rejected by server")
        while (true) {
            if (breakTransfer) {
                send(emptyList<Byte>().toByteArray())
                rpcSend("cancel", emptyMap())
                break
            }
            chunkSize = inp.read(buf)
            if (chunkSize == -1) break
            if (chunkSize != 0) {
                send(buf.take(chunkSize).toByteArray())
                sentBytes += chunkSize
                progressCallback(sentBytes, size, PART.toLong())
            }
        }
        val n = rpcReadNotification() as? JSONObject ?: return DCResult(false, "Fail")
        if (n.getInt("code") == 0) return DCResult(true, "OK")
        if (n.getInt("code") == 1) return DCResult(false, "Canceled")
        return DCResult(false,"Not confirmed")
    }

}