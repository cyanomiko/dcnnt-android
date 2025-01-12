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
import net.dcnnt.core.randId
import net.dcnnt.ui.ProgressNotification
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


class DCForegroundWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    var notificationId: Int = randId()

    override suspend fun doWork(): Result {
        val taskKey = inputData.getString("taskKey") ?: return Result.failure()
        (APP.tasks.remove(taskKey) ?: return Result.failure()).also {
            setForeground(createForegroundInfo())
            it(this)
        }
        return Result.success()
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun createForegroundInfo(): ForegroundInfo {
        val title = applicationContext.getString(R.string.channel_foreground_name)
        val cancel = applicationContext.getString(R.string.cancel)
        // This PendingIntent can be used to cancel the worker
        val intent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, "net.dcnnt.progress")
            .setContentTitle(title)
            .setContentText("...")
            .setSmallIcon(R.mipmap.icon_app_launcher)
            .setOngoing(true)
            // Add the cancel action to the notification which can
            // be used to cancel the worker
            .addAction(android.R.drawable.ic_delete, cancel, intent)
            .build()
        return ForegroundInfo(notificationId, notification)
    }

}
