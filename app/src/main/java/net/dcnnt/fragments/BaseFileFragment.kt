package net.dcnnt.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.ui.*
import java.util.concurrent.atomic.AtomicBoolean


class RunningFileView(context: Context,
                      private val fragment: BaseFileFragment,
                      private val entry: FileEntry,
                      notification: ProgressNotification? = null): EntryView(context) {
    val TAG = "DC/RunningFileView"
    val notificationIsPrivate = notification != null
    val notification = notification ?: ProgressNotification(context)
    var thumbnailLoaded = false
    private val THUMBNAIL_SIZE_THRESHOLD = 10 * 1024 * 1024

    init {
        title = entry.name
        text = when (entry.entryType) {
            EntryType.FILE -> "${entry.size} ${context.getString(R.string.unit_bytes)}"
            EntryType.LINK -> "${entry.localUri}"
        }
        progressView.progress = 0
        val iconId: Int = when (entry.entryType) {
            EntryType.FILE -> fileIconByPath(entry.name)
            EntryType.LINK -> R.drawable.ic_http
        }
        iconView.setImageResource(iconId)
        actionView.setImageResource(R.drawable.ic_cancel)
        actionView.setOnClickListener { onActionViewClicked() }
        fragment.selectedEntriesView[entry.idStr] = this
    }

    /**
     * Helper function to create file intent with OPEN or SHARE action
     */
    private fun createFileIntent(isOpen: Boolean = true): Intent? {
        val uri = entry.localUri ?: return null
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
     * Handle click on share icon or any other place in view
     */
    private fun openOrShare(isOpen: Boolean = true) {
        val intent = createFileIntent(isOpen) ?: return
        val actionName = context.getString(if (isOpen) R.string.action_open else R.string.action_share)
        context.startActivity(Intent.createChooser(intent, "$actionName ${entry.name}"))
    }

    private fun onActionViewClicked() {
        synchronized(entry) {
            when (entry.status) {
                FileStatus.WAIT -> {
                    entry.status = FileStatus.CANCEL
                    fragment.activity?.runOnUiThread {
                        this.visibility = View.GONE
                        if (!fragment.pluginRunning.get()) {
                            fragment.selectedEntries.remove(entry)
                        }
                    }
                }
                FileStatus.RUN -> {
                    entry.status = FileStatus.CANCEL
                    fragment.activity?.runOnUiThread {
                        actionView.setImageResource(R.drawable.ic_block)
                    }
                }
                FileStatus.DONE -> {/* Set it up in other place */}
                else -> {}
            }
        }
    }

    /**
     * Load bitmap thumbnail for file
     * @return bitmap or null
     */
    fun loadThumbnail(): Bitmap? {
        if (entry.entryType != EntryType.FILE) return null
        if (entry.size > THUMBNAIL_SIZE_THRESHOLD) return null
        if ((entry.data != null) and (entry.localUri.toString().startsWith("data"))) return null
        val uri = entry.localUri ?: return null
        try {
            val data = context?.contentResolver?.openInputStream(uri)?.readBytes() ?: return null
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
            return ThumbnailUtils.extractThumbnail(bitmap, iconView.width, iconView.height)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "$e")
        }
        return null
    }

    /**
     * Load thumbnail and show it in left image view
     */
    fun updateThumbnail(): Bitmap? {
        loadThumbnail()?.also {
            fragment.activity?.runOnUiThread {
                iconView.imageTintList = null
                iconView.setImageBitmap(it)
            }
            thumbnailLoaded = true
            return it
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

    /**
     * Update view, notifications and listeners on end of download
     */
    fun updateOnEnd(res: DCResult, unitBytesStr: String, doNotificationStuff: Boolean,
                    notificationDownloadCanceledStr: String, notificationDownloadCompleteStr: String,
                    notificationDownloadFailedStr: String, waitingCount: Int, currentNum: Int,
                    notificationDoneIconId: Int?) {
        val icon = loadThumbnail()
        if (entry.entryType == EntryType.FILE) text = "${entry.size} $unitBytesStr - ${res.message}"
        if (res.success) {
            updateSuccess(icon)
        } else {
            actionView.setImageResource(R.drawable.ic_block)
        }
        if (!doNotificationStuff) return
        if (entry.status == FileStatus.CANCEL) {
            notification.complete(notificationDownloadCanceledStr, "$currentNum/$waitingCount - ${entry.name}")
        } else {
            if (res.success) {
                val intent = createFileIntent(true)
                notification.smallIconId = notificationDoneIconId
                notification.complete(notificationDownloadCompleteStr, "$currentNum/$waitingCount - ${entry.name}", icon, intent)
            } else {
                notification.complete(notificationDownloadFailedStr, "$currentNum/$waitingCount - ${entry.name} : ${res.message}", icon)
            }
        }
    }
}


open class BaseFileFragment: BasePluginFargment() {
    override val TAG = "DC/FileUI"
    val selectedEntries = mutableListOf<FileEntry>()
    val selectedEntriesView = mutableMapOf<String, RunningFileView>()
    val pluginRunning = AtomicBoolean(false)
    val isPluginRunning = AtomicBoolean(false)
    var hasWriteFilePermission = false
    lateinit var selectButton: Button
    lateinit var actionButton: Button
    lateinit var cancelButton: Button
    lateinit var repeatButton: Button
    lateinit var selectedView: VerticalLayout
    private var downloadViewMode = false
    protected var mainView: View? = null
    lateinit var scrollView: ScrollView
    protected lateinit var notification: ProgressNotification
    lateinit var actionStr: String
    lateinit var noEntriesStr: String
    lateinit var unitBytesStr: String
    lateinit var statusCancelStr: String
    lateinit var notificationRunningStr: String
    lateinit var notificationCompleteStr: String
    lateinit var notificationCanceledStr: String
    lateinit var notificationFailedStr: String
    var notificationIconId: Int? = null
    var notificationDoneIconId: Int? = null
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { hasWriteFilePermission = it }


    protected fun askWritePermission() {
        if (ContextCompat.checkSelfPermission(mainActivity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "ask permission")
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            Log.d(TAG, "already granted")
            hasWriteFilePermission = true
        }
    }

    fun setButtonsVisibilityOnEnd() {
        val hideRepeatButton = selectedEntries.all { it.status == FileStatus.DONE }
        activity?.runOnUiThread {
            cancelButton.visibility = View.GONE
            selectButton.visibility = View.VISIBLE
            actionButton.visibility = View.GONE
            repeatButton.visibility = if (hideRepeatButton) View.GONE else View.VISIBLE
        }
    }

    open fun onSelectedDeviceChanged(context: Context) {}

    open fun selectEntries(context: Context) {}

    open fun processAllEntries(context: Context) {}

    open fun processFailedEntries(context: Context) {}

    private fun cancelAllEntries() {
        selectedEntries.forEach {
            synchronized(it) {
                if ((it.status == FileStatus.WAIT) or (it.status == FileStatus.RUN)) {
                    it.status = FileStatus.CANCEL
                }
            }
        }
    }

    fun fragmentMainView(context: Context) = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context).apply {
            onUpdateOptons = { _, changed, _ -> if (changed) onSelectedDeviceChanged(context) }
        })
        addView(LinearLayout(context).apply {
            val lp = LinearLayout.LayoutParams(LParam.W, LParam.W, .5F)
            addView(Button(context).apply {
                selectButton = this
                text = context.getString(R.string.button_select_file)
                setOnClickListener { selectEntries(context) }
                layoutParams = lp
            })
            addView(Button(context).apply {
                actionButton = this
                text = actionStr
                setOnClickListener { processAllEntries(context) }
                layoutParams = lp
            })
            addView(Button(context).apply {
                cancelButton = this
                text = context.getString(R.string.button_cancel)
                visibility = View.GONE
                setOnClickListener { cancelAllEntries() }
                layoutParams = lp
            })
            addView(Button(context).apply {
                repeatButton = this
                text = context.getString(R.string.retry)
                visibility = View.GONE
                setOnClickListener { processFailedEntries(context) }
                layoutParams = lp
            })
        })
        addView(ScrollView(context).apply {
            LParam.set(this, LParam.mm())
            scrollView = this
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initStrings()
        askWritePermission()
    }

    open fun initStrings() {
        unitBytesStr = getString(R.string.unit_bytes)
        statusCancelStr = getString(R.string.status_cancel)
    }

    protected open fun getPolicy(): String = "all"

    protected fun notifyDownloadStart(waiting: List<FileEntry>, currentNum: Int, current: FileEntry,
                                    totalSize: Long, totalDoneSize: Long, currentDoneSize: Long) {
        val policy = getPolicy()
        val currentName = current.name
        val n: ProgressNotification = when (policy) {
            "one" -> notification
            "all" -> selectedEntriesView[current.idStr]?.notification ?: return
            else -> return
        }
        val iconId = notificationIconId ?: R.drawable.ic_wait
        if ((n.isNew and (policy == "one")) or (policy == "all")) {
            n.create(iconId, notificationRunningStr, "0/${current.size} - $currentName", 1000)
        } else if (policy == "one") {
            val progress = if (totalSize > 0) (1000L * totalDoneSize) / totalSize else 1000L
            n.smallIconId = iconId
            n.update("$currentNum/${waiting.size} - $currentName", progress, true)
        }
    }

    protected fun notifyDownloadProgress(waiting: List<FileEntry>, currentNum: Int, current: FileEntry,
                                       totalSize: Long, totalDoneSize: Long, currentDoneSize: Long) {
        val policy = getPolicy()
        val currentName = current.name
        val n: ProgressNotification = when (policy) {
            "one" -> notification
            "all" -> selectedEntriesView[current.idStr]?.notification ?: return
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

    protected fun notifyDownloadEnd(waiting: List<FileEntry>, currentNum: Int, current: FileEntry,
                                  totalSize: Long, totalDoneSize: Long, result: DCResult) {
        val policy = getPolicy()
        val currentName = current.name
        selectedEntriesView[current.idStr]?.also { v ->
            activity?.runOnUiThread {
                v.updateOnEnd(
                    result,
                    unitBytesStr,
                    policy == "all",
                    notificationCanceledStr,
                    notificationCompleteStr,
                    notificationFailedStr,
                    waiting.size,
                    currentNum,
                    notificationDoneIconId
                )
            }
        }
        if (policy == "one") {
            if (currentNum == waiting.size) {
                if (selectedEntries.all { it.status == FileStatus.CANCEL }) {
                    notification.complete(notificationCanceledStr,"${waiting.size}/${waiting.size}")
                } else {
                    notification.smallIconId = notificationDoneIconId
                    notification.complete(notificationCompleteStr, "${waiting.size}/${waiting.size}")
                }
            } else {
                val progress = if (totalSize > 0) (1000L * totalDoneSize) / totalSize else 1000L
                notification.update("$currentNum/${waiting.size} - $currentName", progress, true)
            }
        }
    }

}
