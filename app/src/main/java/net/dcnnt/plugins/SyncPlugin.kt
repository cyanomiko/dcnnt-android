package net.dcnnt.plugins

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
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

    override fun execute(plugin: SyncPlugin) {
        val flatFilesData = JSONArray()
        val dir = DocumentFile.fromTreeUri(plugin.context, Uri.parse(directory.value)) ?: return
        dir.listFiles().asIterable().forEach {
            if (it.isDirectory) return@forEach  // ToDo: Support for subdirectories
            flatFilesData.put(JSONObject().apply {
                put("name", it.name)
                put("ts", it.lastModified())
            })
            APP.log("${it.uri}")
        }
        // ToDo: Prepare session - send info about files and get such info back
        // ToDo: Send files one by one
        // ToDo: Receive files one by one
        // ToDo: Finalize session or just close connection?
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
