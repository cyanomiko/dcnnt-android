package net.dcnnt.fragments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import net.dcnnt.MainActivity
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.plugins.FileTransferPlugin
import net.dcnnt.ui.*
import java.util.*
import kotlin.concurrent.thread


enum class FileSort {NAME, TYPE, SIZE}


class RemoteEntryView(context: Context,
                      private val fragment: DownloadFileFragment,
                      private val remoteEntry: FileEntry,
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


class DownloadFileFragment: BaseFileFragment() {
    override val TAG = "DC/DownloadUI"
    private var remotePath = mutableListOf<FileEntry>()
    private var remoteRoot = FileEntry("root", 0, remoteChildren = listOf())
    private var downloadViewMode = false
    private var downloadDirectoryOk = false
    private var sortBy: FileSort = FileSort.NAME
    private lateinit var noSharedFilesStr: String

    override fun prepareToolbar(toolbarView: Toolbar) {
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

    fun openRemoteDir(context: Context, entry: FileEntry) {
        remotePath.add(entry)
        showRemoteDir(context, entry)
    }

    fun openUpperRemoteDir(context: Context?) {
        remotePath.removeAt(remotePath.lastIndex)
        showRemoteDir(context ?: return, remotePath.lastOrNull() ?: remoteRoot)
    }

    private fun setSorting(context: Context?, value: FileSort): Boolean {
        sortBy = value
        if (downloadViewMode) return true
        showRemoteDir(context ?: return false, remotePath.lastOrNull() ?: remoteRoot)
        return true
    }

    private fun showRemoteDir(context: Context, entry: FileEntry) {
        actionButton.visibility = View.VISIBLE
        cancelButton.visibility = View.GONE
        selectButton.visibility = View.VISIBLE
        repeatButton.visibility = View.GONE
        selectedView.removeAllViews()
        when (remotePath.size) {
            0 -> null
            1 -> remoteRoot
            else -> remotePath[remotePath.lastIndex - 1]
        }?.also {
            selectedView.addView(RemoteEntryView(context, this,
                FileEntry("..", it.size, FileStatus.WAIT, remoteChildren = listOf()), true))
        }
        when (sortBy) {
            FileSort.NAME -> entry.remoteChildren?.sortedBy { "${!it.isDir} ${it.name.uppercase()}" }
            FileSort.TYPE -> entry.remoteChildren?.sortedBy {
                val extensionUpper = it.name.split('.').lastOrNull()?.uppercase() ?: ""
                val nameUpper = it.name.uppercase()
                "${!it.isDir} $extensionUpper $nameUpper"
            }
            FileSort.SIZE -> entry.remoteChildren?.sortedBy { if (it.isDir) it.size else (0xFFFFFFFF + it.size) }
        }?.forEach { selectedView.addView(RemoteEntryView(context, this, it)) }
    }

    private fun showNoSharedFilesMessage(context: Context) {
        actionButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        selectButton.visibility = View.VISIBLE
        repeatButton.visibility = View.GONE
        selectedView.removeAllViews()
        selectedView.addView(TextBlockView(context, noSharedFilesStr))
    }

    private fun updateFilesView(context: Context) {
        val rootChildren = remoteRoot.remoteChildren ?: return showNoSharedFilesMessage(context)
        if (rootChildren.isEmpty()) return showNoSharedFilesMessage(context)
        if (rootChildren.size == 1) {
            openRemoteDir(context, rootChildren[0])
        } else {
            showRemoteDir(context, remoteRoot)
        }
    }

    fun setFileSelection(entry: FileEntry, value: Boolean) {
        if (value) {
            selectedEntries.add(entry)
        } else {
            selectedEntries.remove(entry)
        }
    }

    fun setDirSelection(entry: FileEntry, value: Boolean) {
        setFileSelection(entry, value)
        entry.remoteChildren?.forEach {
            setFileSelection(it, value)
            if (it.isDir) setDirSelection(it, value)
        }
    }

    private fun enableDownloadView(context: Context) {
        synchronized(downloadViewMode) {
            actionButton.visibility = View.GONE
            cancelButton.visibility = View.VISIBLE
            selectButton.visibility = View.GONE
            repeatButton.visibility = View.GONE
            selectedView.removeAllViews()
            selectedEntries.forEach { if (!it.isDir) selectedView.addView(RunningFileView(context, this, it)) }
            downloadViewMode = true
        }
    }

    override fun selectEntries(context: Context) {
        synchronized(downloadViewMode) {
            downloadViewMode = false
            selectedDevice?.also {
                val plugin = FileTransferPlugin(APP, it)
                thread {
                    pluginRunning.set(true)
                    try {
                        plugin.init(context)
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

    override fun processAllEntries(context: Context) {
        downloadFiles(context)
    }

    override fun processFailedEntries(context: Context) {
        synchronized(selectedEntries) {
            selectedEntries.removeAll { it.status == FileStatus.DONE }
            selectedEntries.forEach { it.status = FileStatus.WAIT }
        }
        downloadFiles(context)
    }

    override fun getPolicy(): String = APP.conf.downloadNotificationPolicy.value

    fun checkDownloadDirectory() {
        downloadDirectoryOk = try {
            context?.let {
                DocumentFile.fromTreeUri(it, Uri.parse(APP.conf.downloadDirectory.value))
                    ?.canWrite()
            } == true
        } catch (e: Exception) {
            false
        }
    }

    fun downloadFiles(context: Context) {
        if (!hasWriteFilePermission) return askWritePermission()
        val device = selectedDevice ?: return
        if (selectedEntries.none { !it.isDir }) return
        enableDownloadView(context)
        val waitingEntries = selectedEntries.filter { (it.status == FileStatus.WAIT) and !it.isDir }
        var totalSize = (LongArray(waitingEntries.size) { waitingEntries[it].size }).sum()
        var totalDoneSize = 0L
        var totalDoneSizePre = 0L
        thread {
            pluginRunning.set(true)
            try {
                FileTransferPlugin(APP, device).apply {
                    init(context)
                    connect()
                    waitingEntries.forEachIndexed { index, it ->
                        var currentDoneSize = 0L
                        totalDoneSizePre = totalDoneSize
                        synchronized(it) {
                            if (it.status != FileStatus.WAIT) {
                                Log.d(TAG, "Skip ${it.remoteIndex} (${it.name})")
                                if (it.status == FileStatus.CANCEL) {
                                    selectedEntriesView[it.idStr]?.apply {
                                        text = "${it.size} $unitBytesStr - $statusCancelStr"
                                        actionView.setImageResource(R.drawable.ic_block)
                                    }
                                }
                                totalSize -= it.size
                                return@forEachIndexed
                            }
                            if (breakTransfer) {
                                breakTransfer = false
                                connect()
                            }
                            it.status = FileStatus.RUN
                        }
                        var progress = 0
                        var res: DCResult
                        notifyDownloadStart(waitingEntries, index + 1, it, totalSize, totalDoneSize, currentDoneSize)
                        try {
                            res = downloadFile(it, context.contentResolver) { cur: Long, total: Long, _: Long ->
                                if (cur > currentDoneSize) {
                                    totalDoneSize += cur - currentDoneSize
                                    currentDoneSize = cur
                                }
                                val progressCur = (1000 * cur / total).toInt()
                                breakTransfer = it.status == FileStatus.CANCEL
                                activity?.runOnUiThread {
                                    selectedEntriesView[it.idStr]?.also { v ->
                                        v.text = "$cur/$total $unitBytesStr"
                                        if (progressCur != progress) {
                                            progress = progressCur
                                            v.progressView.progress = progressCur
                                            notifyDownloadProgress(waitingEntries, index + 1, it, totalSize, totalDoneSize, currentDoneSize)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            res = DCResult(false, e.message ?: "$e")
                        }
                        if (it.status != FileStatus.CANCEL) {
                            it.status = if (res.success) FileStatus.DONE else FileStatus.FAIL
                        }
                        if (res.success) {
                            totalDoneSize = totalDoneSizePre + it.size
                        } else {
                            totalSize -= it.size
                            totalDoneSize = totalDoneSizePre
                        }
                        notifyDownloadEnd(waitingEntries, index + 1, it, totalSize, totalDoneSize, res)
                    }
                }
            } catch (e: Exception) {
                showError(context, e)
                Log.e(TAG, "$e")
            }
            setButtonsVisibilityOnEnd()
            pluginRunning.set(false)
        }
    }

    fun fragmentConfigureView(context: Context) = ScrollView(context).apply {
        padding = context.dip(6)
        addView(VerticalLayout(context).apply {
            LParam.set(this, LParam.mm())
            addView(TextBlockView(context, context.getString(R.string.directory_not_set)))
            addView(Button(context).apply {
                selectButton = this
                text = context.getString(R.string.configure)
                layoutParams = LParam.mw()
                setOnClickListener {
                    (activity as? MainActivity)?.navigation?.go(
                        "/settings", listOf(SettingsFragment.ACTION_DOWNLOAD_DIR))
                }
            })
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        mainView?.also { return it }
        container?.context?.also { context ->
            notification = ProgressNotification(context)
            checkDownloadDirectory()
            if (downloadDirectoryOk) {
                return fragmentMainView(context).apply {
                    scrollView.addView(VerticalLayout(context).apply { selectedView = this })
                    mainView = this
                }
            } else {
                return fragmentConfigureView(context)
            }
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askWritePermission()
    }

    override fun onStart() {
        super.onStart()
        context?.also {
            if (!downloadViewMode and selectedEntries.isEmpty()) selectEntries(it)
        }
    }

    override fun initStrings() {
        super.initStrings()
        noEntriesStr = getString(R.string.no_files_info)
        actionStr = getString(R.string.notification_download)
        notificationRunningStr = getString(R.string.notification_download)
        notificationCompleteStr = getString(R.string.notification_download_complete)
        notificationCanceledStr = getString(R.string.notification_download_canceled)
        notificationFailedStr = getString(R.string.notification_download_failed)
        noSharedFilesStr = getString(R.string.no_shared_files)
        notificationIconId = R.drawable.ic_download
        notificationDoneIconId = R.drawable.ic_download_done
    }
}
