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
import net.dcnnt.ui.*


class FileEntryView(context: Context,
                    val fragment: UploadFileFragment,
                    val entry: FileEntry): EntryView(context) {
    var thumbnailLoaded = false
    init {
        title = entry.name
        text = "${entry.size} bytes"
        progressView.progress = 0
        iconView.setImageResource(fileIconByPath(entry.name))
        actionView.apply {
            setImageResource(R.drawable.ic_cancel)
            setOnClickListener { onCancel() }
        }
        fragment.selectedEntriesView[entry.uri] = this
    }

    private fun onCancel() {
        synchronized(entry) {
            when (entry.status) {
                FileEntryStatus.WAIT -> {
                    this.visibility = View.GONE
                    entry.status = FileEntryStatus.CANCEL
                    if (!fragment.pluginRunning.get()) {
                        fragment.selectedList.remove(entry)
                    }
                }
                FileEntryStatus.RUN -> {
                    entry.status = FileEntryStatus.CANCEL
                }
                else -> {
                }
            }
            if (fragment.selectedList.size == 0) {
                fragment.updateFilesView(context)
            }
        }
    }

    fun loadThumbnail() {
        val bitmap: Bitmap
        try {
            bitmap = ThumbnailUtils.extractThumbnail(
                BitmapFactory.decodeByteArray(
                    context?.contentResolver?.openInputStream(entry.uri)?.readBytes() ?: return,
                    0, entry.size.toInt()
                ),
                iconView.width, iconView.height
            )
        } catch (e: Exception) {
            return
        }
        fragment.activity?.runOnUiThread {
            iconView.imageTintList = null
            iconView.setImageBitmap(bitmap)
        }
        thumbnailLoaded = true
    }
}


class UploadFileFragment(toolbarView: Toolbar,
                         private val intent: Intent? = null): BasePluginFargment(toolbarView) {
    override val TAG = "DC/UploadUI"
    private val READ_REQUEST_CODE = 42
    private lateinit var selectButton: Button
    private lateinit var uploadButton: Button
    private lateinit var cancelButton: Button
    val selectedList = mutableListOf<FileEntry>()
    val selectedEntriesView = mutableMapOf<Uri, FileEntryView>()
    val pluginRunning = AtomicBoolean(false)
    private lateinit var filesView: LinearLayout
    private var mainView: View? = null
    private lateinit var notification: ProgressNotification
    private lateinit var unitBytesStr: String
    private lateinit var statusCancelStr: String
    private lateinit var notificationUploadStr: String
    private lateinit var notificationUploadCompleteStr: String

    private fun selectFiles() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            type = "*/*"
        }, READ_REQUEST_CODE)
    }

    private fun uploadFiles(context: Context) {
        val device = selectedDevice ?: return
        if (selectedList.isEmpty()) return
        uploadButton.visibility = View.GONE
        selectButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        thread {
            pluginRunning.set(true)
            try {
                FileTransferPlugin(APP, device).apply {
                    init()
                    connect()
                    val waitingEntries = selectedList.filter { it.status == FileEntryStatus.WAIT }
                    notification.create(R.drawable.ic_upload,
                        notificationUploadStr,
                        "0/${waitingEntries.size}",
                        1000L * waitingEntries.size)
                    waitingEntries.forEachIndexed { index, it ->
                        synchronized(it) {
                            if (it.status != FileEntryStatus.WAIT) {
                                Log.d(TAG, "Skip ${it.uri} (${it.name})")
                                if (it.status == FileEntryStatus.CANCEL) {
                                    selectedEntriesView[it.uri]?.apply {
                                        text = "${it.size} $unitBytesStr - $statusCancelStr"
                                        actionView.setImageResource(R.drawable.ic_block)
                                    }
                                }
                                return@forEachIndexed
                            }
                            breakTransfer = false
                            it.status = FileEntryStatus.RUN
                        }
                        Log.d(TAG, "Upload ${it.uri} (${it.name})")
                        var progress = 0
                        var res: DCResult
                        activity?.runOnUiThread {
                            selectedEntriesView[it.uri]?.also { v -> v.text = "0/${it.size} $unitBytesStr" }
                        }
                        try {
                            res = uploadFile(it, context.contentResolver) { cur: Long, total: Long, _: Long ->
                                val progressCur = (1000 * cur / total).toInt()
                                breakTransfer = it.status == FileEntryStatus.CANCEL
                                activity?.runOnUiThread {
                                    selectedEntriesView[it.uri]?.also { v ->
                                        v.text = "$cur/$total $unitBytesStr"
                                        if (progressCur != progress) {
                                            progress = progressCur
                                            v.progressView.progress = progressCur
                                            notification.update("$index/${waitingEntries.size}",
                                            1000L * index + progressCur)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            res = DCResult(false, e.message ?: "$e")
                        }
                        it.status = FileEntryStatus.DONE
                        Log.d(TAG, "Upload done (${it.name}) - ${res.message}")
                        activity?.runOnUiThread {
                            selectedEntriesView[it.uri]?.apply {
                                text = "${it.size} $unitBytesStr - ${res.message}"
                                actionView.setImageResource(when(res.success) {
                                    true -> R.drawable.ic_done
                                    false -> R.drawable.ic_block
                                })
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                showError(context, e)
                Log.e(TAG, "$e")
            }
            pluginRunning.set(false)
            notification.complete(notificationUploadCompleteStr, "")
            activity?.runOnUiThread {
                cancelButton.visibility = View.GONE
                selectButton.visibility = View.VISIBLE
                uploadButton.visibility = View.GONE
            }
        }
    }

    fun cancelAllFiles() {
        selectedList.forEach {
            synchronized(it) {
                if ((it.status == FileEntryStatus.WAIT) or (it.status == FileEntryStatus.RUN)) {
                    it.status = FileEntryStatus.CANCEL
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        val cntx = context ?: return
        Log.d(TAG, "requestCode = $requestCode, resultCode = $resultCode, resultData = $resultData")
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            selectedList.clear()
            resultData?.data?.also { selectedList.add(getFileInfoFromUri(cntx, it) ?: return) }
            resultData?.clipData?.also {
                Log.d(TAG, "ClipData length = ${it.itemCount}")
                for (i in 0 until it.itemCount) {
                    Log.d(TAG, it.getItemAt(i).uri.toString())
                    selectedList.add(getFileInfoFromUri(cntx, it.getItemAt(i).uri) ?: continue)
                }
            }
            updateFilesView(cntx)
        }
    }

    fun loadThumbnails() {
        for (i in 0 .. 3) {
            selectedEntriesView.values.forEach {
                if (!it.thumbnailLoaded) it.loadThumbnail()
            }
            Thread.sleep(500)
        }
    }

    fun updateFilesView(context: Context) {
        filesView.removeAllViews()
        selectButton.visibility = View.VISIBLE
        cancelButton.visibility = View.GONE
        if (selectedList.size == 0) {
            uploadButton.isClickable = false
            uploadButton.visibility = View.GONE
            filesView.addView(TextBlockView(context, getString(R.string.no_files_selected)))
        } else {
            for (info in selectedList) filesView.addView(FileEntryView(context, this, info))
            thread { loadThumbnails() }
            uploadButton.isClickable = true
            uploadButton.visibility = View.VISIBLE
        }
    }

    fun fragmentMainView(context: Context) = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context))
        addView(LinearLayout(context).apply {
            val lp = LinearLayout.LayoutParams(LParam.W,  LParam.W, .5F)
            addView(Button(context).apply {
                text = context.getString(R.string.button_select_file)
                setOnClickListener { selectFiles() }
                LParam.set(this, lp)
                selectButton = this
            })
            addView(Button(context).apply {
                text = context.getString(R.string.button_upload)
                isClickable = false
                setOnClickListener { uploadFiles(context) }
                LParam.set(this, lp)
                uploadButton = this
            })
            addView(Button(context).apply {
                text = context.getString(R.string.button_cancel)
                setOnClickListener { cancelAllFiles() }
                LParam.set(this, lp)
                cancelButton = this
                visibility = View.GONE
            })
        })
        addView(ScrollView(context).apply {
            LParam.set(this, LParam.mm())
            addView(VerticalLayout(context).apply {
                filesView = this
                addView(TextBlockView(context, context.getString(R.string.no_files_selected)))
            })
        })
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
                selectedList.add(FileEntry(uriFake, "$title.txt", data.size.toLong(), data))
            } else {
                selectedList.add(getFileInfoFromUri(context, uri) ?: return)
            }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.let {
                it.forEach { selectedList.add(getFileInfoFromUri(context, (it as? Uri) ?: return) ?: return) }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        unitBytesStr = getString(R.string.unit_bytes)
        statusCancelStr = getString(R.string.status_cancel)
        notificationUploadStr = getString(R.string.notification_upload)
        notificationUploadCompleteStr = getString(R.string.notification_upload_complete)
        mainView?.also { return it }
        container?.context?.also { context ->
            notification = ProgressNotification(context)
            return fragmentMainView(context).apply {
                intent?.also { processIntent(context, it) }
                updateFilesView(context)
                mainView = this
            }
        }
        return null
    }
}
