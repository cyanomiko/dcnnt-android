package net.dcnnt.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import net.dcnnt.MainActivity
import net.dcnnt.R
import java.util.concurrent.atomic.AtomicBoolean
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
}


class App : Application() {
    val TAG = "DConnect/App"
    private val appPlugins = listOf("file", "rcmd", "nots")
//    val inited = AtomicBoolean(false)
    lateinit var directory: String
    lateinit var conf: AppConf
    lateinit var dm: DeviceManager
    lateinit var pm: PluginManager
    lateinit var downloadsDirectory: Uri
    lateinit var rootDirectory: Uri
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
        Log.i(TAG, "Creste config $directory/conf.json")
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
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.channel_progress_name)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel("net.dcnnt.progress", name, importance)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onCreate() {
        super.onCreate()
        init()
    }
}
