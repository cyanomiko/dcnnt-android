package net.dcnnt.fragments

import android.content.pm.ApplicationInfo
import net.dcnnt.plugins.*
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.doOnTextChanged
import net.dcnnt.R
import net.dcnnt.core.APP
import net.dcnnt.ui.*
import kotlin.concurrent.thread


data class AppInfo(val name: String, val title: String,
                   val icon: ImageView, val isSystem: Boolean, var filter: NotificationFilter)

class NotificationsFragment: BasePluginFargment() {
    override val TAG = "DC/Notifications"
    var conf: NotificationsPluginConf = APP.pm.getConfig("nots", 0) as NotificationsPluginConf
    var appListView: LinearLayout? = null
    var appFilterView: EditText? = null
    val appFiltersTextViews = hashMapOf<String, TextView>()
    val apps = hashMapOf<String, AppInfo>()
    val appNotsSettingsViews = hashMapOf<String, View>()
    var toolbarView: Toolbar? = null

    override fun prepareToolbar(toolbarView: Toolbar) {
        this.toolbarView = toolbarView
        toolbarView.menu.also { menu ->
            menu.clear()
            menu.add(R.string.disable_all).setOnMenuItemClickListener { disableAllEntries() }
            menu.add(R.string.as_common).setOnMenuItemClickListener { copyEntriesFromCommon() }
        }
    }

    private fun updateFilterViews() {
        appFiltersTextViews.keys.forEach {
            appFiltersTextViews[it]?.text = conf.getFilter(it).toString()
        }
    }

    private fun disableAllEntries(): Boolean {
        conf.setFilterForAll(NotificationFilter.NO)
        updateFilterViews()
        return true
    }

    private fun copyEntriesFromCommon(): Boolean {
        conf.copyFilters(APP.pm.getConfig("nots", 0) as NotificationsPluginConf)
        updateFilterViews()
        return true
    }

    fun updateInstalledApps(context: Context, systemApps: Boolean?) {
        activity?.runOnUiThread { appFilterView?.isEnabled = false }
        synchronized(apps) {
            apps.clear()
            context.packageManager.getInstalledPackages(0).forEach { p ->
                val isSystem = (p.applicationInfo.flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                if ((systemApps == null) or (systemApps == isSystem)) {
                    apps[p.packageName] = AppInfo(
                        p.packageName,
                        p.applicationInfo.loadLabel(context.packageManager).toString(),
                        packageIcon(context, p.packageName),
                        isSystem,
                        conf.getFilter(p.packageName)
                    )
                }
            }
        }
        activity?.runOnUiThread { appFilterView?.isEnabled = true }
    }

    fun updateAppsFilters() {
        synchronized(apps) { apps.values.forEach { it.filter = conf.getFilter(it.name) } }
    }

    fun packageIcon(context: Context, packageName: String): ImageView {
        try {
            return ImageView(context).apply {
                setImageDrawable(context.packageManager.getApplicationIcon(packageName))
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "$e")
        }
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_android)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimary))
        }
    }

    fun appNotsSettingsView(context: Context, appInfo: AppInfo) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            (appInfo.icon.parent as? LinearLayout)?.removeAllViews()
            padding = context.dip(6)
            gravity = Gravity.CENTER
            addView(appInfo.icon.apply {
                LParam.set(this, context.dip(32), context.dip(32))
            })
        })
        addView(VerticalLayout(context).apply {
            addView(TextView(context).apply {
                isSingleLine = true
                textAppearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Body2
                LParam.set(this, LParam.mw())
                text = appInfo.title
            })
            addView(TextView(context).apply { text = appInfo.name })
            addView(TextView(context).apply {
                text = appInfo.filter.toString()
                appFiltersTextViews[appInfo.name] = this
            })
        })
        setOnClickListener {
            conf.setFilter(appInfo.name, when (conf.getFilter(appInfo.name)) {
                NotificationFilter.NO -> NotificationFilter.NAME
                NotificationFilter.NAME -> NotificationFilter.HEADER
                NotificationFilter.HEADER -> NotificationFilter.ALL
                NotificationFilter.ALL -> NotificationFilter.NO
            })
            Log.d(TAG, "Set filter ${appInfo.name} = ${conf.getFilter(appInfo.name)} for UIN ${conf.uin.value}")
            appFiltersTextViews[appInfo.name]?.text = conf.getFilter(appInfo.name).toString()
        }
    }

    override fun onSelectedDeviceChanged() {
        val uin = selectedDevice?.uin ?: 0
        toolbarView?.menu?.getItem(1)?.isVisible = (uin != 0)
        conf = APP.pm.getConfig("nots", selectedDevice?.uin ?: 0) as NotificationsPluginConf
        Log.d(TAG, "Conf for device $uin: ${conf.uin}")
        updateAppsFilters()
        redrawApps(context ?: return)
    }

    fun fragmentMainView(context: Context) = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context, availableOnly = false, addCommon = true))
        addView(EditText(context).apply {
            appFilterView = this
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            hint = context.getString(R.string.search)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    Log.d(TAG, "edit: text = $s")
                    s?.also { filterApps(it.toString()) }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        })
        addView(ScrollView(context).apply {
            addView(VerticalLayout(context).apply { appListView = this })
            LParam.set(this,  LParam.mm())
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        container?.context?.also {
            val view = fragmentMainView(it)
            redrawApps(it)
            return view
        }
        return null
    }

    fun filterApps(query: String) {
        apps.values.forEach { appInfo ->
            appNotsSettingsViews[appInfo.name]?.visibility =
                when (appInfo.name.contains(query, true) or
                        appInfo.title.contains(query, true)) {
                    true -> View.VISIBLE
                    false -> View.GONE
                }
        }
    }

    fun redrawApps(context: Context) {
        appNotsSettingsViews.clear()
        appListView?.removeAllViews()
        appListView?.addView(TextBlockView(context, context.getString(R.string.loading_apps)))
        thread {
            if (apps.isEmpty()) updateInstalledApps(context, null)
            val sorted = apps.values.sortedBy { it.title }
            activity?.runOnUiThread { appListView?.removeAllViews() }
            sorted.forEach {
                activity?.runOnUiThread {
                    appNotsSettingsViews[it.name] = appNotsSettingsView(context, it)
                    appListView?.addView(appNotsSettingsViews[it.name])
                }
            }
        }
    }
}
