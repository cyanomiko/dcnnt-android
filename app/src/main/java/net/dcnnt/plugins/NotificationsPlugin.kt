package net.dcnnt.plugins

import net.dcnnt.core.App
import net.dcnnt.core.Device
import net.dcnnt.core.Plugin
import net.dcnnt.core.PluginConf
import org.json.JSONObject


enum class NotificationFilter(val value: String) {
    NO("no"), NAME("name"), HEADER("header"), ALL("all");

    companion object {
        private val map = values().associateBy(NotificationFilter::value)
        fun fromString(value: String): NotificationFilter = map[value] ?: NO
    }
}

class NotificationsPluginConf(directory: String, uin: Int): PluginConf(directory, "nots", uin) {
    private val filters = mutableMapOf<String, NotificationFilter>()

    override fun onLoad() {
        filters.clear()
        extra.keys().forEach { filters[it] = NotificationFilter.fromString(extra.optString(it)) }
    }

    fun getFilter(pkg: String): NotificationFilter = filters[pkg] ?: NotificationFilter.NO

    fun setFilter(pkg: String, filter: NotificationFilter) {
        val pre = filters[pkg]
        filters[pkg] = filter
        extra.put(pkg, filter.value)
        if (pre != filter) dump()
    }

    fun setFilterForAll(filter: NotificationFilter) {
        var needDump = false
        filters.keys.forEach { pkg ->
            val pre = filters[pkg]
            filters[pkg] = filter
            extra.put(pkg, filter.value)
            if (pre != filter) needDump = true
        }
        if (needDump) dump()
    }

    fun copyFilters(src: NotificationsPluginConf) {
        val keys = filters.keys + src.filters.keys
        var needDump = false
        keys.forEach { pkg ->
            val pre = filters[pkg]
            val cur = src.getFilter(pkg)
            filters[pkg] = cur
            extra.put(pkg, cur.value)
            if (pre != cur) needDump = true
        }
        if (needDump) dump()
    }
}

class NotificationsPlugin(app: App, device: Device): Plugin<NotificationsPluginConf>(app, device) {
    override val TAG = "DC/Nots"
    override val MARK = "nots"
    override val NAME = "Notifications"

    companion object {
        fun checkIfNotificationAllowedForAnyDevice(app: App, packageName: String): Boolean {
            app.dm.devices.values.forEach {
                val conf = app.pm.getConfig("nots", it.uin)
                if (conf !is NotificationsPluginConf) return@forEach
                if (conf.getFilter(packageName) != NotificationFilter.NO) return true
            }
            return false
        }
    }

    fun checkAndSendNotification(notification: JSONObject, filter: NotificationFilter, icon: ByteArray?) {
        notification.put("packageIcon", icon != null)
        when (filter) {
            NotificationFilter.ALL -> {
                rpcSend("notification", notification)
                icon?.also { send(it) }
            }
            NotificationFilter.NAME -> {
                rpcSend("notification", notification.apply {
                    put("title", notification.optString("package"))
                    put("text", null)
                })
                icon?.also { send(it) }
            }
            NotificationFilter.HEADER -> {
                rpcSend("notification", notification.apply {
                    put("text", null)
                })
                icon?.also { send(it) }
            }
            else -> return
        }
    }
}
