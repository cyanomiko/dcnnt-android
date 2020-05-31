package net.dcnnt.ui

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.concurrent.thread

/**
 * Supply class to show progress notifications
 */
class ProgressNotification(val context: Context) {
    private val uiProgressMax = 100
    private var uiProgressCur = 0
    private var notificationId: Int = 0
    private var progressMax: Long = 0L
    private var builder = NotificationCompat.Builder(context, "net.dcnnt.progress")

    /**
     * Create and show new progress notification
     * @param iconId - ID of notifications small icon
     * @param title - title text
     * @param text - one line notification text (percentage added automatically)
     * @param max - max progress (internal value, not used in progress bar directly)
     */
    fun create(iconId: Int, title: String, text: String, max: Long) {
        notificationId = (System.currentTimeMillis() and 0xFFFFFF).toInt()
        progressMax = max
        builder.setSmallIcon(iconId)
               .setContentTitle(title)
               .setContentText("$text (0%)")
               .setPriority(NotificationCompat.PRIORITY_LOW)
               .setOnlyAlertOnce(true)
               .setDefaults(0)
               .setProgress(uiProgressMax, uiProgressCur, false)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    /**
     * Update progress info in notification
     * @param text - one line of text for notification (percentage added automatically)
     * @param progressCur - current progress of ongoing operation
     */
    fun update(text: String, progressCur: Long) {
        val uiProgressNew = ((100 * progressCur) / progressMax).toInt()
        if (uiProgressCur == uiProgressNew) return
        uiProgressCur = uiProgressNew
        builder.setContentText("$text ($uiProgressCur%)")
               .setProgress(uiProgressMax, uiProgressCur, false)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    /**
     * Update notification to display completion of operation
     * @param title - new title of notification
     * @param text - new text of notification  (percentage added automatically)
     */
    fun complete(title: String, text: String) {
        builder.setContentTitle(title)
               .setContentText("$text (100%)")
               .setProgress(0, 0, false)
        with(NotificationManagerCompat.from(context)) {
            notify(notificationId, builder.build())
            thread {
                Thread.sleep(1000L)
                notify(notificationId, builder.build())
            }
        }
    }
}