package net.dcnnt.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.concurrent.thread


/**
 * Supply class to show progress notifications
 */
class ProgressNotification(val context: Context) {
    private val minUpdateInterval = 500L
    private val uiProgressMax = 100
    private var uiProgressCur = 0
    private var notificationId: Int = 0
    private var progressMax: Long = 1000L
    private var builder = NotificationCompat.Builder(context, "net.dcnnt.progress")
    private var smallIconId: Int? = null
    var isNew = true
    var lastUpdateTime = 0L

    /**
     * Create and show new progress notification
     * @param iconId - ID of notifications small icon
     * @param title - title text
     * @param text - one line notification text (percentage added automatically)
     * @param max - max progress (internal value, not used in progress bar directly)
     * @param icon - optional large icon
     * @param intent - optional intent for notification
     */
    fun create(iconId: Int, title: String, text: String, max: Long,
               icon: Bitmap? = null, intent: Intent? = null) {
        notificationId = (System.currentTimeMillis() and 0xFFFFFF).toInt()
        progressMax = max
        smallIconId = iconId
        builder.setSmallIcon(iconId)
               .setChannelId("net.dcnnt.progress")
               .setContentTitle(title)
               .setContentText("$text (0%)")
               .setPriority(NotificationCompat.PRIORITY_LOW)
               .setOnlyAlertOnce(true)
               .setDefaults(0)
               .setProgress(uiProgressMax, uiProgressCur, false)
        icon?.also { builder.setLargeIcon(it) }
        intent?.also { builder.setContentIntent(PendingIntent.getActivity(context, 113, intent, 0)) }
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        isNew = false
    }

    /**
     * Update progress info in notification
     * @param text - one line of text for notification (percentage added automatically)
     * @param progressCur - current progress of ongoing operation
     */
    fun update(text: String, progressCur: Long, forceUpdate: Boolean = false) {
        val uiProgressNew = ((100 * progressCur) / progressMax).toInt()
        val currentTime = System.currentTimeMillis()
        if (!forceUpdate) {
            if (currentTime - lastUpdateTime < minUpdateInterval) return
            if (uiProgressCur == uiProgressNew) return
        }
        uiProgressCur = uiProgressNew
        builder.setContentText("$text ($uiProgressCur%)")
               .setProgress(uiProgressMax, uiProgressCur, false)
        smallIconId?.also { builder.setSmallIcon(it) }
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        lastUpdateTime = currentTime
    }

    /**
     * Update notification to display completion of operation
     * @param title - new title of notification
     * @param text - new text of notification  (percentage added automatically)
     * @param icon - optional large icon
     * @param intent - optional intent for notification
     */
    fun complete(title: String, text: String, icon: Bitmap? = null, intent: Intent? = null) {
        builder.setContentTitle(title)
               .setContentText("$text (100%)")
               .setProgress(0, 0, false)
        smallIconId?.also { builder.setSmallIcon(it) }
        icon?.also { builder.setLargeIcon(it) }
        intent?.also { builder.setContentIntent(PendingIntent.getActivity(context, 113, intent, 0)) }
        with(NotificationManagerCompat.from(context)) {
            notify(notificationId, builder.build())
            thread {
                Thread.sleep(1000L)
                notify(notificationId, builder.build())
            }
        }
    }
}