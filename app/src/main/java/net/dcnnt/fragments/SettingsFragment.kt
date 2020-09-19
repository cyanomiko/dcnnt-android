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
import net.dcnnt.MainActivity
import net.dcnnt.R
import net.dcnnt.core.ACTION_NOTIFICATION_LISTENER_SETTINGS
import net.dcnnt.core.APP
import net.dcnnt.core.nowString
import net.dcnnt.ui.BoolInputView
import net.dcnnt.ui.ConfListView
import net.dcnnt.ui.DCFragment
import net.dcnnt.ui.TextInputView


class SettingsFragment: DCFragment() {
    val TAG = "DC/SettingsFragment"
    private var notificationConfView: BoolInputView? = null
    private var downloadDirectoryView: TextInputView? = null
    private val CODE_SAVE_SETTINGS = 141
    private val CODE_LOAD_SETTINGS = 142
    private val CODE_SELECT_DOWNLOAD_DIRECTORY = 143
    private lateinit var confListView: ConfListView

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
        (activity as? MainActivity)?.restartApp()
        return true
    }

    fun dumpSettings(): Boolean {
        activity?.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "dcnnt-data-${nowString()}.zip")
            type = "application/zip"
        }, CODE_SAVE_SETTINGS)
        return true
    }

    fun loadSettings(): Boolean {
        activity?.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }, CODE_LOAD_SETTINGS)
        return true
    }

    override fun onActivityResult(mainActivity: MainActivity, requestCode: Int,
                                  resultCode: Int, data: Intent?): Boolean {
        Log.d(TAG, "requestCode = $requestCode, resultCode = $resultCode")
        val contentResolver = context?.contentResolver ?: return true
        val uri = data?.data ?: return true
        if ((requestCode == CODE_SAVE_SETTINGS) and (resultCode == Activity.RESULT_OK)) {
            try {
                APP.dumpSettingsToFile(contentResolver.openOutputStream(uri) ?: return false)
                Toast.makeText(context, "OK", Toast.LENGTH_SHORT).show()
                return false
            } catch (e: Exception) {
                Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show()
            }
        }
        if ((requestCode == CODE_LOAD_SETTINGS) and (resultCode == Activity.RESULT_OK)) {
            try {
                val res = APP.loadSettingsFromFile(contentResolver.openInputStream(uri) ?: return false)
                Toast.makeText(context, "OK", Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.restartApp()
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show()
            }
        }
        if ((requestCode == CODE_SELECT_DOWNLOAD_DIRECTORY) and (resultCode == Activity.RESULT_OK)) {
            Log.d(TAG, "Tree URI: $uri")
            APP.conf.downloadDirectory.setValue(uri.toString())
            updateDownloadDirectoryView()
        }
        return true
    }

    fun checkNotificationAccess() {
        val view = notificationConfView ?: return
        val res = (activity as? MainActivity)?.isNotificationServiceEnabled() ?: false
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
        addView(ConfListView(context).apply {
            confListView = this
            init(APP.conf)
            (confViews[APP.conf.notificationListenerService.name] as? BoolInputView)?.also {
                notificationConfView = it
                it.onInput = { _ ->
                    context.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    checkNotificationAccess()
                }
            }
            (confViews[APP.conf.downloadDirectory.name] as? TextInputView)?.also {
                downloadDirectoryView = it
                it.setOnClickListener {
                    activity?.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            putExtra(DocumentsContract.EXTRA_INITIAL_URI, APP.conf.downloadDirectory.value)
                        }
                    }, CODE_SELECT_DOWNLOAD_DIRECTORY)
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        checkNotificationAccess()
        updateDownloadDirectoryView()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        container?.context?.also { return fragmentMainView(it) }
        return null
    }

}
