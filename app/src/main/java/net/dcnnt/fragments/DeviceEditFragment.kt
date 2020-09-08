package net.dcnnt.fragments

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.ui.DCFragment
import net.dcnnt.ui.TextInputView
import net.dcnnt.ui.VerticalLayout


class DeviceEditFragment: DCFragment() {
    val TAG = "DC/DeviceEditFragment"
    var device: Device? = null

    companion object {
        private const val ARG_UIN = "uin"
        fun newInstance(uin: Int) = DeviceEditFragment().apply {
            arguments = bundleOf(ARG_UIN to uin)
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = APP.dm.devices[arguments?.getInt(ARG_UIN) ?: return]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        container?.context?.also { return fragmentMainView(it) }
        return null
    }

}
