package net.dcnnt.plugins

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import net.dcnnt.R
import net.dcnnt.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
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

data class DirSyncEntry(val name: String, var ts: Long, val isDir: Boolean, val d: DocumentFile)

class DirectorySyncTask(parent: SyncPluginConf, key: String): SyncTask(parent, key) {
    override val confName = "sync_dir"
    lateinit var directory: DirEntry
    lateinit var target: StringEntry
    lateinit var mode: SelectEntry
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
                result[name] = DirSyncEntry(name, it.lastModified(), true, it)
                getFlatFS(result, fn, it)
            } else if (it.isFile) {
                val ts = it.lastModified()
                result[name] = DirSyncEntry(name, ts, false, it)
                var dirName = File(name).parentFile
                while ((dirName != null) and ("$dirName" != "/")) {
                    val dirNameStr = "$dirName"
                    result[dirNameStr]?.also { d -> if (d.ts < ts) d.ts = ts }
                    dirName = File(dirNameStr).parentFile
                }
            }
        }
    }

    private fun renameWithMark(f: DocumentFile, mark: String) {
        if (!f.exists()) return
        val name = File("${f.name}")
        val namePart = name.nameWithoutExtension
        val extension = name.extension
        val suffixes = listOf("", "-1", "-2", "-3", "-4", "-5")
        suffixes.forEach {
            if (f.renameTo("$namePart-$mark$it.$extension")) return
        }
        throw PluginException("Could not rename '$name'")
    }

    private fun ensureRemoved(f: DocumentFile) {
        f.delete()
    }

    override fun execute(plugin: SyncPlugin) {
        val base = DocumentFile.fromTreeUri(plugin.context, Uri.parse(directory.value)) ?: return
        // Get local FS data
        val flatClient = mutableMapOf<String, DirSyncEntry>()
        getFlatFS(flatClient, "", base)
        val flatArrClient = JSONArray()
        flatClient.values.forEach {
            val entry = JSONArray()
            entry.put(it.name)
            entry.put(it.ts)
            entry.put(it.isDir)
            flatArrClient.put(entry)
        }
        // Init connection
        plugin.connect()
        // Send FS data to server to compare with server data
        val params = mapOf(
            "path" to target.value,
            "mode" to mode.value,
            "on_conflict" to onConflict.value,
            "on_delete" to onDelete.value,
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
        // Do local FS actions
        toRename.forEach { flatClient[it]?.also { e -> renameWithMark(e.d, "${e.ts}") } }
        toDelete.forEach { flatClient[it]?.also { e -> ensureRemoved(e.d) } }
        flatClient[""] = DirSyncEntry("", 0L, true, base)
        toCreate.forEach {
            var parent = base
            if (it.contains('/')) {
                parent = (flatClient[(File(it).parent ?: return@forEach)] ?: return@forEach).d
            }
            val newDir = parent.createDirectory(it)
                ?: throw PluginException("Failed to create directory '$it'")
            flatClient[it] = DirSyncEntry(it, 0L, true, newDir)
        }
        val contentResolver = plugin.context.contentResolver
        // Do uploads
        toUpload.forEach {
            val d = flatClient[it]?.d ?: return@forEach
            val f = FileEntry(name = it, localUri = d.uri, size = d.length())
            plugin.sendFile(f, contentResolver, { _, _, _ -> },
                "dir_upload", mapOf("path" to target.value))
        }
        // Do downloads
        toDownload.forEach {
            var parent = base
            if (it.contains('/')) {
                parent = (flatClient[(File(it).parent ?: return@forEach)] ?: return@forEach).d
            }
            val f = FileEntry(name = File(it).name, size = -1L, )
            plugin.recvFile(parent, f, contentResolver, { _, _, _ -> },
                "dir_download", mapOf("path" to target.value, "name" to it))
        }
    }
}


class SyncPluginConf(directory: String, uin: Int): PluginConf(directory, "sync", uin) {
    val tasks: MutableMap<String, SyncTask> = mutableMapOf()

    private fun loadTask(confName: String, key: String) {
        if (confName == "sync_dir") {
            val task = DirectorySyncTask(this, key)
            task.init()
            task.load()
            if (task.errorOnLoad) {
                removeTask(task)
            } else {
                tasks[task.confKey] = task
            }

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
