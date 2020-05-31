package net.dcnnt.fragments

import android.Manifest
import net.dcnnt.plugins.RemoteEntry
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.plugins.FileTransferPlugin
import net.dcnnt.ui.*
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


enum class FileSort {NAME, TYPE, SIZE}


class RemoteEntryView(context: Context,
                      private val fragment: DownloadFileFragment,
                      private val remoteEntry: RemoteEntry,
                      private val isUp: Boolean = false): EntryView(context) {
    private val fragmentContext = context
    private val sizeUnits = context.getString(if (remoteEntry.isDir) R.string.unit_entries else R.string.unit_bytes)

    init {
        title = remoteEntry.name
        text = "${remoteEntry.size} $sizeUnits"
        progressView.visibility = View.GONE
        actionView.visibility = View.GONE
        initIcon()
        showSelectionState()
        setOnClickListener { onClick() }
        setOnLongClickListener { onLongClick(); true }
    }

    private fun isEntrySelected() = fragment.selectedEntries.contains(remoteEntry)

    private fun initIcon() = iconView.apply {
        val iconId = if (remoteEntry.isDir) R.drawable.ic_folder else fileIconByPath(remoteEntry.name)
        setImageResource(iconId)
    }

    private fun showSelectionState(value: Boolean? = null) {
        val isSelected: Boolean = value ?: isEntrySelected()
        val iconColor = if (isSelected) R.color.colorPrimary else R.color.colorPrimaryDark
        iconView.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(fragmentContext, iconColor))
    }

    private fun setSelection(value: Boolean) {
        if (remoteEntry.isDir) {
            fragment.setDirSelection(remoteEntry, value)
        } else {
            fragment.setFileSelection(remoteEntry, value)
        }
        showSelectionState(value)
    }

    private fun onClick() {
        if (isUp) return fragment.openUpperRemoteDir(fragmentContext)
        if (remoteEntry.isDir) {
            fragment.openRemoteDir(fragmentContext, remoteEntry)
        } else {
            if (fragment.selectedEntries.isEmpty()) {
                fragment.selectedEntries.add(remoteEntry)
                showSelectionState(true)
                fragment.downloadFiles(fragmentContext)
            } else {
                setSelection(!isEntrySelected())
            }
        }
    }

    private fun onLongClick() {
        if (isUp) return fragment.openUpperRemoteDir(fragmentContext)
        setSelection(!isEntrySelected())
    }
}

class DownloadingFileView(context: Context,
                          private val fragment: DownloadFileFragment,
                          private val remoteEntry: RemoteEntry): EntryView(context) {
    val TAG = "DC/DownloadingFileView"
    init {
        title = remoteEntry.name
        text = "${remoteEntry.size} ${context.getString(R.string.unit_bytes)}"
        progressView.progress = 0
        iconView.setImageResource(fileIconByPath(remoteEntry.name))
        actionView.setImageResource(R.drawable.ic_cancel)
        actionView.setOnClickListener { onActionViewClicked() }
        fragment.selectedEntriesView[remoteEntry.index as Long] = this
    }

    private fun onActionViewClicked() {
        synchronized(remoteEntry) {
            when (remoteEntry.status) {
                FileEntryStatus.WAIT -> {
                    this.visibility = View.GONE
                    remoteEntry.status = FileEntryStatus.CANCEL
                    if (!fragment.pluginRunning.get()) {
                        fragment.selectedEntries.remove(remoteEntry)
                    }
                }
                FileEntryStatus.RUN -> { remoteEntry.status = FileEntryStatus.CANCEL }
                else -> {}
            }
        }
    }

    /**
     * Update onClick listeners and thumbnail after successful download
     */
    fun updateSuccess() {
        actionView.setImageResource(R.drawable.ic_share)
        actionView.setOnClickListener { openOrShare(false) }
        leftView.setOnClickListener { openOrShare(true) }
        try {
            remoteEntry.data?.also { ThumbnailUtils.extractThumbnail(
                    BitmapFactory.decodeByteArray(it, 0, it.size), iconView.width, iconView.height)?.also { b ->
                    iconView.setImageBitmap(b)
                    iconView.imageTintList = null
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "$e")
        }
        remoteEntry.data = null
    }

    private fun openOrShare(isOpen: Boolean = true) {
        val file = remoteEntry.localFile ?: return
        val uri = FileProvider.getUriForFile(context.applicationContext, "net.dcnnt", file)
        val mime = mimeTypeByPath(file.path)
        val action = if (isOpen) Intent.ACTION_VIEW else Intent.ACTION_SEND
        val actionName = context.getString(if (isOpen) R.string.action_open else R.string.action_share)
        val intent = Intent(action)
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        Log.d(TAG, "$actionName $uri, mime = $mime")
        if (isOpen) {
            intent.setDataAndNormalize(uri)
        } else {
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.type = mime
        }
        context.startActivity(Intent.createChooser(intent, "$actionName ${remoteEntry.name}"))
    }
}


class DownloadFileFragment(toolbarView: Toolbar): BasePluginFargment(toolbarView) {
    override val TAG = "DC/DownloadUI"
    val selectedEntries = mutableListOf<RemoteEntry>()
    val selectedEntriesView = mutableMapOf<Long, DownloadingFileView>()
    private val WRITE_EXTERNAL_STORAGE_CODE = 1
    private lateinit var selectButton: Button
    private lateinit var downloadButton: Button
    private lateinit var cancelButton: Button
    private var hasWriteFilePermission = false
    private var remotePath = mutableListOf<RemoteEntry>()
    private var remoteRoot: RemoteEntry = RemoteEntry("root", 0, children = listOf())
    val pluginRunning = AtomicBoolean(false)
    private lateinit var filesView: VerticalLayout
    private var downloadViewMode = false
    private var mainView: View? = null
    private var sortBy: FileSort = FileSort.NAME
    private lateinit var notification: ProgressNotification
    private lateinit var noSharedFilesStr: String
    private lateinit var unitBytesStr: String
    private lateinit var statusCancelStr: String
    private lateinit var notificationDownloadStr: String
    private lateinit var notificationDownloadCompleteStr: String

    override fun prepareToolbar() {
        toolbarView.menu.also { menu ->
            menu.clear()
            menu.add(R.string.sort_by_name).setOnMenuItemClickListener { setSorting(context, FileSort.NAME) }
            menu.add(R.string.sort_by_type).setOnMenuItemClickListener { setSorting(context, FileSort.TYPE) }
            menu.add(R.string.sort_by_size).setOnMenuItemClickListener { setSorting(context, FileSort.SIZE) }
        }
    }

    override fun onBackPressed(): Boolean {
        if (downloadViewMode) return true
        if (remotePath.isEmpty()) return true
        openUpperRemoteDir(context)
        return false
    }

    private fun askWritePermission() {
        Log.d(TAG, "activity = $activity")
        val activity = activity ?: return
        if (ContextCompat.checkSelfPermission(activity as Context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "ask permission")
            ActivityCompat.requestPermissions(activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_EXTERNAL_STORAGE_CODE)
        } else {
            Log.d(TAG, "already granted")
            hasWriteFilePermission = true
        }
    }

    fun setFileSelection(entry: RemoteEntry, value: Boolean) {
        if (value) {
            selectedEntries.add(entry)
        } else {
            selectedEntries.remove(entry)
        }
    }

    fun setDirSelection(entry: RemoteEntry, value: Boolean) {
        setFileSelection(entry, value)
        entry.children?.forEach {
            setFileSelection(it, value)
            if (it.isDir) setDirSelection(it, value)
        }
    }

    private fun updateFilesView(context: Context) {
        val rootChildren = remoteRoot.children ?: return showNoSharedFilesMessage(context)
        if (rootChildren.isEmpty()) return showNoSharedFilesMessage(context)
        if (rootChildren.size == 1) {
            openRemoteDir(context, rootChildren[0])
        } else {
            showRemoteDir(context, remoteRoot)
        }
    }

    private fun showNoSharedFilesMessage(context: Context) {
        downloadButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        selectButton.visibility = View.VISIBLE
        filesView.removeAllViews()
        filesView.addView(TextBlockView(context, noSharedFilesStr))
    }

    private fun showRemoteDir(context: Context, entry: RemoteEntry) {
        downloadButton.visibility = View.VISIBLE
        cancelButton.visibility = View.GONE
        selectButton.visibility = View.VISIBLE
        filesView.removeAllViews()
        when (remotePath.size) {
            0 -> null
            1 -> remoteRoot
            else -> remotePath[remotePath.lastIndex - 1]
        }?.also {
            filesView.addView(RemoteEntryView(context, this,
                RemoteEntry("..", it.size, null, listOf()), true))
        }
        when (sortBy) {
            FileSort.NAME -> entry.children?.sortedBy { "${!it.isDir} ${it.name.toUpperCase(Locale.getDefault())}" }
            FileSort.TYPE -> entry.children?.sortedBy {
                val locale = Locale.getDefault()
                val extensionUpper = it.name.split('.').lastOrNull()?.toUpperCase(locale) ?: ""
                val nameUpper = it.name.toUpperCase(locale)
                "${!it.isDir} $extensionUpper $nameUpper"
            }
            FileSort.SIZE -> entry.children?.sortedBy { if (it.isDir) it.size else (0xFFFFFFFF + it.size) }
        }?.forEach { filesView.addView(RemoteEntryView(context, this, it)) }
    }

    fun openRemoteDir(context: Context, entry: RemoteEntry) {
        remotePath.add(entry)
        showRemoteDir(context, entry)
    }

    fun openUpperRemoteDir(context: Context?) {
        remotePath.removeAt(remotePath.lastIndex)
        showRemoteDir(context ?: return, remotePath.lastOrNull() ?: remoteRoot)
    }

    private fun setSorting(context: Context?, value: FileSort): Boolean {
        sortBy = value
        if (downloadViewMode) {
            return true
        } else {
            showRemoteDir(context ?: return false, remotePath.lastOrNull() ?: remoteRoot)
        }
        return true
    }

    private fun cancelAllFiles() {
        selectedEntries.forEach {
            synchronized(it) {
                if ((it.status == FileEntryStatus.WAIT) or (it.status == FileEntryStatus.RUN)) {
                    it.status = FileEntryStatus.CANCEL
                }
            }
        }
    }

    private fun updateRemoteFiles(context: Context) {
        synchronized(downloadViewMode) {
            downloadViewMode = false
            selectedDevice?.also {
                val plugin = FileTransferPlugin(APP, it)
                thread {
                    pluginRunning.set(true)
                    try {
                        plugin.init()
                        plugin.connect()
                        remoteRoot = plugin.listRemoteDir(listOf("/"))
                        remotePath.clear()
                        selectedEntries.clear()
                        Log.d(TAG, "$remoteRoot")
                    } catch (e: Exception) {
                        showError(context, e)
                    }
                    activity?.runOnUiThread { updateFilesView(context) }
                    pluginRunning.set(false)
                }
            }
        }
    }

    private fun enableDownloadView(context: Context) {
        synchronized(downloadViewMode) {
            filesView.removeAllViews()
            selectedEntries.forEach { if (!it.isDir) filesView.addView(DownloadingFileView(context, this, it)) }
            downloadViewMode = true
        }
    }

    fun downloadFiles(context: Context) {
        if (!hasWriteFilePermission) return askWritePermission()
        val device = selectedDevice ?: return
        if (selectedEntries.none { !it.isDir }) return
        selectButton.visibility = View.GONE
        downloadButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        enableDownloadView(context)
        thread {
            pluginRunning.set(true)
            try {
                FileTransferPlugin(APP, device).apply {
                    init()
                    connect()
                    val waitingEntries = selectedEntries.filter { (it.status == FileEntryStatus.WAIT) and !it.isDir }
                    notification.create(R.drawable.ic_download,
                                  notificationDownloadStr,
                            "0/${waitingEntries.size}",
                                  1000L * waitingEntries.size)
                    waitingEntries.forEachIndexed { index, it ->
                        synchronized(it) {
                            if (it.status != FileEntryStatus.WAIT) {
                                Log.d(TAG, "Skip ${it.index} (${it.name})")
                                if (it.status == FileEntryStatus.CANCEL) {
                                    selectedEntriesView[it.index]?.apply {
                                        text = "${it.size} $unitBytesStr - $statusCancelStr"
                                        actionView.setImageResource(R.drawable.ic_block)
                                    }
                                }
                                return@forEachIndexed
                            }
                            if (breakTransfer) {
                                breakTransfer = false
                                connect()
                            }
                            it.status = FileEntryStatus.RUN
                        }
                        var progress = 0
                        var res: DCResult
                        try {
                            res = downloadFile(it, context.contentResolver) { cur: Long, total: Long, _: Long ->
                                val progressCur = (1000 * cur / total).toInt()
                                breakTransfer = it.status == FileEntryStatus.CANCEL
                                activity?.runOnUiThread {
                                    selectedEntriesView[it.index]?.also { v ->
                                        v.text = "$cur/$total $unitBytesStr"
                                        if (progressCur != progress) {
                                            progress = progressCur
                                            v.progressView.progress = progressCur
                                            notification.update("$index/${waitingEntries.size}",
                                                   (1000L * index) + progressCur)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            res = DCResult(false, e.message ?: "$e")
                        }
                        (res.data as? File)?.also { file ->
                            if (res.success) {
                                it.localFile = file
                                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                    Uri.fromFile(file)))
                            }
                        }
                        activity?.runOnUiThread {
                            selectedEntriesView[it.index]?.also { v ->
                                v.text = "${it.size} $unitBytesStr - ${res.message}"
                                if (res.success) {
                                    v.updateSuccess()
                                } else {
                                    v.actionView.setImageResource(R.drawable.ic_block)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                showError(context, e)
                Log.e(TAG, "$e")
            }
            activity?.runOnUiThread {
                cancelButton.visibility = View.GONE
                selectButton.visibility = View.VISIBLE
                downloadButton.visibility = View.GONE
            }
            notification.complete(notificationDownloadCompleteStr, "")
            pluginRunning.set(false)
        }
    }

    private fun fragmentMainView(context: Context) = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context).apply {
            onUpdateOptons = { _, changed, _ -> if (changed) updateRemoteFiles(context) }
        })
        addView(LinearLayout(context).apply {
            val lp = LinearLayout.LayoutParams(LParam.W, LParam.W, .5F)
            addView(Button(context).apply {
                selectButton = this
                text = context.getString(R.string.button_select_file)
                setOnClickListener { updateRemoteFiles(context) }
                layoutParams = lp
            })
            addView(Button(context).apply {
                downloadButton = this
                text = context.getString(R.string.button_download)
                setOnClickListener { downloadFiles(context) }
                layoutParams = lp
            })
            addView(Button(context).apply {
                cancelButton = this
                text = context.getString(R.string.button_cancel)
                visibility = View.GONE
                setOnClickListener { cancelAllFiles() }
                layoutParams = lp
            })
        })
        addView(ScrollView(context).apply {
            LParam.set(this, LParam.mm())
            addView(VerticalLayout(context).apply {
                filesView = this
                addView(TextBlockView(context, context.getString(R.string.no_files_info)))
            })
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askWritePermission()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            WRITE_EXTERNAL_STORAGE_CODE -> hasWriteFilePermission = (grantResults.isNotEmpty() &&
                    (grantResults[0] == PackageManager.PERMISSION_GRANTED))
            else -> {}
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView, mainView = $mainView, downloadViewMode = $downloadViewMode")
        noSharedFilesStr = getString(R.string.no_shared_files)
        unitBytesStr = getString(R.string.unit_bytes)
        statusCancelStr = getString(R.string.status_cancel)
        notificationDownloadStr = getString(R.string.notification_download)
        notificationDownloadCompleteStr = getString(R.string.notification_download_complete)
        mainView?.also { return it }
        container?.context?.also { return fragmentMainView(it).apply { mainView = this } }
        return null
    }

    override fun onStart() {
        super.onStart()
        context?.also {
            if (!downloadViewMode and selectedEntries.isEmpty()) updateRemoteFiles(it)
            notification = ProgressNotification(it)
        }
    }
}
