package net.dcnnt.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.util.Log
import android.widget.*
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import net.dcnnt.MainActivity
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.ui.DCFragment
import net.dcnnt.ui.Option
import net.dcnnt.ui.SelectInputView
import net.dcnnt.ui.dip
import java.lang.Exception
import kotlin.concurrent.thread


class DeviceSelectView(context: Context) : SelectInputView(context) {
    val TAG = "DC/DSView"
    var availableDevicesOnly = true
    var enableCommonDevice = false
    var onUpdateOptons: ((Int?, Boolean, MutableList<Option>) -> Unit)? = null
    var refreshButton = ImageView(context).apply {
        id = 10
        setImageResource(R.drawable.ic_refresh)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimary))
        layoutParams = RelativeLayout.LayoutParams(context.dip(32), context.dip(32)).apply {
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.ALIGN_PARENT_END)
        }
        setOnClickListener { refreshDevices() }
    }

    init {
        setOnClickListener {
            if (options.isEmpty()) {
                refreshDevices()
            } else {
                showSelectionDialog()
            }
        }
        addView(refreshButton)
        updateOptions()
    }

    fun updateOptions() {
        val indexPre = index
        var optionPre: Option? = null
        var selectionChanged = false
        index?.let { optionPre = options[it] }
        selectDisabled = true
        options.clear()
        if (enableCommonDevice) {
            options.add(Option(context.getString(R.string.common_device_name), APP.dm.commonDevice))
        }
        APP.dm.devices.filterValues {
            ((!it.isNew() and (it.ip != null)) or !availableDevicesOnly)
        }.values.forEach {
            options.add(Option(it.name, it))
        }
        if (options.isEmpty()) {
            index = null
            title = context.getString(R.string.no_devices)
            text = context.getString(R.string.click_to_search)
        } else {
            selectDisabled = false
            index = 0
            optionPre?.let{
                index = options.indexOf(it)
                if (index == -1) {
                    index = 0
                    selectionChanged = true
                }
            }
            title = context.getString(R.string.selected_device)
            text = options[0].title
        }
        onUpdateOptons?.invoke(index, selectionChanged or (index != indexPre), options)
    }

    fun refreshDevices() {
        Log.d(TAG, "Start refresh")
        refreshButton.isClickable = false
        refreshButton.setImageResource(R.drawable.ic_wait)
        refreshButton.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimaryDark))
        Log.d(TAG, "Create thread")
        thread {
            Log.d(TAG, "Start search")
            APP.dm.syncSearch(APP.conf)
            APP.activity?.runOnUiThread {
                Log.d(TAG, "Update options")
                updateOptions()
                Log.d(TAG, "Restore image, activity = ${APP.activity}")
                refreshButton.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimary))
                refreshButton.setImageResource(R.drawable.ic_refresh)
                refreshButton.isClickable = true
            }
        }
    }
}


abstract class BasePluginFargment(toolbarView: Toolbar): DCFragment(toolbarView) {
    open val TAG = "DC/PluginUI"
    open var deviceSelectView: DeviceSelectView? = null
    val selectedDevice: Device?
        get() { return deviceSelectView?.options?.getOrNull(deviceSelectView?.index ?: return null)?.value as? Device }

    open fun onSelectedDeviceChanged() {}

    fun toast(context: Context, text: String) {
        activity?.runOnUiThread { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    fun showError(context: Context, e: Exception) = toast(context, "Error: $e")

    fun createDeviceSelectView(context: Context,
                               availableOnly: Boolean = true,
                               addCommon: Boolean = false): DeviceSelectView {
        return DeviceSelectView(context).apply {
            deviceSelectView = this
            if ((availableOnly != availableDevicesOnly) or (addCommon != enableCommonDevice)) {
                availableDevicesOnly = availableOnly
                enableCommonDevice = addCommon
                updateOptions()
            }
            onInput = { _, _ -> onSelectedDeviceChanged() }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { APP.activity = it }
        deviceSelectView?.updateOptions()
    }
}
