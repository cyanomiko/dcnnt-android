package net.dcnnt.fragments

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.widget.Toolbar
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.ui.DCFragment
import net.dcnnt.ui.TextInputView
import net.dcnnt.ui.VerticalLayout


class DeviceEditFragment(toolbarView: Toolbar, val uin: Int): DCFragment(toolbarView) {
    val TAG = "DC/DeviceEditFragment"
    val device = APP.dm.devices[uin]

    fun fragmentMainView(context: Context) = ScrollView(context).apply {
        addView(VerticalLayout(context).apply {
            addView(TextInputView(context).apply {
                title = context.getString(R.string.device_uin)
                text = "${device?.uin}"
                isNumeric = true
            })
            addView(TextInputView(context).apply {
                title = context.getString(R.string.device_name)
                text = "${device?.name}"
            })
            addView(TextInputView(context).apply {
                title = context.getString(R.string.device_description)
                text = "${device?.description}"
                onInput = {
                    device?.apply {
                        description = it
                        APP.dm.dumpItem(this)
                    }
                }
            })
            addView(TextInputView(context).apply {
                title = context.getString(R.string.device_password)
                text = "${device?.password}"
                onInput = {
                    device?.apply {
                        password = it
                        APP.dm.dumpItem(this)
                        updateKeys()
                    }
                }
            })
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        container?.context?.also { return fragmentMainView(it) }
        return null
    }

}
