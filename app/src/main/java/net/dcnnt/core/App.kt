package net.dcnnt.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import net.dcnnt.DCWorker
import net.dcnnt.MainActivity
import net.dcnnt.R
import net.dcnnt.plugins.SyncPluginConf
import net.dcnnt.plugins.SyncTask
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random


class AppConf(path: String): DCConf(path) {
    override val confName = "app"
    val uin = IntEntry(this, "uin", UIN_MIN, UIN_MAX, Random.nextInt(UIN_MIN, UIN_MAX)).init()
    val name = StringEntry(this, "name", 0, 40, Build.DEVICE.chunked(40)[0]).init()
    val description = StringEntry(this, "description", 0, 200,
        "Manufacturer: ${Build.MANUFACTURER}, model: ${Build.MODEL}".chunked(200)[0]).init()
    val password = StringEntry(this, "password", 0, 4096,
        "P" + Random.nextBytes(100).filter { (it > 96) and (it < 123) }.toByteArray().toString()).init()
    val cellularData = BoolEntry(this, "cellularData", false).init()
    val notificationListenerService = BoolEntry(this, "notificationListenerService", false).init()
    val autoSearch = BoolEntry(this, "autoSearch", true).init()
    val downloadNotificationPolicy = SelectEntry(this, "downloadNotificationPolicy", listOf(
        SelectOption("no", R.string.conf_app_downloadNotificationPolicy_no),
        SelectOption("one", R.string.conf_app_downloadNotificationPolicy_one),
        SelectOption("all", R.string.conf_app_downloadNotificationPolicy_all)
    ), 1).init()
    val uploadNotificationPolicy = SelectEntry(this, "uploadNotificationPolicy", listOf(
        SelectOption("no", R.string.conf_app_downloadNotificationPolicy_no),
        SelectOption("one", R.string.conf_app_downloadNotificationPolicy_one),
        SelectOption("all", R.string.conf_app_downloadNotificationPolicy_all)
    ), 1).init()
    val downloadDirectory = DirEntry(this, "downloadDirectory",
        "content://com.android.providers.downloads.documents/tree/downloads").init()
    val actionForSharedFile = SelectEntry(this, "actionForSharedFile", listOf(
        SelectOption("ask", R.string.conf_app_actionForSharedFile_ask),
        SelectOption("upload", R.string.conf_app_actionForSharedFile_upload),
        SelectOption("open", R.string.conf_app_actionForSharedFile_open)
    ), 0).init()
}

class App : Application() {
    val TAG = "DConnect/App"
    private val appPlugins = listOf("file", "rcmd", "nots")
    lateinit var directory: String
    lateinit var conf: AppConf
    lateinit var dm: DeviceManager
    lateinit var pm: PluginManager
    lateinit var crashHandler: DCCrashHandler
    private lateinit var logger: DCLogger
    private lateinit var errorLogger: DCLogger
    var activity: MainActivity? = null
    val isDCNotificationListenerServiceRunning = AtomicBoolean(false)

    init {
        APP = this
    }

    fun init() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.i(TAG, "Create notification channels...")
            createNotificationChannels()
        }
        Log.i(TAG, "Fetch data directory...")
        directory = applicationInfo.dataDir
        Log.i(TAG, "Init crash logger...")
        initCrashHandler()
        Log.i(TAG, "Init loggers...")
        logger = DCLogger(this, "dcnnt", ".work.log", 3, 100 * 1024L)
        errorLogger = DCLogger(this, "dcnnt", ".errors.log", 3, 100 * 1024L)
        logger.log("***********************************")
        logger.log("Application started, PID = ${android.os.Process.myPid()}")
        Log.i(TAG, "Create config $directory/conf.json")
        conf = AppConf("$directory/conf.json")
        Log.i(TAG, "Create DM...")
        dm = DeviceManager("$directory/devices")
        Log.i(TAG, "Create PM...")
        pm = PluginManager(this, "$directory/plugins", appPlugins)
        Log.i(TAG, "Load config...")
        conf.load()
        Log.i(TAG, "Init DM...")
        dm.init()
        Log.i(TAG, "Load DM...")
        dm.load()
        Log.i(TAG, "Init PM...")
        pm.init()
        Log.i(TAG, "Load PM...")
        pm.load()
        Log.i(TAG, "Init background tasks")
        initWorker()
    }

    fun log(line: String, tag: String = "DC/Log") = logger.log(line, tag)
    fun logError(line: String, tag: String = "DC/Log") = errorLogger.log(line, tag)
    fun logException(e: Exception, tag: String = "DC/Log") = errorLogger.log(e, tag)
    fun dumpLogs() {
        logger.dump()
        errorLogger.dump()
    }

    private fun initCrashHandler() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (oldHandler !is DCCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(DCCrashHandler(this, oldHandler).apply {
                crashHandler = this
            })
        }
    }

    private fun initWorker() {
        var minInterval = Long.MAX_VALUE
        dm.devices.values.forEach { device ->
            val config = pm.getConfig("sync", device.uin) as? SyncPluginConf ?: return@forEach
            config.tasks.values.forEach { task ->
                val curInterval = SyncTask.intervalMinutes[task.interval.value] ?: minInterval
                if (minInterval > curInterval) {
                    minInterval = curInterval
                }
            }
        }
        if (minInterval < Long.MAX_VALUE) {
            DCWorker.initWorker(applicationContext, minInterval)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(NotificationChannel(
                "net.dcnnt.progress", getString(R.string.channel_progress_name), importance))
            notificationManager.createNotificationChannel(NotificationChannel(
                "net.dcnnt.sync", getString(R.string.channel_sync_name), importance))
        }
    }

    private fun writeFileToArchive(zip: ZipOutputStream, file: File) {
        zip.putNextEntry(ZipEntry(file.name))
        val buf = file.readBytes()
        zip.write(buf)
        Log.d(TAG, "write ${buf.size} bytes to ${file.absolutePath}")
        zip.closeEntry()
    }

    fun dumpSettingsToFile(outputStream: OutputStream): Boolean {
        val zip = ZipOutputStream(outputStream)
        zip.putNextEntry(ZipEntry("dcnnt.timestamp.txt"))
        zip.write(nowString().toByteArray())
        zip.closeEntry()
        writeFileToArchive(zip, File(conf.path))
        File(dm.path).listFiles()?.forEach { writeFileToArchive(zip, it) }
        File(pm.directory).listFiles()?.forEach { writeFileToArchive(zip, it) }
        zip.close()
        outputStream.close()
        return true
    }

    fun dropSettings() {
        File(conf.path).delete()
        File(dm.path).listFiles()?.forEach { if ("$it".endsWith(dm.EXTENSION)) it.delete() }
        File(pm.directory).listFiles()?.forEach { if ("$it".endsWith(pm.SUFFIX)) it.delete() }
    }

    fun loadSettingsFromFile(inputStream: InputStream): Boolean {
        val zip = ZipInputStream(inputStream)
        val files = mutableMapOf<String, ByteArray>()
        // Unpack zipfile to memory
        while (true) files[(zip.nextEntry ?: break).name] = zip.readBytes()
        // Check mark file
        if (!files.containsKey("dcnnt.timestamp.txt")) return false
        // Remove old settings
        dropSettings()
        // Write new settings
        files.forEach {
            if (it.key == "conf.json") {
                File(conf.path).writeBytes(it.value)
            } else if (it.key.endsWith(dm.EXTENSION)) {
                File("${dm.path}/${it.key}").writeBytes(it.value)
            } else if (it.key.endsWith(pm.SUFFIX)) {
                File("${pm.directory}/${it.key}").writeBytes(it.value)
            }
        }
        return true
    }

    override fun onCreate() {
        super.onCreate()
        init()
    }
}
