package net.dcnnt.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import net.dcnnt.MainActivity
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.plugins.*
import net.dcnnt.ui.*
import org.json.JSONArray
import java.lang.NullPointerException
import kotlin.concurrent.thread
import kotlin.math.roundToInt


@SuppressLint("ViewConstructor")
class SyncTaskView(context: Context, parent: SyncFragment, task: SyncTask): EntryView(context) {
    init {
        title = task.name.value
        text = task.getTextInfo()
        iconView.setImageResource(getIcon())
        val iconColor = if (task.enabled.value) R.color.colorPrimary else R.color.colorPrimaryDark
        iconView.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, iconColor))
        setOnClickListener { parent.editSyncTask(context, task) }
        actionView.setOnClickListener { parent.removeSyncTask(context, task) }
        progressView.visibility = View.GONE
    }

    private fun getIcon(): Int {
        return R.drawable.ic_folder
    }
}

class SyncFragment: BasePluginFargment() {
    override val TAG = "DC/SyncFragment"
    lateinit var tasksNotAtAllStr: String
    lateinit var syncTasksView: VerticalLayout
    var selectedConf: SyncPluginConf? = null
    var hasReadContactsPermission = false
    private val requestReadContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { hasReadContactsPermission = it }
    var hasReadMessagesPermission = false
    private val requestReadMessagesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { hasReadMessagesPermission = it }


    private fun updateSelectedConf() {
        selectedConf = null
        selectedDevice?.also {
            selectedConf = APP.pm.getConfig("sync", it.uin) as SyncPluginConf
        }
    }

    override fun onSelectedDeviceChanged() {
        updateSelectedConf()
        updateSyncTasksView(context ?: return)
    }

    private fun askReadContactsPermission() {
        if (ContextCompat.checkSelfPermission(mainActivity,
                Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "ask contacts permission")
            requestReadContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            Log.d(TAG, "already granted")
            hasReadContactsPermission = true
        }
    }

    private fun askReadMessagesPermission() {
        if (ContextCompat.checkSelfPermission(mainActivity,
                Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "ask messages permission")
            requestReadMessagesPermissionLauncher.launch(Manifest.permission.READ_SMS)
        } else {
            Log.d(TAG, "already granted")
            hasReadMessagesPermission = true
        }
    }

    fun updateSyncTasksView(context: Context) {
        syncTasksView.removeAllViews()
        val conf = selectedConf ?: return syncTasksView.addView(
            TextBlockView(context, tasksNotAtAllStr))
        val tasks = conf.getTasks()
        if (tasks.isEmpty()) {
            return syncTasksView.addView(TextBlockView(context, tasksNotAtAllStr))
        }
        tasks.forEach { syncTasksView.addView(SyncTaskView(context, this, it)) }
    }

    fun addSyncTask(context: Context) {
        val conf = selectedConf ?: return
        SelectInputView.showListDialog(context, context.getString(R.string.sync_task_type),
            mutableListOf(
                Option(context.getString(R.string.sync_dir_short), SyncTask.CONF_KEY_DIR),
                Option(context.getString(R.string.sync_file_short), SyncTask.CONF_KEY_FILE),
                Option(context.getString(R.string.sync_contacts_short), SyncTask.CONF_KEY_CONTACTS),
                Option(context.getString(R.string.sync_messages_short), SyncTask.CONF_KEY_MESSAGES),
            )) { _, option ->
                when ("${option.value}") {
                    SyncTask.CONF_KEY_CONTACTS -> askReadContactsPermission()
                    SyncTask.CONF_KEY_MESSAGES -> askReadMessagesPermission()
                    else -> {}
                }
                conf.addTask(conf.getSyncTask("${option.value}"))
                conf.dump()
                updateSyncTasksView(context)
            }
    }

    fun removeSyncTask(context: Context, task: SyncTask) {
        AlertDialog.Builder(context).also {
            it.setTitle(context.getString(R.string.sync_remove_task))
            it.setMessage(context.getString(R.string.delete_device_warning))
            it.setNegativeButton(context.getString(R.string.cancel)) { _, _ -> }
            it.setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                task.parent.removeTask(task)
                selectedConf?.dump()
                updateSyncTasksView(context)
            }
        }.create().show()
    }

    fun editSyncTask(context: Context, task: SyncTask) {
        (activity as? MainActivity)?.navigation?.go(
            "/sync/task", listOf(task.parent.uin.value, task.confKey))
    }

    fun fragmentMainView(context: Context): View = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context, availableOnly = false, addCommon = false))
        addView(Button(context).apply {
            text = context.getString(R.string.sync_add_task)
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
        updateSelectedConf()
        updateSyncTasksView(context ?: return)
    }
}

class SyncTaskEditFragment: DCFragment() {
    val TAG = "DC/SyncEditFragment"
    lateinit var device: Device
    lateinit var task: SyncTask
    lateinit var conf: SyncPluginConf
    private lateinit var confListView: ConfListView
    private var targetView: TextInputView? = null
    private lateinit var progressDialog: DCProgressDialog

    companion object {
        private const val ARG_UIN = "uin"
        private const val ARG_KEY = "key"
        fun newInstance(uin: Int, taskKey: String) = SyncTaskEditFragment().apply {
            arguments = bundleOf(ARG_UIN to uin, ARG_KEY to taskKey)
        }
    }

    override fun prepareToolbar(toolbarView: Toolbar) {
        toolbarView.menu.also { menu ->
            menu.clear()
            menu.add(R.string.do_it_now).setOnMenuItemClickListener {
                progressDialog.setProgressText("...")
                progressDialog.setProgressValue(0)
                progressDialog.setIndeterminate(true)
                progressDialog.show()
                val plugin = SyncPlugin(APP, device)
                plugin.init(APP.applicationContext)
                thread {
                    try {
                        var progressPre = 0.0
                        task.execute(plugin) { cur, total, _ ->
                            activity?.runOnUiThread {
                                progressDialog.setIndeterminate(false)
                                val progress = cur.toDouble() / total
                                if ((progress - progressPre > 0.001) or ((progressPre == 0.0) and (progress > 0))) {
                                    progressDialog.setProgressText("${(progress * 100).roundToInt()}%")
                                    progressDialog.setProgressValue(progress)
                                }
                                progressPre = progress
                            }
                        }
                        context?.also { toast(it, "Task '${task.name.value}' - OK") }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        context?.also { showError(it, e) }
                    } finally {
                        activity?.runOnUiThread { progressDialog.hide() }
                    }
                }
                true
            }
        }
    }

    fun selectTarget(context: Context, view: TextInputView, title: String) {
        view.text = "..."
        val options = mutableListOf<Option>()
        thread {
            try {
                val plugin = SyncPlugin(APP, device)
                plugin.init(context)
                plugin.connect()
                val res = (plugin.rpc("get_targets", mapOf("sub" to task.SUB)) as? JSONArray)
                    ?: throw PluginException("Incorrect response")
                for (i in 0 .. res.length()) {
                    res.optString(i).also {
                        if (it.isNotEmpty()) options.add(Option(it, it))
                    }
                }
            } catch (e: Exception) {
                showError(context, e)
            }
            if (options.isEmpty()) {
                toast(context, getString(R.string.sync_no_targets))
                return@thread
            }
            activity?.runOnUiThread {
                SelectInputView.showListDialog(context, title, options) { _, option ->
                    val v = option.value.toString()
                    view.text = v
                    (task as? DirectorySyncTask)?.target?.updateValue(v)
                    (task as? FileSyncTask)?.target?.updateValue(v)
                }
            }
        }
    }

    fun fragmentMainView(context: Context) = ScrollView(context).apply {
        addView(ConfListView(context, this@SyncTaskEditFragment).apply {
            alternativeConfNames = listOf("sync")
            confListView = this
            init(task)
            (confViews["target"] as? TextInputView)?.also {
                targetView = it
                it.setOnClickListener { _ ->
                    selectTarget(context, it, it.title)
                }
            }
            confViews["useCRC"]?.also { it.visibility = View.GONE }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.also { args ->
            val uin = args.getInt(ARG_UIN)
            device = APP.dm.devices.getValue(uin)
            conf = APP.pm.getConfig("sync", uin) as SyncPluginConf
            task = conf.tasks[args.getString(ARG_KEY)] ?: return
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        container?.context?.also {
            progressDialog = DCProgressDialog(it).apply {
                setTitle(R.string.channel_progress_name)
            }
            return fragmentMainView(it)
        }
        return null
    }

}
