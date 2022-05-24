package net.dcnnt.plugins

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import net.dcnnt.R
import net.dcnnt.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer


abstract class SyncTask(val parent: SyncPluginConf, key: String): DCConf(key) {
    lateinit var name: StringEntry
    lateinit var enabled: BoolEntry
    lateinit var interval: SelectEntry
    val confKey: String
        get() = "$confName/$path"
    val errorOnLoad: Boolean
        get() = needDump
    open val defaultName = "Unknown task"
    open val SUB = "null"

    companion object {
        val intervalMinutes = hashMapOf( "15m" to 15L, "1h" to 60L, "8h" to 480L, "1d" to 1440L)
    }

    open fun init() {
        name = StringEntry(this, "name", 0, 40, defaultName).init() as StringEntry
        enabled = BoolEntry(this, "enabled", false).init() as BoolEntry
        interval = SelectEntry(this, "interval", listOf(
            SelectOption("15m", R.string.conf_sync_interval_15m),
            SelectOption("1h", R.string.conf_sync_interval_1h),
            SelectOption("8h", R.string.conf_sync_interval_8h),
            SelectOption("1d", R.string.conf_sync_interval_1d)
        ), 1).init() as SelectEntry
    }

    override fun loadJSON() = parent.extra.optJSONObject(confKey) ?: JSONObject()

    override fun dumpJSON(json: JSONObject) {
        parent.extra.put(confKey, json)
        if (!parent.dump()) throw Exception("Parent dump fail")
    }

    abstract fun getTextInfo(): String
    abstract fun execute(plugin: SyncPlugin)
}

data class DirSyncEntry(val name: String, var ts: Long, var crc: Long,
                        val isDir: Boolean, val d: DocumentFile)

class DirectorySyncTask(parent: SyncPluginConf, key: String): SyncTask(parent, key) {
    override val confName = "sync_dir"
    lateinit var directory: DirEntry
    lateinit var target: StringEntry
    lateinit var mode: SelectEntry
    lateinit var useCRC: BoolEntry
    lateinit var onConflict: SelectEntry
    lateinit var onDelete: SelectEntry
    override val defaultName = "Sync directory"
    override val SUB = "dir"

    override fun init() {
        super.init()
        directory = DirEntry(this, "directory", "", true).init() as DirEntry
        target = StringEntry(this, "target", 0, 0xFFFF, "").init() as StringEntry
        mode = SelectEntry(this, "mode", listOf(
            SelectOption("upload", R.string.conf_sync_dir_mode_upload),
            SelectOption("download", R.string.conf_sync_dir_mode_download),
            SelectOption("sync", R.string.conf_sync_dir_mode_sync)
        ), 1).init() as SelectEntry
        useCRC = BoolEntry(this, "useCRC", false).init() as BoolEntry
        onConflict = SelectEntry(this, "onConflict", listOf(
            SelectOption("replace", R.string.conf_sync_dir_onConflict_replace),
            SelectOption("new", R.string.conf_sync_dir_onConflict_new),
            SelectOption("both", R.string.conf_sync_dir_onConflict_both),
            SelectOption("ignore", R.string.conf_sync_dir_onConflict_ignore)
        ), 1).init() as SelectEntry
        onDelete = SelectEntry(this, "onDelete", listOf(
            SelectOption("delete", R.string.conf_sync_dir_onDelete_delete),
            SelectOption("ignore", R.string.conf_sync_dir_onDelete_ignore)
        ), 1).init() as SelectEntry
    }

    override fun getTextInfo(): String = Uri.decode(directory.value.split('/').last())

    private fun getFlatFS(result: MutableMap<String, DirSyncEntry>,
                          root: String, base: DocumentFile) {
        base.listFiles().asIterable().forEach {
            val fn = "${it.name}"
            val name = if (root.isEmpty()) fn else "$root/$fn"
            if (it.isDirectory) {
                result[name] = DirSyncEntry(name, it.lastModified(), -2L,true, it)
                getFlatFS(result, fn, it)
            } else if (it.isFile) {
                val ts = it.lastModified()
                result[name] = DirSyncEntry(name, ts, -2L, false, it)
                var dirName = File(name).parentFile
                while ((dirName != null) and ("$dirName" != "/")) {
                    val dirNameStr = "$dirName"
                    result[dirNameStr]?.also { d -> if (d.ts < ts) d.ts = ts }
                    dirName = File(dirNameStr).parentFile
                }
            }
        }
    }

    private fun renameWithMark(f: DocumentFile, mark: String): String? {
        if (!f.exists()) return null
        val name = File("${f.name}")
        val namePart = name.nameWithoutExtension
        val extension = name.extension
        val suffixes = listOf("", "-1", "-2", "-3", "-4", "-5")
        suffixes.forEach {
            val newName = "$namePart-$mark$it.$extension"
            if (f.renameTo(newName)) return newName
        }
        throw PluginException("Could not rename '$name'")
    }

    private fun ensureRemoved(f: DocumentFile) {
        f.delete()
    }

    override fun execute(plugin: SyncPlugin) {
        APP.log("Start '$SUB' sync task '${name.value}'")
        val base = DocumentFile.fromTreeUri(plugin.context, Uri.parse(directory.value)) ?: return
        // Get local FS data
        val flatClient = mutableMapOf<String, DirSyncEntry>()
        getFlatFS(flatClient, "", base)
        val flatArrClient = JSONArray()
        flatClient.values.forEach {
            val entry = JSONArray()
            entry.put(it.name)
            entry.put(it.ts)
            if (it.isDir) {
                entry.put(-1L)
            } else {
                entry.put(it.crc)
            }
            flatArrClient.put(entry)
        }
        APP.log("Have ${flatClient.size} entries to sync")
        // Init connection
        plugin.connect()
        // Send FS data to server to compare with server data
        val params = mapOf(
            "path" to target.value,
            "mode" to mode.value,
            "on_conflict" to onConflict.value,
            "on_delete" to onDelete.value,
            "use_crc" to useCRC.value,
            "data" to flatArrClient
        )
        val res = (plugin.rpc("${SUB}_list", params) as? JSONObject)
            ?: throw PluginException("Incorrect dir sync list data")
        // Extract data from response
        val toRenameArr = res.getJSONArray("rename")
        val toDeleteArr = res.getJSONArray("delete")
        val toCreateArr = res.getJSONArray("create")
        val toUploadArr = res.getJSONArray("upload")
        val toDownloadArr = res.getJSONArray("download")
        var toRename = List<String>(toRenameArr.length()) { toRenameArr.optString(it) }
        toRename = toRename.filter { it.isNotEmpty() }.sorted()
        var toDelete = List<String>(toDeleteArr.length()) { toDeleteArr.optString(it) }
        toDelete = toDelete.filter { it.isNotEmpty() }
        var toCreate = List<String>(toCreateArr.length()) { toCreateArr.optString(it) }
        toCreate = toCreate.filter { it.isNotEmpty() }.sorted()
        var toUpload = List<String>(toUploadArr.length()) { toUploadArr.optString(it) }
        toUpload = toUpload.filter { it.isNotEmpty() }
        var toDownload = List<String>(toDownloadArr.length()) { toDownloadArr.optString(it) }
        toDownload = toDownload.filter { it.isNotEmpty() }
        APP.log("Scheduled operations: rename - ${toRename.size}, delete - ${toDelete.size} " +
                "create dirs - ${toCreate.size}, upload to server - ${toUpload.size}, " +
                "download from server - ${toDownload.size}")
        // Do local FS actions
        toRename.forEach { flatClient[it]?.also { e ->
            val newName = renameWithMark(e.d, "${e.ts}")
            if (newName == null) {
                APP.log("Need not rename: '$it'")
            } else {
                APP.log("Renamed: '$it' -> '$newName'")
            }
        } }
        toDelete.forEach { flatClient[it]?.also { e ->
            ensureRemoved(e.d)
            APP.log("Deleted: '$it'")
        } }
        flatClient[""] = DirSyncEntry("", 0L, -2L, true, base)
        toCreate.forEach {
            var parent = base
            if (it.contains('/')) {
                parent = (flatClient[(File(it).parent ?: return@forEach)] ?: return@forEach).d
            }
            val newDir = parent.createDirectory(it)
                ?: throw PluginException("Failed to create directory '$it'")
            APP.log("Create directory: '$it'")
            flatClient[it] = DirSyncEntry(it, 0L, -2L, true, newDir)
        }
        val contentResolver = plugin.context.contentResolver
        // Do uploads
        toUpload.forEach {
            val d = flatClient[it]?.d ?: return@forEach
            val f = FileEntry(name = it, localUri = d.uri, size = d.length())
            APP.log("Uploading: '$it' (${f.size} bytes)")
            plugin.sendFile(f, contentResolver, { _, _, _ -> },
                "dir_upload", mapOf("path" to target.value))
        }
        // Do downloads
        toDownload.forEach {
            var parent = base
            if (it.contains('/')) {
                parent = (flatClient[(File(it).parent ?: return@forEach)] ?: return@forEach).d
            }
            val f = FileEntry(name = File(it).name, size = -1L)
            APP.log("Downloading: '$it'")
            plugin.recvFile(parent, f, contentResolver, { _, _, _ -> },
                "dir_download", mapOf("path" to target.value, "name" to it))
        }
    }
}


class MessagesSyncTask(parent: SyncPluginConf, key: String): SyncTask(parent, key) {
    override val confName = "sync_messages"
    override val defaultName = "Sync messages"
    override val SUB = "messages"

    override fun getTextInfo(): String {
        TODO("Not yet implemented")
    }

    override fun execute(plugin: SyncPlugin) {
        TODO("Not yet implemented")
    }
}


class ContactsSyncTask(parent: SyncPluginConf, key: String): SyncTask(parent, key) {
    override val confName = "sync_contacts"
    override val defaultName = "Sync contacts"
    override val SUB = "contacts"
    lateinit var mode: SelectEntry
    lateinit var noPhoto: BoolEntry
//    lateinit var oneFile: BoolEntry

    override fun init() {
        super.init()
        mode = SelectEntry(this, "mode", listOf(
            SelectOption("upload", R.string.conf_sync_dir_mode_upload),
        ), 0).init() as SelectEntry
        noPhoto = BoolEntry(this, "noPhoto", false).init() as BoolEntry
//        oneFile = BoolEntry(this, "oneFile", true).init() as BoolEntry
    }

    override fun getTextInfo(): String = ""

    override fun execute(plugin: SyncPlugin) {
        val context = plugin.context
        val cursor: Cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, null, null, null, null
        ) ?: throw PluginException("No contacts cursor")
        val buf = ByteArrayOutputStream()
        if (cursor.moveToFirst()) {
            cursor.use {
                do {
                    var uri = Uri.withAppendedPath(
                        ContactsContract.Contacts.CONTENT_VCARD_URI,
                        it.getString(it.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY))
                    )
                    if (noPhoto.value) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            uri = uri.buildUpon().appendQueryParameter(
                                ContactsContract.Contacts.QUERY_PARAMETER_VCARD_NO_PHOTO, "true")
                                .build()
                        }
                    }
//                    Log.d(TAG, "uri = $uri")
                    try {
                        val inp = (context.contentResolver.openAssetFileDescriptor(uri, "r")
                            ?: throw PluginException("No contact info")).createInputStream()
                        val vCardRaw = inp.readBytes()
                        Log.d(TAG,"Put ${vCardRaw.size} bytes to buffer")
                        buf.write(vCardRaw)
                    } catch (e: Exception) {
                        APP.logException(e)
                    }
                } while (it.moveToNext())
            }
        }
        plugin.connect()
        val res = plugin.sendStream(buf.toByteArray().inputStream(), "contacts.vcf",
            buf.size().toLong(), { _, _, _ -> }, "contacts_upload")
        if (!res.success) {
            throw PluginException(res.message)
        }
    }
}


class SyncPluginConf(directory: String, uin: Int): PluginConf(directory, "sync", uin) {
    val tasks: MutableMap<String, SyncTask> = mutableMapOf()

    fun getSyncTask(confName: String, key: String? = null): SyncTask {
        val taskKey = key ?: nowString()
        val task = when (confName) {
            "sync_dir" -> DirectorySyncTask(this, taskKey)
            "sync_contacts" -> ContactsSyncTask(this, taskKey)
            else -> { throw PluginException("Unknown sync task type '$confName'") }
        }
        task.init()
        return task
    }

    private fun loadTask(confName: String, key: String) {
        val task: SyncTask
        Log.d(TAG, "confName = $confName, key = $key")
        try {
            task = getSyncTask(confName, key)
        } catch (e: PluginException) {
            return
        }
        Log.d(TAG, "task = $task")
        task.load()
        Log.d(TAG, "task loaded")
        if (task.errorOnLoad) {
            removeTask(task)
        } else {
            tasks[task.confKey] = task
        }
    }

    fun loadTasks() {
        extra.keys().forEach {
            it.split('/').also { s -> if (s.size == 2) loadTask(s[0], s[1]) }
        }
    }

    fun removeTask(task: SyncTask) {
        tasks.remove(task.confKey)
        extra.remove(task.confKey)
        dump()
    }

    fun addTask(task: SyncTask) {
        tasks[task.confKey] = task
        task.dump()
    }

    fun getTasks() = tasks.values.toList()

    override fun onLoad() {
        loadTasks()
    }
}


class SyncPlugin(app: App, device: Device): BaseFilePlugin<SyncPluginConf>(app, device) {
    override val TAG = "DC/Sync"
    override val MARK = "sync"
    override val NAME = "Sync"
    lateinit var context: Context

    override fun init(context: Context?): Boolean {
        super.init(context)
        this.context = context ?: throw PluginException("No context passed")
        return true
    }
}
