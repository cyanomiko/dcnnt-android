package net.dcnnt.ui

import android.content.Context
import net.dcnnt.core.*

open class ConfListView(context: Context) : VerticalLayout(context) {
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
            else -> null
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

    // ToDo: This function, probably changes in SelectEntry class
    private fun createSelectTnput(entry: SelectEntry, label: String, info: String): SelectInputView {
        return SelectInputView(context).apply {
            title = label
            text = entry.value
        }
    }
}