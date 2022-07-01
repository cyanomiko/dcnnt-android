package net.dcnnt.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.children
import net.dcnnt.MainActivity
import net.dcnnt.R
import net.dcnnt.core.ACTION_NOTIFICATION_LISTENER_SETTINGS
import net.dcnnt.core.APP
import net.dcnnt.core.nowString
import net.dcnnt.ui.*
import java.io.File


class LogDirectoryFragment: DCFragment() {
    val TAG = "DC/LogDirFrag"
    lateinit var containerView: ScrollView
    lateinit var listView: VerticalLayout
    var logView: TextView? = null

    fun getAllLogs(): List<Pair<Pair<Int, Int>, File>> {
        val res = mutableListOf<Pair<Pair<Int, Int>, File>>()
        File(APP.crashHandler.path).listFiles()?.forEach {
            res.add(Pair(when {
                it.path.endsWith(APP.crashHandler.SUFFIX) -> Pair(R.drawable.ic_warning, R.string.log_crash)
                it.path.endsWith(".work.log") -> Pair(R.drawable.ic_text, R.string.log_common)
                it.path.endsWith(".errors.log") -> Pair(R.drawable.ic_block, R.string.log_errors)
                else -> Pair(R.drawable.ic_file, R.string.log_other)
            }, it))
        }
        return res
    }

    fun deleteLog(context: Context, file: File) {
        AlertDialog.Builder(context).also {
            it.setTitle(context.getString(R.string.delete_log_title))
            it.setMessage(context.getString(R.string.delete_log_warning))
            it.setNegativeButton(context.getString(R.string.cancel)) { _, _ -> }
            it.setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                file.delete()
                updateFileList(context)
            }
        }.create().show()
    }

    fun initLogView(context: Context) = TextView(context).apply {
        layoutParams = LParam.ww()
    }

    fun showLog(context: Context, file: File) {
        containerView.removeAllViews()
        containerView.addView((logView ?: initLogView(context)).apply {
            text = file.readText()
            setTextIsSelectable(true)
            maxLines = 0xFFFFFF
        })
        containerView.post { containerView.fullScroll(View.FOCUS_DOWN) }
    }

    fun updateFileList(context: Context) {
        APP.dumpLogs()
        listView.removeAllViews()
        getAllLogs().forEach { l ->
            listView.addView(EntryView(context).apply {
                titleView.text = l.second.name
                textView.setText(l.first.second)
                progressView.visibility = View.GONE
                iconView.setImageResource(l.first.first)
                actionView.setImageResource(R.drawable.ic_cancel)
                actionView.setOnClickListener { deleteLog(context, l.second) }
                setOnClickListener { showLog(context, l.second) }
            })
        }
    }

    fun fragmentMainView(context: Context) = ScrollView(context).apply {
        containerView = this
        addView(VerticalLayout(context).apply {
            listView = this
            updateFileList(context)
        })
    }

    override fun onBackPressed(): Boolean {
        if ((containerView.children.firstOrNull() as? VerticalLayout) != null) return true
        containerView.removeAllViews()
        containerView.addView(listView)
        return false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        container?.context?.also { return fragmentMainView(it) }
        return null
    }

}
