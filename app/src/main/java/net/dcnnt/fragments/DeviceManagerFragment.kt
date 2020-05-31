package net.dcnnt.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import net.dcnnt.R
import net.dcnnt.core.APP
import net.dcnnt.core.Device
import net.dcnnt.core.EVENT_AVAILABLE_DEVICES_UPDATED
import net.dcnnt.ui.*
import kotlin.concurrent.thread


class DeviceManagerFragment(toolbarView: Toolbar): DCFragment(toolbarView) {
    val TAG = "DC/DMFragment"
    lateinit var deviceListView: VerticalLayout
    private lateinit var deviceNotAtAllStr: String

    fun deviceEntryView(context: Context, device: Device) = EntryView(context).apply {
        title = "${device.uin} - ${device.name}"
        text = device.ip ?: context.getString(R.string.device_offline)
        progressView.visibility = View.GONE
        iconView.visibility = View.GONE
        removeView(progressView)
        removeView(iconView)
        actionView.setImageResource(R.drawable.ic_cancel)
        setOnClickListener { APP.activity?.navigation?.go("/device", listOf(device.uin)) }
        actionView.setOnClickListener {
            AlertDialog.Builder(context).also {
                it.setTitle(context.getString(R.string.delete_device_title))
                it.setMessage(context.getString(R.string.delete_device_warning))
                it.setNegativeButton(context.getString(R.string.cancel)) { _, _ -> }
                it.setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                    APP.dm.remove(device.uin)
                    updateDeviceListView()
                }
            }.create().show()
        }
    }

    fun updateDeviceListView() {
        deviceListView.removeAllViews()
        with(APP.dm.devices.values) {
            if (size > 0) {
                forEach { deviceListView.addView(deviceEntryView(deviceListView.context, it)) }
            } else {
                deviceListView.addView(
                    TextBlockView(deviceListView.context, deviceNotAtAllStr)
                )
            }
        }
    }

    fun fragmentMainView(context: Context): View = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(Button(context).apply {
            text = context.getString(R.string.device_search)
            setOnClickListener {
                thread {
                    if (APP.dm.searching.compareAndSet(false, true)) {
                        activity?.runOnUiThread {
                            this.isClickable = false
                            this.text = context.getString(R.string.device_search_in_progress)
                            this.refreshDrawableState()
                        }
                        APP.dm.search(APP.conf)
                        activity?.runOnUiThread { updateDeviceListView() }
                        APP.dm.searching.set(false)
                        activity?.runOnUiThread {
                            this.text = context.getString(R.string.device_search)
                            this.isClickable = true
                            this.refreshDrawableState()
                        }
                    }
                }
            }
        })
        addView(ScrollView(context).apply {
            addView(VerticalLayout(context).apply {
                deviceListView = this
            })
            LParam.set(this, LParam.mm())
        })
        updateDeviceListView()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        container?.context?.also { context ->
            deviceNotAtAllStr = context.getString(R.string.device_no_at_all)
            val mainView = fragmentMainView(context)
            LocalBroadcastManager.getInstance(context).registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(contxt: Context?, intent: Intent?) {
                    when (intent?.action) {
                        EVENT_AVAILABLE_DEVICES_UPDATED -> activity?.runOnUiThread { updateDeviceListView() }
                    }
                }
            }, IntentFilter(EVENT_AVAILABLE_DEVICES_UPDATED))
            return mainView
        }
        return null
    }
}
