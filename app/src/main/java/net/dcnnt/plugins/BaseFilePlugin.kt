package net.dcnnt.plugins

import android.content.ContentResolver
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import net.dcnnt.core.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer


abstract class BaseFilePlugin<T: PluginConf>(app: App, device: Device):
    Plugin<T>(app, device) {
    var breakTransfer = false

    companion object {
        const val PART = 65532
    }

    fun sendStream(inp: InputStream, name: String, size: Long,
                   progressCallback: (cur: Long, total: Long, part: Long) -> Unit,
                   rpcMethod: String = "upload", extraArgs: Map<String, Any> = mapOf()): DCResult {
        val resp = (rpc(rpcMethod, mapOf("name" to name, "size" to size) + extraArgs)
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
                progressCallback(sentBytes, size, chunkSize.toLong())
            }
        }
        val n = rpcReadNotification() as? JSONObject ?: return DCResult(false, "Fail")
        if (n.getInt("code") == 0) return DCResult(true, "OK")
        if (n.getInt("code") == 1) return DCResult(false, "Canceled")
        return DCResult(false,"Not confirmed")
    }

    fun sendFile(file: FileEntry, contentResolver: ContentResolver,
                 progressCallback: (cur: Long, total: Long, part: Long) -> Unit,
                 rpcMethod: String = "upload", extraArgs: Map<String, Any> = mapOf()): DCResult {
        val inp = if (file.data != null) {
            ByteArrayInputStream(file.data)
        } else {
            val uri = file.localUri ?: return DCResult(false, "No URI presented")
            contentResolver.openInputStream(uri) ?: return DCResult(false, "URI open fail")
        }
        return sendStream(inp, file.name, file.size, progressCallback, rpcMethod, extraArgs)
    }

    protected fun safeFileName(downloadDir: DocumentFile, initialName: String): String? {
        val existingNames: MutableSet<String> = mutableSetOf()
        downloadDir.listFiles().forEach { f -> f.name?.also { existingNames.add(it) } }
        if (!existingNames.contains(initialName)) return initialName
        val file = File(initialName)
        val name = file.nameWithoutExtension
        val extension = file.extension
        for (i in 1 .. 0xFFFF) {
            val newName = "$name-$i.$extension"
            if (!existingNames.contains(newName)) return newName
        }
        return null
    }

    fun recvFile(downloadDir: DocumentFile, entry: FileEntry, contentResolver: ContentResolver,
                 progressCallback: (cur: Long, total: Long, part: Long) -> Unit,
                 rpcMethod: String = "download", extraArgs: Map<String, Any> = mapOf()): DCResult {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            return DCResult(false,"Storage not mounted")
        }
        val fileName = safeFileName(downloadDir, entry.name)
            ?: return DCResult(false, "No safe name for file")
        val file = downloadDir.createFile("*/*", fileName)
            ?: return DCResult(false, "Failed to create file")
        val uri = file.uri
        entry.localUri = uri
        Log.d(TAG, "Open new file URI: $uri")
        val fd = contentResolver.openOutputStream(uri)
            ?: return DCResult(false, "Could not open file")
        Log.d(TAG, "send request")
        val params = mapOf("index" to entry.remoteIndex, "size" to entry.size) + extraArgs
        val resp = (rpc(rpcMethod, params) as? JSONObject)
            ?: return DCResult(false,"Incorrect response")
        var recBytes: Long = 0
        var buf: ByteArray
        Log.d(TAG, "resp = $resp")
        if (resp.getInt("code") == 0) {
            if (entry.size < 0L) {
                entry.size = resp.getLong("size")
            }
            Log.d(TAG, "Start downloading: recBytes = $recBytes, entry.size = ${entry.size}")
            var dataBuf: ByteBuffer? = null
            if (entry.size < FileTransferPlugin.THUMBNAIL_THRESHOLD) {
                entry.data = ByteArray(entry.size.toInt())
                entry.data?.also { dataBuf = ByteBuffer.wrap(it) }
            }
            while (recBytes < entry.size) {
                if (breakTransfer) {
                    Log.i(TAG, "Downloading canceled")
                    sock.close()
                    return DCResult(false, "Canceled")
                }
//                Log.d(TAG, "recBytes = $recBytes, entry.size = ${entry.size}")
                buf = read()
                fd.write(buf)
                dataBuf?.put(buf)
                recBytes += buf.size
                progressCallback(recBytes, entry.size, PART.toLong())
            }
            rpcSend("confirm", mapOf("code" to 0))
            entry.status = FileStatus.DONE
            return DCResult(true,"OK", data = entry.name)
        }
        return DCResult(false,"Request rejected")
    }

}