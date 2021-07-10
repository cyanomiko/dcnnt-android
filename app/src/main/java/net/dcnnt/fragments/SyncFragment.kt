package net.dcnnt.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import net.dcnnt.R
import net.dcnnt.core.APP
import net.dcnnt.plugins.SyncPluginConf
import net.dcnnt.plugins.SyncTask
import net.dcnnt.ui.*

@SuppressLint("ViewConstructor")
class SyncTaskView(context: Context, task: SyncTask): EntryView(context) {
    init {
        title = task.name.value
        text = task.getTextInfo()
        iconView.setImageResource(getIcon())
        val iconColor = if (isSelected) R.color.colorPrimary else R.color.colorPrimaryDark
        iconView.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, iconColor))
    }

    private fun getIcon(): Int {
        return R.drawable.ic_folder
    }
}

class SyncFragment: BasePluginFargment() {
    override val TAG = "DC/SyncFragment"
    lateinit var tasksNotAtAllStr: String
    lateinit var syncTasksView: VerticalLayout

    override fun onSelectedDeviceChanged() {
        updateSyncTasksView(context ?: return)
    }

    fun updateSyncTasksView(context: Context) {
        syncTasksView.removeAllViews()
        val device = selectedDevice ?: return syncTasksView.addView(
            TextBlockView(context, tasksNotAtAllStr))
        val conf = APP.pm.getConfig("sync", device.uin) as SyncPluginConf
        val tasks = conf.getTasks()
        if (tasks.isEmpty()) {
            return syncTasksView.addView(TextBlockView(context, tasksNotAtAllStr))
        }
        tasks.forEach { syncTasksView.addView(SyncTaskView(context, it)) }
    }

    fun fragmentMainView(context: Context): View = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context, availableOnly = false, addCommon = false))
        addView(ScrollView(context).apply {
            addView(VerticalLayout(context).apply {
                syncTasksView = this
            })
            LParam.set(this, LParam.mm())
        })
        updateSyncTasksView(context)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        container?.context?.also { context ->
            tasksNotAtAllStr = context.getString(R.string.sync_no_tasks)
            return fragmentMainView(context)
        }
        return null
    }

    override fun onStart() {
        super.onStart()
        if (APP.conf.autoSearch.value and !APP.dm.searchDone) {
            updateSyncTasksView(context ?: return)
        }
    }
}
