package net.dcnnt.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import net.dcnnt.MainActivity
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.plugins.OpenerPlugin
import net.dcnnt.ui.ProgressNotification
import net.dcnnt.ui.TextInputView
import net.dcnnt.ui.VerticalLayout
import kotlin.concurrent.thread


class OpenerFragment: UploadFileFragment() {
    override val TAG = "DC/OpenerUI"
    private val READ_REQUEST_CODE = 43
    private var intent: Intent? = null

    companion object {
        private const val ARG_INTENT = "intent"
        private const val ARG_TITLE = "title"
        private const val ARG_ENTRY_TYPE = "entryType"
        fun newInstance(intent: Intent? = null) = OpenerFragment().apply {
            arguments = bundleOf(ARG_INTENT to intent)
        }
    }

    override fun prepareToolbar(toolbarView: Toolbar) {
        toolbarView.menu.also { menu ->
            menu.clear()
            menu.add(R.string.open_link).setOnMenuItemClickListener { onSelectLink() }
        }
    }

    fun onSelectLink(): Boolean {
        val context = context ?: return true
        TextInputView.inputDialog(context, "Input URL", "", false) {
            val uri = Uri.parse(it)
            Log.d(TAG, "URI = $uri, scheme = '${uri.scheme}'")
            if ((uri.scheme != "http") and (uri.scheme != "https")) return@inputDialog
            selectedEntries.clear()
            selectedEntries.add(FileEntry("Link", it.length.toLong(),
                localUri = uri, entryType = EntryType.LINK))
            updateEntriesView(context)
        }
        return true
    }

    override fun selectEntries(context: Context) {
        activity?.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, READ_REQUEST_CODE)
    }

    override fun processAllEntries(context: Context) {
        openEntries(context)
    }

    override fun processFailedEntries(context: Context) {
        synchronized(selectedEntries) {
            selectedEntries.removeAll { it.status == FileStatus.DONE }
            selectedEntries.forEach { it.status = FileStatus.WAIT }
        }
        updateEntriesView(context)
        openEntries(context)
    }

    private fun openEntries(context: Context) {
        // ToDo: Unify together with method from UploadFileFragment...
        val device = selectedDevice ?: return
        if (selectedEntries.isEmpty()) return
        actionButton.visibility = View.GONE
        selectButton.visibility = View.GONE
        repeatButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        thread {
            pluginRunning.set(true)
            try {
                OpenerPlugin(APP, device).apply {
                    init(context)
                    connect()
                    val waitingEntries = selectedEntries.filter { it.status == FileStatus.WAIT }
                    var totalSize = (LongArray(waitingEntries.size) { waitingEntries[it].size }).sum()
                    var totalDoneSize = 0L
                    var totalDoneSizePre = 0L
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
                        if (it.entryType == EntryType.FILE) {
                            activity?.runOnUiThread {
                                selectedEntriesView[it.idStr]?.also { v ->
                                    v.text = "0/${it.size} $unitBytesStr"
                                }
                            }
                            try {
                                res = openFile(it, context.contentResolver) { cur: Long, total: Long, _: Long ->
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
                                                notifyDownloadProgress(
                                                    waitingEntries,
                                                    index + 1,
                                                    it,
                                                    totalSize,
                                                    totalDoneSize,
                                                    currentDoneSize
                                                )
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                res = DCResult(false, e.message ?: "$e")
                            }
                        } else {
                            try {
                                res = openLink(it.localUri.toString())
                                selectedEntriesView[it.idStr]?.also { v ->
                                    v.progressView.progress = 1000
                                }
                            } catch (e: Exception) {
                                res = DCResult(false, e.message ?: "$e")
                            }
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

    override fun processIntent(context: Context, intent: Intent) {
        // ToDo: Do not repeat code from UploadFileFragment and MainActivity
        if (intent.action != Intent.ACTION_SEND) return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        text?.also {
            if (it.startsWith("http://") or it.startsWith("https://")) {
                selectedEntries.add(FileEntry(it, it.length.toLong(),
                    localUri = Uri.parse(it), entryType = EntryType.LINK))
                return
            }
        }
        val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
        uri?.also {
            selectedEntries.add(getFileInfoFromUri(context, it) ?: return)
            return
        }
        val data = (text ?: return).toByteArray()
        val titleStr = simplifyFilename(title ?: subject ?: text ?: getString(R.string.file))
        val uriFake = Uri.fromParts("data", "", "")
        selectedEntries.add(FileEntry( "$titleStr.txt", data.size.toLong(),
            localUri = uriFake, data = data))
    }

    override fun onActivityResult(mainActivity: MainActivity, requestCode: Int,
                                  resultCode: Int, data: Intent?): Boolean {
        Log.d(TAG, "requestCode = $requestCode, resultCode = $resultCode, resultData = $data")
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            handleSelectEntries(context ?: return true, data)
        }
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent = arguments?.getParcelable(ARG_INTENT)
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

    override fun initStrings() {
        super.initStrings()
        noEntriesStr = getString(R.string.no_entries_selected)
        actionStr = getString(R.string.notification_open)
        notificationRunningStr = getString(R.string.notification_upload)
        notificationCompleteStr = getString(R.string.notification_upload_complete)
        notificationCanceledStr = getString(R.string.notification_upload_canceled)
        notificationFailedStr = getString(R.string.notification_upload_failed)
    }
}
