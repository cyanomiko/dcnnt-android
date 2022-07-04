package net.dcnnt

import android.content.Context
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

    override fun doWork(): Result {
        APP.log("DCWorker $id - start")
        var searchDone = false
        APP.dm.devices.values.forEach { device ->
            try {
                val plugin = SyncPlugin(APP, device)
                plugin.init(applicationContext)
                val tasks = plugin.conf.getTasks()
                if (tasks.isNotEmpty()) {
                    if (!searchDone) {
                        APP.dm.syncSearch(APP.conf)
                        searchDone = true
                    }
                    if (device.ip != null) {
                        tasks.forEach {
                            APP.log("task '${it.name.value}'")
                            it.execute(plugin)
                        }
                    }
                }
            } catch (e: Exception) {
                APP.logException(e)
            }
        }
        return Result.success()
    }
}
