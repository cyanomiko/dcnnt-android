package net.dcnnt.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.FragmentManager
import net.dcnnt.MainActivity
import net.dcnnt.core.*
import net.dcnnt.fragments.SettingsFragment

@SuppressLint("ViewConstructor")
open class ConfListView(context: Context, private val fragment: DCFragment) : VerticalLayout(context) {
    private val r = context.resources
    private val packageName = context.packageName
    private val activityLaunchers = mutableMapOf<Int, ActivityResultLauncher<Intent>>()
    private lateinit var conf: DCConf
    val confViews = mutableMapOf<String, ListTileView>()
    var alternativeConfNames = listOf<String>()

    fun init(c: DCConf) {
        conf = c
        c.entries.forEach {
            createViewForEntry(it)?.also { v ->
                addView(v)
                confViews[it.name] = v
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun extractResourceString(entryName: String, stringType: String): String {
        val confNames = mutableListOf(conf.confName)
        confNames.addAll(alternativeConfNames)
        for (confName in confNames) {
            val prefix = "conf_${confName}_${entryName}"
            val id = r.getIdentifier("${prefix}_${stringType}", "string", packageName)
            if (id > 0) {
                return r.getString(id)
            }
        }
        return if (stringType == "title") entryName else ""
    }

    private fun createViewForEntry(entry: DCConfEntry<Any>): ListTileView? {
//        val prefix = "conf_${conf.confName}_${entry.name}"
//        val titleId = r.getIdentifier("${prefix}_title", "string", packageName)
//        val t = if (titleId > 0) r.getString(titleId) else entry.name
//        val infoId = r.getIdentifier("${prefix}_info", "string", packageName)
//        val s = if (infoId > 0) r.getString(infoId) else ""
        val t = extractResourceString(entry.name, "title")
        val s = extractResourceString(entry.name, "info")
        return when (entry.type) {
            ConfTypes.BOOL -> createBoolInput((entry as? BoolEntry) ?: return null, t, s)
            ConfTypes.INT -> createIntInput((entry as? IntEntry) ?: return null, t, s)
            ConfTypes.STRING -> createStringInput((entry as? StringEntry) ?: return null, t, s)
            ConfTypes.SELECT -> createSelectInput((entry as? SelectEntry) ?: return null, t, s)
            ConfTypes.DIR -> createPathInput((entry as? DirEntry) ?: return null, t, s)
            ConfTypes.DOC -> createPathInput((entry as? DocEntry) ?: return null, t, s)
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

    private fun createPathInput(entry: PathEntry, label: String, info: String) = TextInputView(context).apply {
        title = label
        text = Uri.decode(entry.value.split("/").last())
        onInput = { v -> entry.updateValue(v) }
        val newKey = activityLaunchers.keys.size * 1000 + (0 .. 999).random()
        val intentAction = when (entry.type) {
            ConfTypes.DIR -> Intent.ACTION_OPEN_DOCUMENT_TREE
            else -> Intent.ACTION_OPEN_DOCUMENT
        }
        val activityResultLauncher = newActivityCaller(fragment) { _, intent ->
            val uri = intent?.data
            entry.updateValue("$uri")
            text = Uri.decode(entry.value.split("/").last())
            if (entry.persistent) {
                if (uri != null) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    APP.contentResolver.takePersistableUriPermission(uri, flags)
                }
            }
            activityLaunchers.remove(newKey)
        }
        activityLaunchers[newKey] = activityResultLauncher

        setOnClickListener {
            activityResultLauncher.launch(Intent(intentAction).apply {
                if (entry.type != ConfTypes.DIR) type = "*/*"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, entry.value)
                }
            })
        }
    }
}
