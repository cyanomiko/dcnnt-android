package net.dcnnt.fragments

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import net.dcnnt.R
import net.dcnnt.core.APP
import net.dcnnt.plugins.ClipboardInfo
import net.dcnnt.plugins.ClipboardPlugin
import net.dcnnt.ui.*
import kotlin.concurrent.thread


class ClipboardFragment: BasePluginFargment() {
    override val TAG = "DC/Clipboard"
    var remoteClipboards: List<ClipboardInfo> = listOf()
    var clipboardsListView: LinearLayout? = null

    fun clipboardView(context: Context, clipboard: ClipboardInfo) = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(TextBlockView(context, clipboard.name))
        if (clipboard.isReadable) {
            addView(Button(context).apply {
                text = context.getString(R.string.conf_sync_clipboard_mode_fetch)
                LParam.set(this, LParam.mw())
                setOnClickListener {
                    selectedDevice?.also { device ->
                        thread {
                            try {
                                ClipboardPlugin(APP, device).apply {
                                    init(context)
                                    connect()
                                    setClipboardText(context, clipboard.key, readRemoteClipboard(clipboard))
                                    toast(context, "OK")
                                }
                            } catch (e: Exception) {
                                showError(context, e)
                            }
                        }
                    }
                }
            })
        }
        if (clipboard.isWritable) {
            addView(Button(context).apply {
                text = context.getString(R.string.conf_sync_clipboard_mode_send)
                LParam.set(this, LParam.mw())
                setOnClickListener {
                    selectedDevice?.also { device ->
                        thread {
                            try {
                                ClipboardPlugin(APP, device).apply {
                                    init(context)
                                    connect()
                                    toast(context, writeRemoteClipboard(clipboard, getClipboardText(context) ?: ""))
                                }
                            } catch (e: Exception) {
                                showError(context, e)
                            }
                        }
                    }
                }
            })
        }
    }

    fun updateClipboardsList(context: Context) {
        selectedDevice?.also { device ->
            thread {
                try {
                    ClipboardPlugin(APP, device).apply {
                        init(context)
                        connect()
                        remoteClipboards = getRemoteClipboardsList()
                        activity?.runOnUiThread { redrawClipboardsListView(context) }
                    }
                } catch (e: Exception) {
                    showError(context, e)
                }
            }
        }
    }

    fun redrawClipboardsListView(context: Context) {
        clipboardsListView?.also { container ->
            container.removeAllViews()
            if (remoteClipboards.isEmpty()) {
                container.addView(
                    TextBlockView(context, context.getString(R.string.no_clipboards_available)))
            } else {
                remoteClipboards.forEach { container.addView(clipboardView(context, it)) }
            }
        }
    }

    override fun onSelectedDeviceChanged() {
        updateClipboardsList(context ?: return)
    }

    fun fragmentMainView(context: Context): View = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context))
        addView(ScrollView(context).apply {
            clipboardsListView = VerticalLayout(context).apply {
                addView(TextBlockView(context, context.getString(R.string.no_clipboards_available)))
            }
            addView(clipboardsListView)
            LParam.set(this, LParam.mm())
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        container?.context?.also { return fragmentMainView(it) }
        return null
    }

    override fun onStart() {
        super.onStart()
        updateClipboardsList(context ?: return)
    }
}
