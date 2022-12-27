package net.dcnnt

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
//import androidx.work.WorkRequest
//import androidx.work.Worker
//import androidx.work.WorkerParameters
import net.dcnnt.core.APP
import net.dcnnt.plugins.SyncPlugin
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DCWorker(appContext: Context, workerParams: WorkerParameters):
    Worker(appContext, workerParams) {

    companion object {
        const val TAG = "DCWorker"
        fun initWorker(context: Context, workIntervalMinutes: Long) {
            val intervalTag = "$TAG/$workIntervalMinutes"
            val bgWorkRequest: PeriodicWorkRequest = PeriodicWorkRequestBuilder<DCWorker>(
                workIntervalMinutes, TimeUnit.MINUTES).addTag(TAG).addTag(intervalTag).build()
//            WorkManager.initialize(
//                context,
//                Configuration.Builder()
//                    .setExecutor(Executors.newFixedThreadPool(1))
//                    .build())
            val wm = WorkManager.getInstance(context)
            val worksToCancel = mutableListOf<UUID>()
            wm.getWorkInfosByTag(TAG).get().forEach {
                if (!it.tags.contains(intervalTag)) worksToCancel.add(it.id)
            }
            worksToCancel.forEach { wm.cancelWorkById(it) }
            wm.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, bgWorkRequest)
        }
    }

    fun showResultNotification(title: String, text: String) {
        val notificationId = (System.currentTimeMillis() and 0xFFFFFF).toInt()
        val builder = NotificationCompat.Builder(applicationContext, "net.dcnnt.progress")
        builder.setSmallIcon(R.drawable.ic_sync)
               .setChannelId("net.dcnnt.sync")
               .setContentTitle(title)
               .setContentText(text)
               .setPriority(NotificationCompat.PRIORITY_LOW)
               .setOnlyAlertOnce(true)
               .setDefaults(0)
        NotificationManagerCompat.from(applicationContext).notify(notificationId, builder.build())
    }

    override fun doWork(): Result {
        APP.log("DCWorker $id - start")
        var searchDone = false
        APP.dm.devices.values.forEach { device ->
            try {
                val plugin = SyncPlugin(APP, device)
                plugin.init(applicationContext)
                val tasks = plugin.conf.getTasks().filter { it.enabled.value }
                if (tasks.isNotEmpty()) {
                    if (!searchDone) {
                        APP.dm.syncSearch(APP.conf)
                        searchDone = true
                    }
                    if (device.ip != null) {
                        tasks.forEach {
                            val name = it.name.value
                            val taskInfo = it.getTextInfo()
                            val taskInfoPrefix = if (taskInfo.isEmpty()) "" else "$taskInfo - "
                            APP.log("task '$name': $taskInfo")
                            try {
                                it.execute(plugin)
                                if (it.notify.value) {
                                    showResultNotification(name, "${taskInfoPrefix}OK")
                                }
                            } catch (e: Exception) {
                                if (it.notify.value) {
                                    showResultNotification(name,"$${taskInfoPrefix}Failed: $e")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                APP.logException(e)
//                showResultNotification(APP.getString(R.string.menu_sync), "Error: $e")
            }
        }
        return Result.success()
    }
}
