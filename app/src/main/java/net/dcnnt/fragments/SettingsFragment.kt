package net.dcnnt.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import net.dcnnt.MainActivity
import net.dcnnt.R
import net.dcnnt.core.ACTION_NOTIFICATION_LISTENER_SETTINGS
import net.dcnnt.core.APP
import net.dcnnt.core.newActivityCaller
import net.dcnnt.core.nowString
import net.dcnnt.ui.BoolInputView
import net.dcnnt.ui.ConfListView
import net.dcnnt.ui.DCFragment
import net.dcnnt.ui.TextInputView


class SettingsFragment: DCFragment() {
    val TAG = "DC/SettingsFragment"
    private var notificationConfView: BoolInputView? = null
    private var downloadDirectoryView: TextInputView? = null
    private lateinit var confListView: ConfListView
    private val saveSettingsActivityLauncher = newActivityCaller(this) { _, intent ->
        val contentResolver = context?.contentResolver ?: return@newActivityCaller
        val uri = intent?.data ?: return@newActivityCaller
        try {
            APP.dumpSettingsToFile(contentResolver.openOutputStream(uri)
                ?: return@newActivityCaller)
            Toast.makeText(context, "OK", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show()
        }
    }
    private val loadSettingsActivityLauncher = newActivityCaller(this) { _, intent ->
        val contentResolver = context?.contentResolver ?: return@newActivityCaller
        val uri = intent?.data ?: return@newActivityCaller
        try {
            val res = APP.loadSettingsFromFile(contentResolver.openInputStream(uri)
                ?: return@newActivityCaller)
            Toast.makeText(context, "OK", Toast.LENGTH_SHORT).show()
            mainActivity.restartApp()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show()
        }
    }
    private val selectDownloadDirectoryActivityLauncher = newActivityCaller(this) { _, intent ->
        val uri = intent?.data ?: return@newActivityCaller
        Log.d(TAG, "Tree URI: $uri")
        val contentResolver = context?.contentResolver ?: return@newActivityCaller
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)
        APP.conf.downloadDirectory.updateValue(uri.toString())
        updateDownloadDirectoryView()
        if (arguments?.getInt(ARG_ACTION) == ACTION_DOWNLOAD_DIR) mainActivity.navigation.back()
    }

    companion object {
        private const val ARG_ACTION = "action"
        const val ACTION_NONE = 0
        const val ACTION_DOWNLOAD_DIR = 1
        fun newInstance(action: Int) = SettingsFragment().apply {
            arguments = bundleOf(ARG_ACTION to action)
        }
    }

    override fun prepareToolbar(toolbarView: Toolbar) {
        toolbarView.menu.also { menu ->
            menu.clear()
            menu.add(R.string.dump_settings).setOnMenuItemClickListener { dumpSettings() }
            menu.add(R.string.load_settings).setOnMenuItemClickListener { loadSettings() }
            menu.add(R.string.drop_settings).setOnMenuItemClickListener { dropSettings() }
        }
    }

    fun dropSettings(): Boolean {
        APP.dropSettings()
        Toast.makeText(context, "OK", Toast.LENGTH_SHORT).show()
        mainActivity.restartApp()
        return true
    }

    fun dumpSettings(): Boolean {
        saveSettingsActivityLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "dcnnt-data-${nowString()}.zip")
            type = "application/zip"
        })
        return true
    }

    fun loadSettings(): Boolean {
        loadSettingsActivityLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        })
        return true
    }

    fun checkNotificationAccess() {
        val view = notificationConfView ?: return
        val res = mainActivity.isNotificationServiceEnabled()
        Log.d(TAG, "Notification access = $res")
        view.value = res
        Log.d(TAG, "notificationConfView.value = ${view.value}")
        APP.conf.notificationListenerService.updateValue(view.value)
    }

    fun updateDownloadDirectoryView() {
        (confListView.confViews[APP.conf.downloadDirectory.name] as? TextInputView)?.also {
            it.text = Uri.decode(APP.conf.downloadDirectory.value.split("/").last())
        }
    }

    fun fragmentMainView(context: Context) = ScrollView(context).apply {
        addView(ConfListView(context, this@SettingsFragment).apply {
            confListView = this
            init(APP.conf)
            (confViews[APP.conf.notificationListenerService.name] as? BoolInputView)?.also {
                notificationConfView = it
                it.onInput = { _ ->
                    mainActivity.notificationAccessActivityLauncher.launch(
                        Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    checkNotificationAccess()
                }
            }
            (confViews[APP.conf.downloadDirectory.name] as? TextInputView)?.also {
                downloadDirectoryView = it
                it.setOnClickListener {
                    selectDownloadDirectoryActivityLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            putExtra(DocumentsContract.EXTRA_INITIAL_URI, APP.conf.downloadDirectory.value)
                        }
                    })
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        checkNotificationAccess()
        updateDownloadDirectoryView()
        if (arguments?.getInt(ARG_ACTION) == ACTION_DOWNLOAD_DIR) downloadDirectoryView?.callOnClick()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        container?.context?.also { return fragmentMainView(it) }
        return null
    }
}
