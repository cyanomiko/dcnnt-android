package net.dcnnt

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.dcnnt.core.APP
import net.dcnnt.core.App
import net.dcnnt.core.EVENT_AVAILABLE_DEVICES_UPDATED
import net.dcnnt.ui.ProgressNotification
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DCForegroundTask(val fgNotify: ProgressNotification, val main: () -> Unit);

class DCForegroundWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        // Mark the Worker as important
        val progress = "Starting Download"
        val taskKey = inputData.getString("taskKey") ?: return Result.failure()
        setForeground(createForegroundInfo(progress))
        APP.tasks[taskKey]?.also {
            APP.tasks.remove(taskKey)
            it()
        }
        return Result.success()
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun createForegroundInfo(progress: String): ForegroundInfo {
        val title = applicationContext.getString(R.string.channel_foreground_name)
        val cancel = applicationContext.getString(R.string.cancel)
        // This PendingIntent can be used to cancel the worker
        val intent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(getId())
        val notification = NotificationCompat.Builder(applicationContext, "net.dcnnt.progress")
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(progress)
            .setSmallIcon(R.drawable.icon_app)
            .setOngoing(true)
            // Add the cancel action to the notification which can
            // be used to cancel the worker
            .addAction(android.R.drawable.ic_delete, cancel, intent)
            .build()
        return ForegroundInfo((1377 .. 2488).random(), notification)
    }

}
