package net.dcnnt.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
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
import androidx.core.graphics.drawable.toBitmap
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.plugins.FileTransferPlugin
import net.dcnnt.ui.*
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock


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

class DownloadingFileView(context: Context,
                          private val fragment: DownloadFileFragment,
                          private val remoteEntry: FileEntry): EntryView(context) {
    val TAG = "DC/DownloadingFileView"
    val notification = ProgressNotification(context)
    init {
        title = remoteEntry.name
        text = "${remoteEntry.size} ${context.getString(R.string.unit_bytes)}"
        progressView.progress = 0
        iconView.setImageResource(fileIconByPath(remoteEntry.name))
        actionView.setImageResource(R.drawable.ic_cancel)
        actionView.setOnClickListener { onActionViewClicked() }
        fragment.selectedEntriesView[remoteEntry.remoteIndex as Long] = this
    }

    private fun onActionViewClicked() {
        synchronized(remoteEntry) {
            when (remoteEntry.status) {
                FileStatus.WAIT -> {
                    this.visibility = View.GONE
                    remoteEntry.status = FileStatus.CANCEL
                    if (!fragment.pluginRunning.get()) {
                        fragment.selectedEntries.remove(remoteEntry)
                    }
                }
                FileStatus.RUN -> { remoteEntry.status = FileStatus.CANCEL }
                else -> {}
            }
        }
    }

    /**
     * Get thumbnail for downloaded file
     * @return bitmap or null
     */
    fun getThumbnail(): Bitmap? {
        try {
            remoteEntry.data?.also { ThumbnailUtils.extractThumbnail(
                BitmapFactory.decodeByteArray(it, 0, it.size), iconView.width, iconView.height)?.also { b ->
                    return b
                }
            }
            iconView.drawable.toBitmap(iconView.width, iconView.height)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "$e")
        }
        return null
    }

    /**
     * Update onClick listeners and set image thumbnail if available after successful download
     */
    private fun updateSuccess(bitmap: Bitmap?) {
        actionView.setImageResource(R.drawable.ic_share)
        actionView.setOnClickListener { openOrShare(false) }
        leftView.setOnClickListener { openOrShare(true) }
        bitmap?.also {
            iconView.setImageBitmap(it)
            iconView.imageTintList = null
        }
    }

    fun createFileIntent(isOpen: Boolean = true): Intent? {
        val file = remoteEntry.localFile ?: return null
        val uri = FileProvider.getUriForFile(context.applicationContext, "net.dcnnt", file)
        val mime = mimeTypeByPath(uri.toString())
        val action = if (isOpen) Intent.ACTION_VIEW else Intent.ACTION_SEND
        val intent = Intent(action)
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (isOpen) {
            intent.setDataAndNormalize(uri)
        } else {
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.type = mime
        }
        return intent
    }

    /**
     * Update view, notifications and listeners on end of download
     */
    fun updateOnEnd(res: DCResult, unitBytesStr: String, doNotificationStuff: Boolean,
                    notificationDownloadCanceledStr: String, notificationDownloadCompleteStr: String,
                    notificationDownloadFailedStr: String, waitingCount: Int, currentNum: Int) {
        val icon = getThumbnail()
        text = "${remoteEntry.size} $unitBytesStr - ${res.message}"
        if (res.success) {
            updateSuccess(icon)
        } else {
            actionView.setImageResource(R.drawable.ic_block)
        }
        if (!doNotificationStuff) return
        if (remoteEntry.status == FileStatus.CANCEL) {
            notification.complete(notificationDownloadCanceledStr, "$currentNum/$waitingCount - ${remoteEntry.name}")
        } else {
            if (res.success) {
                val intent = createFileIntent(true)
                notification.complete(notificationDownloadCompleteStr, "$currentNum/$waitingCount - ${remoteEntry.name}", icon, intent)
            } else {
                notification.complete(notificationDownloadFailedStr, "$currentNum/$waitingCount - ${remoteEntry.name} : ${res.message}", icon)
            }
        }
    }

    /**
     * Handle click on share icon or any other place in view
     */
    private fun openOrShare(isOpen: Boolean = true) {
        val intent = createFileIntent(isOpen) ?: return
        val actionName = context.getString(if (isOpen) R.string.action_open else R.string.action_share)
        context.startActivity(Intent.createChooser(intent, "$actionName ${remoteEntry.name}"))
    }
}


class DownloadFileFragment: BasePluginFargment() {
    override val TAG = "DC/DownloadUI"
    val selectedEntries = mutableListOf<FileEntry>()
    val selectedEntriesView = mutableMapOf<Long, DownloadingFileView>()
    private val WRITE_EXTERNAL_STORAGE_CODE = 1
    private lateinit var selectButton: Button
    private lateinit var downloadButton: Button
    private lateinit var cancelButton: Button
    private var hasWriteFilePermission = false
    private var remotePath = mutableListOf<FileEntry>()
    private var remoteRoot: FileEntry = FileEntry("root", 0, remoteChildren = listOf())
    val pluginRunning = AtomicBoolean(false)
    private lateinit var filesView: VerticalLayout
    private var downloadViewMode = false
    private var mainView: View? = null
    private var sortBy: FileSort = FileSort.NAME
    private var downloadNotificationPolicy = APP.conf.downloadNotificationPolicy.value
    private lateinit var notification: ProgressNotification
    private lateinit var noSharedFilesStr: String
    private lateinit var unitBytesStr: String
    private lateinit var statusCancelStr: String
    private lateinit var notificationDownloadStr: String
    private lateinit var notificationDownloadCompleteStr: String
    private lateinit var notificationDownloadCanceledStr: String
    private lateinit var notificationDownloadFailedStr: String

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

    private fun updateFilesView(context: Context) {
        val rootChildren = remoteRoot.remoteChildren ?: return showNoSharedFilesMessage(context)
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

    private fun showRemoteDir(context: Context, entry: FileEntry) {
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
                FileEntry("..", it.size, FileStatus.WAIT, remoteChildren = listOf()), true))
        }
        when (sortBy) {
            FileSort.NAME -> entry.remoteChildren?.sortedBy { "${!it.isDir} ${it.name.toUpperCase(Locale.getDefault())}" }
            FileSort.TYPE -> entry.remoteChildren?.sortedBy {
                val locale = Locale.getDefault()
                val extensionUpper = it.name.split('.').lastOrNull()?.toUpperCase(locale) ?: ""
                val nameUpper = it.name.toUpperCase(locale)
                "${!it.isDir} $extensionUpper $nameUpper"
            }
            FileSort.SIZE -> entry.remoteChildren?.sortedBy { if (it.isDir) it.size else (0xFFFFFFFF + it.size) }
        }?.forEach { filesView.addView(RemoteEntryView(context, this, it)) }
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
                if ((it.status == FileStatus.WAIT) or (it.status == FileStatus.RUN)) {
                    it.status = FileStatus.CANCEL
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

    private fun enableDownloadView(context: Context) {
        synchronized(downloadViewMode) {
            filesView.removeAllViews()
            selectedEntries.forEach { if (!it.isDir) filesView.addView(DownloadingFileView(context, this, it)) }
            downloadViewMode = true
        }
    }

    private fun notifyDownloadStart(waiting: List<FileEntry>, currentNum: Int, current: FileEntry,
                                    totalSize: Long, totalDoneSize: Long, currentDoneSize: Long) {
        val policy = APP.conf.downloadNotificationPolicy.value
        val currentName = current.name
        val n: ProgressNotification = when (policy) {
            "one" -> notification
            "all" -> selectedEntriesView[current.remoteIndex]?.notification ?: return
            else -> return
        }
        if ((n.isNew and (policy == "one")) or (policy == "all")) {
            n.create(R.drawable.ic_download, notificationDownloadStr,
            "0/${current.size} - $currentName", 1000)
        } else if (policy == "one") {
            val progress = if (totalSize > 0) (1000L * totalDoneSize) / totalSize else 1000L
            n.update("$currentNum/${waiting.size} - $currentName", progress, true)
        }
    }

    private fun notifyDownloadProgress(waiting: List<FileEntry>, currentNum: Int, current: FileEntry,
                                       totalSize: Long, totalDoneSize: Long, currentDoneSize: Long) {
        val policy = APP.conf.downloadNotificationPolicy.value
        val currentName = current.name
        val n: ProgressNotification = when (policy) {
            "one" -> notification
            "all" -> selectedEntriesView[current.remoteIndex]?.notification ?: return
            else -> return
        }
        if (policy == "all") {
            val progress = if (current.size > 0) (1000L * currentDoneSize) / current.size else 1000L
            n.update("$currentNum/${waiting.size} - $currentName", progress)
        } else if (policy == "one") {
            val progress = if (totalSize > 0) (1000L * totalDoneSize) / totalSize else 1000L
            n.update("$currentNum/${waiting.size} - $currentName", progress)
        }
    }

    private fun notifyDownloadEnd(waiting: List<FileEntry>, currentNum: Int, current: FileEntry,
                                  totalSize: Long, totalDoneSize: Long, result: DCResult) {
        val policy = APP.conf.downloadNotificationPolicy.value
        val currentName = current.name
        selectedEntriesView[current.remoteIndex]?.also { v ->
            activity?.runOnUiThread {
                v.updateOnEnd(
                    result,
                    unitBytesStr,
                    policy == "all",
                    notificationDownloadCanceledStr,
                    notificationDownloadCompleteStr,
                    notificationDownloadFailedStr,
                    waiting.size,
                    currentNum
                )
            }
        }
        if (policy == "one") {
            if (currentNum == (waiting.size - 1)) {
                if (selectedEntries.all { it.status == FileStatus.CANCEL }) {
                    notification.complete(notificationDownloadCanceledStr,"${waiting.size}/${waiting.size}")
                } else {
                    notification.complete(notificationDownloadCompleteStr, "${waiting.size}/${waiting.size}")
                }
            } else {
                val progress = if (totalSize > 0) (1000L * totalDoneSize) / totalSize else 1000L
                notification.update("$currentNum/${waiting.size} - $currentName", progress, true)
            }
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
        val waitingEntries = selectedEntries.filter { (it.status == FileStatus.WAIT) and !it.isDir }
        var totalSize = (LongArray(waitingEntries.size) { waitingEntries[it].size }).sum()
        var totalDoneSize = 0L
        var totalDoneSizePre = 0L
        downloadNotificationPolicy = APP.conf.downloadNotificationPolicy.value
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
                                    selectedEntriesView[it.remoteIndex]?.apply {
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
                                    selectedEntriesView[it.remoteIndex]?.also { v ->
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
                        if (res.success) {
                            (res.data as? File)?.also { file ->
                                it.localFile = file
                                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                    Uri.fromFile(file)))
                            }
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
                throw e
            }
            activity?.runOnUiThread {
                cancelButton.visibility = View.GONE
                selectButton.visibility = View.VISIBLE
                downloadButton.visibility = View.GONE
            }
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
        notificationDownloadCanceledStr = getString(R.string.notification_download_canceled)
        notificationDownloadFailedStr = getString(R.string.notification_download_failed)
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
