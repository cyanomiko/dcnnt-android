package net.dcnnt

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import net.dcnnt.core.*
import net.dcnnt.plugins.NotificationFilter
import net.dcnnt.plugins.NotificationsPlugin
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.absoluteValue


class DCNotificationListenerService : NotificationListenerService() {
    val TAG = "DC/NListener"

    /**
     * Check if device has network connection to work with
     */
    private fun hasConnection(): Boolean {
        val cm = (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: return false
        return cm.allNetworks.all { network ->
            val cap = cm.getNetworkCapabilities(network) ?: return true
            return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) or
                   cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) or
                   (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) and APP.conf.cellularData.value)
        }
    }

    override fun onCreate() {
        super.onCreate()
        APP.isDCNotificationListenerServiceRunning.set(true)
    }

    override fun onDestroy() {
        APP.isDCNotificationListenerServiceRunning.set(false)
        super.onDestroy()
    }

    /**
     * Get application icon as PNG encoded data
     * @param packageName - name of application (example: "net.mydomain.myapp")
     * @return PNG bytes or null
     */
    fun packageIcon(packageName: String): ByteArray? {
        try {
            return drawableToPNG(packageManager.getApplicationIcon(packageName))
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "$packageName ${e.message}")
        }
        return null
    }

    /**
     * Pack notification info to JSON structure
     * @param event - type of notification event: "posted", "removed" etc
     * @param n - notification object itself
     * @param icon - set true if icon presented
     */
    fun notificationJSON(event: String, n: StatusBarNotification, icon: Boolean) = JSONObject().apply {
        put("event", event)
        put("package", n.packageName)
        put("timestamp", n.postTime)
        put("title", n.notification.extras.getString(Notification.EXTRA_TITLE))
        put("text", n.notification.extras.getString(Notification.EXTRA_TEXT))
        put("packageIcon", icon)
        put("color", n.notification.color)
    }

    /**
     * Send notification data and icon to one of known devices if user allows it in settings
     * @param notification - JSON contains notification data
     * @param device - device object represent device to send notification to
     * @param icon - optional icon to show on device with notification encoded as PNG
     */
    fun sendNotificationToDevice(notification: JSONObject, device: Device, icon: ByteArray?) {
        val msg = "from app ${notification.getString("package")} to device ${device.uin}";
        APP.log("Check notification $msg", TAG)
        try {
            NotificationsPlugin(APP, device).also { plugin ->
                plugin.init()
                val filter = plugin.conf.getFilter(notification.getString("package"))
                Log.d(TAG, "filter = $filter")
                if (filter == NotificationFilter.NO) {
                    APP.log("Notification $msg - filtered", TAG)
                    return
                }
                APP.log("Notification $msg - sending...", TAG)
                thread {
                    try {
                        plugin.connect()
                        plugin.checkAndSendNotification(notification, filter, icon)
                    } catch (e: Exception) {
                        APP.logException(e, TAG)
                    }
                }
            }
        } catch (e: Exception) {
            APP.logException(e, TAG)
        }
    }

    /**
     * Try to send notification to all available devices
     */
    fun sendNotificationToAll(event: String, sbn: StatusBarNotification) {
        if (!hasConnection()) {
            APP.log("Skip notification ($event) from ${sbn.packageName} - no connection")
            return
        }
        if (!NotificationsPlugin.checkIfNotificationAllowedForAnyDevice(APP, sbn.packageName)) {
            APP.log("Skip notification ($event) from ${sbn.packageName} - filtered")
            return
        }
        APP.log("Process notification ($event) from ${sbn.packageName}")
        val icon = packageIcon(sbn.packageName)
        val notification: JSONObject = notificationJSON(event, sbn, icon != null)
        val now = System.currentTimeMillis() / 1000L
        val availableDevices = APP.dm.availableDevices()
        if (((now - APP.dm.lastSearchTimestamp).absoluteValue > 60) or availableDevices.isEmpty()) {
            thread {
                APP.dm.syncSearch(APP.conf, 25, 100, 5) {
                    sendNotificationToDevice(notification, it, icon)
                }
            }
        } else {
            availableDevices.forEach {
                sendNotificationToDevice(notification, it, icon)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        sendNotificationToAll("posted", sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        sendNotificationToAll("removed", sbn)
    }
}