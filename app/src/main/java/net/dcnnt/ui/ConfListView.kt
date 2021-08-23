package net.dcnnt.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.fragment.app.FragmentManager
import net.dcnnt.MainActivity
import net.dcnnt.core.*
import net.dcnnt.fragments.SettingsFragment

@SuppressLint("ViewConstructor")
open class ConfListView(context: Context, private val fragment: DCFragment) : VerticalLayout(context) {
    private val r = context.resources
    private val packageName = context.packageName
    private lateinit var conf: DCConf
    val confViews = mutableMapOf<String, ListTileView>()

    fun init(c: DCConf) {
        conf = c
        c.entries.forEach {
            createViewForEntry(it)?.also { v ->
                addView(v)
                confViews[it.name] = v
            }
        }
    }

    private fun createViewForEntry(entry: DCConfEntry<Any>): ListTileView? {
        val prefix = "conf_${conf.confName}_${entry.name}"
        val titleId = r.getIdentifier("${prefix}_title", "string", packageName)
        val t = if (titleId > 0) r.getString(titleId) else entry.name
        val infoId = r.getIdentifier("${prefix}_info", "string", packageName)
        val s = if (infoId > 0) r.getString(infoId) else ""
        return when (entry.type) {
            ConfTypes.BOOL -> createBoolInput((entry as? BoolEntry) ?: return null, t, s)
            ConfTypes.INT -> createIntInput((entry as? IntEntry) ?: return null, t, s)
            ConfTypes.STRING -> createStringInput((entry as? StringEntry) ?: return null, t, s)
            ConfTypes.SELECT -> createSelectInput((entry as? SelectEntry) ?: return null, t, s)
            ConfTypes.DIR -> createDirInput((entry as? DirEntry) ?: return null, t, s)
        }
    }

    private fun createBoolInput(entry: BoolEntry, label: String, info: String) = BoolInputView(context).apply {
        title = label
        text = info
        value = entry.value
        onInput = { value -> entry.updateValue(value) }
    }

    private fun createIntInput(entry: IntEntry, label: String, info: String) = TextInputView(context).apply {
        title = label
        text = entry.value.toString()
        isNumeric = true
        onInput = { v -> entry.updateValue(v.toIntOrNull()) }
    }

    private fun createStringInput(entry: StringEntry, label: String, info: String) = TextInputView(context).apply {
        title = label
        text = entry.value
        onInput = { v -> entry.updateValue(v) }
    }

    private fun createSelectInput(entry: SelectEntry, label: String, info: String): SelectInputView {
        return SelectInputView(context).apply {
            title = label
            text = entry.valueText(context)
            options = MutableList(entry.options.size) { i ->
                val opt = entry.options[i]
                Option(context.getString(opt.title), opt.value)
            }
            index = entry.valueIndex()
            onInput = { _, v -> entry.updateValue(v) }
        }
    }

    private fun createDirInput(entry: DirEntry, label: String, info: String) = TextInputView(context).apply {
        title = label
        text = entry.value
        onInput = { v -> entry.updateValue(v) }
        val requestCode = (fragment.activityResultHandlers.keys.maxOrNull() ?: 1000) + 1
        fragment.activityResultHandlers[requestCode] = { _, resultCode, intent ->
            var ret = true
            if (resultCode == Activity.RESULT_OK) {
                entry.updateValue(intent?.data.toString())
                text = Uri.decode(entry.value.split("/").last())
                ret = false
            }
            ret
        }
        setOnClickListener {
            val activity = fragment.activity as? MainActivity ?: return@setOnClickListener
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, entry.value)
                }
            }, requestCode)
        }
    }
}
