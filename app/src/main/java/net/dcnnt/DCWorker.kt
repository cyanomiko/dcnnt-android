package net.dcnnt

import android.content.Context
import androidx.work.*
//import androidx.work.WorkRequest
//import androidx.work.Worker
//import androidx.work.WorkerParameters
import net.dcnnt.core.APP
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DCWorker(appContext: Context, workerParams: WorkerParameters):
    Worker(appContext, workerParams) {

    companion object {
        fun initWorker(context: Context, workIntervalMinutes: Long) {
            val backgroundWorkRequest: WorkRequest = PeriodicWorkRequestBuilder<DCWorker>(
                workIntervalMinutes, TimeUnit.MINUTES).build()
//            WorkManager.initialize(
//                context,
//                Configuration.Builder()
//                    .setExecutor(Executors.newFixedThreadPool(1))
//                    .build())
            WorkManager.getInstance(context).enqueue(backgroundWorkRequest)
        }
    }

    override fun doWork(): Result {
        APP.log("DCWorker - OK")
        return Result.success()
    }
}
