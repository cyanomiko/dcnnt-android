package net.dcnnt.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.ContextCompat
import net.dcnnt.R

// Custom components

open class VerticalLayout(context: Context) : LinearLayout(context) {
    init { orientation = VERTICAL }
}

open class TextBlockView(context: Context) : LinearLayout(context) {

    constructor(context: Context, text: String) : this(context) { this.text = text }

    private val textView = TextView(context).apply {
        this.text = text
        isSingleLine = true
        gravity = Gravity.CENTER
        minimumHeight = context.dip(32)
        textAppearance = R.style.TextAppearance_AppCompat_Body2
        LParam.set(this, LParam.mw())
    }

    init { addView(textView) }

    final override fun addView(child: View?) = super.addView(child)

    var text: String
        get() = textView.text.toString()
        set(value) { textView.text = value }
}

open class ListTileView(context: Context) : RelativeLayout(context) {
    val titleView = TextView(context).apply{
        textAppearance = R.style.TextAppearance_AppCompat_Subhead
        layoutParams = LayoutParams(LParam.M, LParam.W)
    }
    val textView = TextView(context).apply {
        layoutParams = LayoutParams(LParam.M, LParam.W)
    }
    val centralView = VerticalLayout(context).apply {
        padding = context.dip(6)
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LParam.M, LParam.W)
        addView(titleView)
        addView(textView)
    }
    val leftView = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(centralView)
        layoutParams = LayoutParams(LParam.M, LParam.W).apply {
            addRule(ALIGN_PARENT_START)
            addRule(CENTER_VERTICAL)
        }
    }

    init {
        minimumHeight = context.dip(64)
        padding = context.dip(6)
        layoutParams = LParam.mw()
        addView(leftView)
    }

    final override fun addView(child: View?) = super.addView(child)
    final override fun addView(child: View?, index: Int) = super.addView(child, index)

    var text: String
        get() = textView.text.toString()
        set(value) { textView.text = value }

    var title: String
        get() = titleView.text.toString()
        set(value) { titleView.text = value }
}

class BoolInputView(context: Context) : ListTileView(context) {

    val switchView = Switch(context).apply {
        padding = context.dip(6)
        layoutParams = LayoutParams(LParam.W, LParam.W).apply {
            addRule(ALIGN_PARENT_END)
            addRule(CENTER_VERTICAL)
        }
        setOnClickListener { onInput?.invoke(value) }
    }
    var onInput: ((Boolean) -> Unit)? = null

    init {
        addView(switchView)
        setOnClickListener {
            value = !value
            onInput?.invoke(value)
        }
    }

    var value: Boolean
        get() = switchView.isChecked
        set(value) { switchView.isChecked = value }
}


open class TextInputView(context: Context) : ListTileView(context) {
    var onInput: ((String) -> Unit)? = null
    var isNumeric: Boolean = false

    init {
        setOnClickListener {
            val dialogInitialText = text
            AlertDialog.Builder(context).apply {
                var textEditView: EditText? = null
                setNegativeButton(context.getString(R.string.cancel)) { _, _ -> }
                setPositiveButton(context.getString(R.string.ok)) { _, _ -> handleInput(textEditView?.text.toString()) }
                setTitle(title)
                setView(VerticalLayout(context).apply {
                    padding = context.dip(6)
                    addView(EditText(context).apply {
                        if (isNumeric) inputType = InputType.TYPE_CLASS_NUMBER
                        textEditView = this
                        isSingleLine = true
                        text.clear()
                        text.append(dialogInitialText)
                        setOnFocusChangeListener { _, _ ->
                            this.post {
                                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                                    ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                        selectAll()
                    })
                })
            }.create().show()
        }
    }

    open fun handleInput(input: String) {
        text = input
        onInput?.invoke(input)
    }
}


data class Option(val title: String, val value: Any)


open class SelectInputView(context: Context) : ListTileView(context) {
    var onInput: ((Int, Any?) -> Unit)? = null
    var options: MutableList<Option> = mutableListOf()
    var index: Int? = null
    var selectDisabled = false

    init {
        setOnClickListener { showSelectionDialog() }
    }

    fun showSelectionDialog() {
        if (selectDisabled) return
        val dialogIndex = index ?: return
        AlertDialog.Builder(context).apply {
            setNegativeButton("Cansel") { _, _ -> }
            setPositiveButton("OK") { _, _ -> index?.also { handleInput(it, options[it].value) } }
            setTitle(title)
            setSingleChoiceItems(Array(options.size) { options[it].title }, dialogIndex) { d, i ->
                index = i
                index?.also { handleInput(it, options[it].value) }
                d.dismiss()
            }
        }.create().show()
    }

    fun handleInput(index: Int, input: Any) {
        text = input.toString()
        onInput?.invoke(index, input)
    }
}


open class EntryView(context: Context) : ListTileView(context) {
    val iconView = ImageView(context).apply {
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, net.dcnnt.R.color.colorPrimary))
        layoutParams = LayoutParams(context.dip(32), context.dip(32))
    }
    val actionView = ImageView(context).apply {
        id = 10
        setImageResource(net.dcnnt.R.drawable.ic_cancel)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, net.dcnnt.R.color.colorPrimary))
        layoutParams = LayoutParams(context.dip(32), context.dip(32)).apply {
            addRule(CENTER_VERTICAL)
            addRule(ALIGN_PARENT_END)
        }
    }
    val progressView = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        progress = 0
        max = 1000
        LParam.set(this, LParam.mw())
        layoutParams = LayoutParams(LParam.M, LParam.W)
    }

    init {
        (leftView.layoutParams as RelativeLayout.LayoutParams).addRule(LEFT_OF, actionView.id)
        leftView.addView(iconView, 0)
        centralView.addView(progressView)
        addView(actionView)
    }
}
