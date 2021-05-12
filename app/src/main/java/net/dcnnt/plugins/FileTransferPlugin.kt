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


class FileTransferPluginConf(directory: String, uin: Int):
    PluginConf(directory, "file", uin) {
    val downloadDir = StringEntry(this, "downloadDir", 0, 4096, "").init()
//    val uploadTries = IntEntry(this, "uploadTries", 1, 4096, 1)
//    val downloadTries = IntEntry(this, "uploadTries", 1, 4096, 1)
}


class FileTransferPlugin(app: App, device: Device): BaseFilePlugin<FileTransferPluginConf>(app, device) {
    override val TAG = "DC/File"
    override val MARK = "file"
    override val NAME = "File Transfer"
//    var breakTransfer = false
    lateinit var downloadDir: DocumentFile
    lateinit var context: Context

    companion object {
//        const val PART = 65532
        const val THUMBNAIL_THRESHOLD = 10 * 1024 * 1024
    }

    override fun init(context: Context?): Boolean {
        super.init(context)
        this.context = context ?: throw PluginException("No context passed")
        downloadDir = DocumentFile.fromTreeUri(this.context, Uri.parse(app.conf.downloadDirectory.value)) ?: throw PluginException("Couldn't open download dir")
        return true
    }

    private fun processRemoteDirData(data: JSONArray): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        for (i in 0 until data.length()) data.optJSONObject(i)?.also {
            val name = it.getString("name")
            val nodeType = it.getString("node_type")
            val size = it.getLong("size")
            when (nodeType) {
                "file" -> result.add(FileEntry(name, size, remoteIndex = it.getLong("index")))
                "directory" -> result.add(FileEntry(name, size,
                    remoteChildren = processRemoteDirData(it.getJSONArray("children"))))
            }
        }
        result.sortWith(compareBy { "${!it.isDir}${it.name}" })
        return result
    }

    fun listRemoteDir(path: List<String>): FileEntry {
        val res = rpc("list", mapOf())
        Log.d(TAG, "$res")
        if (res is JSONArray) {
            return FileEntry("${device.name} shared files",
                res.length().toLong(), remoteChildren = processRemoteDirData(res))
        }
        return FileEntry("${device.name} shared files", 0, remoteChildren = listOf())
    }

    fun uploadFile(file: FileEntry, contentResolver: ContentResolver,
                   progressCallback: (cur: Long, total: Long, part: Long) -> Unit): DCResult {
        APP.log("Upload file '${file.name}' (${file.localUri}) to device ${device.uin}")
        return sendFile(file, contentResolver, progressCallback)
    }

    private fun safeFileName(initialName: String): String? {
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

    fun downloadFile(entry: FileEntry, contentResolver: ContentResolver,
                     progressCallback: (cur: Long, total: Long, part: Long) -> Unit): DCResult {
        APP.log("Download file '${entry.name}' from device ${device.uin}")
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            return DCResult(false,"Storage not mounted")
        }
        val fileName = safeFileName(entry.name)
            ?: return DCResult(false, "No safe name for file")
        val file = downloadDir.createFile("*/*", fileName)
            ?: return DCResult(false, "Failed to create file")
        val uri = file.uri
        entry.localUri = uri
        Log.d(TAG, "Open new file URI: $uri")
        val fd = contentResolver.openOutputStream(uri)
            ?: return DCResult(false, "Could not open file")
        Log.d(TAG, "send request")
        val resp = (rpc("download", mapOf("index" to entry.remoteIndex, "size" to entry.size)) as? JSONObject)
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
