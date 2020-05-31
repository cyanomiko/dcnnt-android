package net.dcnnt.plugins

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.util.Log
import net.dcnnt.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer


data class RemoteEntry(val name: String, val size: Long, val index: Long? = null, val children: List<RemoteEntry>? = null) {
    val isDir: Boolean = (index == null)
    var progress: Int = 0
    var localUri: Uri? = null
    var localFile: File? = null
    var status = FileEntryStatus.WAIT
    var data: ByteArray? = null
}


class FileTransferPluginConf(directory: String, uin: Int):
    PluginConf(directory, "file", uin) {
    val downloadDir = StringEntry(this, "downloadDir", 0, 4096, "").init()
//    val uploadTries = IntEntry(this, "uploadTries", 1, 4096, 1)
//    val downloadTries = IntEntry(this, "uploadTries", 1, 4096, 1)
}


class FileTransferPlugin(app: App, device: Device): Plugin<FileTransferPluginConf>(app, device) {
    override val TAG = "DC/File"
    override val MARK = "file"
    override val NAME = "File Transfer"
    var breakTransfer = false
    lateinit var directory: String

    companion object {
        const val PART = 65532
        const val THUMBNAIL_THRESHOLD = 10 * 1024 * 1024
    }

    override fun init(): Boolean {
        super.init()
        directory = conf.downloadDir.value
        return true
    }

    fun processRemoteDirData(data: JSONArray): List<RemoteEntry> {
        val result = mutableListOf<RemoteEntry>()
        for (i in 0 until data.length()) data.optJSONObject(i)?.also {
            val name = it.getString("name")
            val node_type = it.getString("node_type")
            val size = it.getLong("size")
            when (node_type) {
                "file" -> result.add(RemoteEntry(name, size, index = it.getLong("index")))
                "directory" -> result.add(RemoteEntry(name, size,
                    children = processRemoteDirData(it.getJSONArray("children"))))
            }
        }
        result.sortWith(compareBy { "${!it.isDir}${it.name}" })
        return result
    }

    fun listRemoteDir(path: List<String>): RemoteEntry {
        val res = rpc("list", mapOf())
        Log.d(TAG, "$res")
        if (res is JSONArray) {
            return RemoteEntry("${device.name} shared files",
                res.length().toLong(), children = processRemoteDirData(res))
        }
        return RemoteEntry("${device.name} shared files", 0, children = listOf())
    }

    fun uploadFile(file: FileEntry, contentResolver: ContentResolver,
                   progressCallback: (cur: Long, total: Long, part: Long) -> Unit): DCResult {
        val inp = if (file.data != null) {
            ByteArrayInputStream(file.data)
        } else {
            contentResolver.openInputStream(file.uri)
                ?: return DCResult(false, "URI open fail")
        }
        val size = file.size
        val resp = (rpc("upload", mapOf("name" to file.name, "size" to size))
                as? JSONObject) ?: return DCResult(false,"Incorrect response")
        Log.d(TAG, "$resp")
        var sentBytes: Long = 0
        var chunkSize = 0
        val buf = ByteArray(PART)
        if (resp.getInt("code") == 0) {
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
        return DCResult(false,"Rejected by server")
    }

    fun safeFile(file: File): File? {
        if (!file.exists()) return file
        for (i in 1 .. 0xFFFF) {
            val safeFile = File("${file.parent ?: "/"}/${file.nameWithoutExtension}_$i.${file.extension}")
            if (!safeFile.exists()) return safeFile
        }
        return null
    }

    fun downloadFile(entry: RemoteEntry, contentResolver: ContentResolver,
                     progressCallback: (cur: Long, total: Long, part: Long) -> Unit): DCResult {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            return DCResult(false,"Storage not mounted")
        }
        val downloadsDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        val file = safeFile(File("$downloadsDirectory/$directory/${entry.name}"))
            ?: return DCResult(false, "Could not create safe name for file")
        Log.d(TAG, "Safe name for file ${file.absolutePath}")
        entry.localFile = file
        val uri = Uri.fromFile(file)
        entry.localUri = uri
        Log.d(TAG, "Open new file URI: $uri")
        val fd = contentResolver.openOutputStream(uri)
            ?: return DCResult(false, "Could not open file")
        Log.d(TAG, "send request")
        val resp = (rpc("download", mapOf("index" to entry.index, "size" to entry.size)) as? JSONObject)
            ?: return DCResult(false,"Incorrect response")
        var recBytes: Long = 0
        var buf: ByteArray
        Log.d(TAG, "resp = $resp")
        if (resp.getInt("code") == 0) {
            Log.d(TAG, "Start downloading: recBytes = $recBytes, entry.size = ${entry.size}")
            var dataBuf: ByteBuffer? = null
            if (entry.size < THUMBNAIL_THRESHOLD) {
                entry.data = ByteArray(entry.size.toInt())
                entry.data?.also { dataBuf = ByteBuffer.wrap(it) }
            }
            while (recBytes < entry.size) {
                if (breakTransfer) {
                    Log.i(TAG, "Downloading canceled")
                    sock.close()
                    return DCResult(false, "Canceled")
                }
                Log.d(TAG, "recBytes = $recBytes, entry.size = ${entry.size}")
                buf = read()
                fd.write(buf)
                dataBuf?.put(buf)
                recBytes += buf.size
                progressCallback(recBytes, entry.size, PART.toLong())
            }
            rpcSend("confirm", mapOf("code" to 0))
            entry.status = FileEntryStatus.DONE
            return DCResult(true,"OK", data = entry.name)
        }
        return DCResult(false,"Request rejected")
    }
}
