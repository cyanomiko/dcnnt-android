package net.dcnnt.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.marginStart
import androidx.core.view.setMargins
import com.google.android.material.floatingactionbutton.FloatingActionButton
import net.dcnnt.R
import net.dcnnt.core.APP
import net.dcnnt.core.Task
import net.dcnnt.core.nowString
import net.dcnnt.plugins.DirectorySyncTask
import net.dcnnt.plugins.SyncPluginConf
import net.dcnnt.plugins.SyncTask
import net.dcnnt.ui.*

@SuppressLint("ViewConstructor")
class SyncTaskView(context: Context, parent: SyncFragment, task: SyncTask): EntryView(context) {
    init {
        title = task.name.value
        text = task.getTextInfo()
        iconView.setImageResource(getIcon())
        val iconColor = if (task.enabled.value) R.color.colorPrimary else R.color.colorPrimaryDark
        iconView.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, iconColor))
        setOnClickListener { parent.toast(context, "Not implemented") }
        actionView.setOnClickListener { parent.removeSyncTask(context, task) }
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
        tasks.forEach { syncTasksView.addView(SyncTaskView(context, this, it)) }
    }

    fun addSyncTask(context: Context) {
        val conf = APP.pm.getConfig("sync", (selectedDevice ?: return).uin) as SyncPluginConf
        conf.addTask(DirectorySyncTask(conf, nowString()).apply { init() })
        updateSyncTasksView(context)
    }

    fun removeSyncTask(context: Context, task: SyncTask) {
        AlertDialog.Builder(context).also {
            it.setTitle("Remove sync task?")
            it.setMessage(context.getString(R.string.delete_device_warning))
            it.setNegativeButton(context.getString(R.string.cancel)) { _, _ -> }
            it.setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                task.parent.removeTask(task)
                updateSyncTasksView(context)
            }
        }.create().show()
    }

    fun fragmentMainView(context: Context): View = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context, availableOnly = false, addCommon = false))
        addView(Button(context).apply {
            text = "Add sync task"
            //visibility = View.GONE
            LParam.set(this, LParam.mw())
            setOnClickListener { addSyncTask(context) }
        })
        addView(ScrollView(context).apply {
            addView(VerticalLayout(context).apply {
                syncTasksView = this
            })
            LParam.set(this, LParam.mm())
        })
//        addView(FloatingActionButton(context).apply {
//            setImageResource(R.drawable.ic_add)
//            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
//                LinearLayout.LayoutParams.WRAP_CONTENT)
//            val m = context.dip(16)
//            lp.setMargins(m, m, m, m)
//            LParam.set(this, lp)
//            gravity = Gravity.BOTTOM or Gravity.END
//            setOnClickListener {  }
//        })
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
