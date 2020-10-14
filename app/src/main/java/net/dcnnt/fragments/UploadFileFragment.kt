package net.dcnnt.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.plugins.FileTransferPlugin
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Parcelable
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import net.dcnnt.MainActivity
import net.dcnnt.ui.*


class UploadFileFragment: BaseFileFragment() {
    override val TAG = "DC/UploadUI"
    private val READ_REQUEST_CODE = 42
    private var intent: Intent? = null

    companion object {
        private const val ARG_INTENT = "intent"
        fun newInstance(intent: Intent? = null) = UploadFileFragment().apply {
            arguments = bundleOf(ARG_INTENT to intent)
        }
    }

    override fun selectEntries(context: Context) {
        activity?.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            type = "*/*"
        }, READ_REQUEST_CODE)
    }

    fun loadThumbnails() {
        for (i in 0 .. 3) {
            selectedEntriesView.values.forEach {
                if (!it.thumbnailLoaded) it.updateThumbnail()
            }
            Thread.sleep(500)
        }
    }

    fun updateEntriesView(context: Context) {
        selectedView.removeAllViews()
        selectButton.visibility = View.VISIBLE
        cancelButton.visibility = View.GONE
        repeatButton.visibility = View.GONE
        if (selectedEntries.size == 0) {
            actionButton.visibility = View.GONE
            selectedView.addView(TextBlockView(context, noEntriesStr))
        } else {
            for (entry in selectedEntries) selectedView.addView(RunningFileView(context, this, entry))
            thread { loadThumbnails() }
            actionButton.isClickable = true
            actionButton.visibility = View.VISIBLE
        }
    }


    private fun handleSelectEntries(context: Context, resultData: Intent?) {
        selectedEntries.clear()
        resultData?.data?.also { selectedEntries.add(getFileInfoFromUri(context, it) ?: return) }
        resultData?.clipData?.also {
            Log.d(TAG, "ClipData length = ${it.itemCount}")
            for (i in 0 until it.itemCount) {
                Log.d(TAG, it.getItemAt(i).uri.toString())
                selectedEntries.add(getFileInfoFromUri(context, it.getItemAt(i).uri) ?: continue)
            }
        }
        updateEntriesView(context)
    }

    override fun processAllEntries(context: Context) {
        uploadFiles(context)
    }

    override fun processFailedEntries(context: Context) {
        synchronized(selectedEntries) {
            selectedEntries.removeAll { it.status == FileStatus.DONE }
            selectedEntries.forEach { it.status = FileStatus.WAIT }
        }
        updateEntriesView(context)
        uploadFiles(context)
    }

    override fun getPolicy(): String = APP.conf.uploadNotificationPolicy.value

    private fun uploadFiles(context: Context) {
        val device = selectedDevice ?: return
        if (selectedEntries.isEmpty()) return
        actionButton.visibility = View.GONE
        selectButton.visibility = View.GONE
        repeatButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        thread {
            pluginRunning.set(true)
            try {
                FileTransferPlugin(APP, device).apply {
                    init(context)
                    connect()
                    val waitingEntries = selectedEntries.filter { it.status == FileStatus.WAIT }
                    var totalSize = (LongArray(waitingEntries.size) { waitingEntries[it].size }).sum()
                    var totalDoneSize = 0L
                    var totalDoneSizePre = 0L
//                    notification.create(R.drawable.ic_upload,
//                        notificationRunningStr,
//                        "0/${waitingEntries.size}",
//                        1000L * waitingEntries.size)
                    waitingEntries.forEachIndexed { index, it ->
                        var currentDoneSize = 0L
                        synchronized(it) {
                            if (it.status != FileStatus.WAIT) {
                                Log.d(TAG, "Skip ${it.localUri} (${it.name})")
                                if (it.status == FileStatus.CANCEL) {
                                    activity?.runOnUiThread {
                                        selectedEntriesView[it.idStr]?.apply {
                                            text = "${it.size} $unitBytesStr - $statusCancelStr"
                                            actionView.setImageResource(R.drawable.ic_block)
                                        }
                                    }
                                }
                                return@forEachIndexed
                            }
                            breakTransfer = false
                            it.status = FileStatus.RUN
                        }
                        Log.d(TAG, "Upload ${it.localUri} (${it.name})")
                        var progress = 0
                        var res: DCResult
                        notifyDownloadStart(waitingEntries, index + 1, it, totalSize, totalDoneSize, currentDoneSize)
                        activity?.runOnUiThread {
                            selectedEntriesView[it.idStr]?.also { v -> v.text = "0/${it.size} $unitBytesStr" }
                        }
                        try {
                            res = uploadFile(it, context.contentResolver) { cur: Long, total: Long, _: Long ->
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
//                                            notification.update("$index/${waitingEntries.size}",
//                                            1000L * index + progressCur)
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
//                        Log.d(TAG, "Upload done (${it.name}) - ${res.message}")
//                        activity?.runOnUiThread {
//                            selectedEntriesView[it.idStr]?.apply {
//                                text = "${it.size} $unitBytesStr - ${res.message}"
//                                actionView.setImageResource(when(res.success) {
//                                    true -> R.drawable.ic_done
//                                    false -> R.drawable.ic_block
//                                })
//                            }
//                        }
                    }
                }
            } catch (e: Exception) {
                showError(context, e)
                Log.e(TAG, "$e")
            }
            //notification.complete(notificationCompleteStr, "")
            setButtonsVisibilityOnEnd()
            pluginRunning.set(false)
        }
    }

    override fun onActivityResult(mainActivity: MainActivity, requestCode: Int,
                                  resultCode: Int, data: Intent?): Boolean {
        Log.d(TAG, "requestCode = $requestCode, resultCode = $resultCode, resultData = $data")
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            handleSelectEntries(context ?: return true, data)
        }
        return true
    }

    fun processIntent(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            if (uri == null) {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val title = (intent.getStringExtra(Intent.EXTRA_TITLE)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: text)
                    .take(21).trim(' ', '-', '.').replace(' ', '_')
                    .filter { c -> c.isLetterOrDigit() or (c == '_') }
                val data = text.toByteArray()
                val uriFake = Uri.fromParts("data", "", "")
                selectedEntries.add(FileEntry( "$title.txt", data.size.toLong(), localUri = uriFake, data = data))
            } else {
                selectedEntries.add(getFileInfoFromUri(context, uri) ?: return)
            }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.let {
                it.forEach { selectedEntries.add(getFileInfoFromUri(context, (it as? Uri) ?: return) ?: return) }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        mainView?.also { return it }
        container?.context?.also { context ->
            notification = ProgressNotification(context)
            return fragmentMainView(context).apply {
                scrollView.addView(VerticalLayout(context).apply { selectedView = this })
                mainView = this
                intent?.also { processIntent(context, it) }
                updateEntriesView(context)
            }
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent = arguments?.getParcelable(ARG_INTENT)
    }

    override fun initStrings() {
        super.initStrings()
        noEntriesStr = getString(R.string.no_files_selected)
        actionStr = getString(R.string.notification_upload)
        notificationRunningStr = getString(R.string.notification_upload)
        notificationCompleteStr = getString(R.string.notification_upload_complete)
        notificationCanceledStr = getString(R.string.notification_upload_canceled)
        notificationFailedStr = getString(R.string.notification_upload_failed)
    }

    override fun onStart() {
        super.onStart()
        if (APP.conf.autoSearch.value and !APP.dm.searchDone) {
            deviceSelectView?.refreshDevices()
        }
    }
}
