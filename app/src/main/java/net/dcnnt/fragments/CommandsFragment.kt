package net.dcnnt.fragments

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.Toolbar
import net.dcnnt.R
import net.dcnnt.core.APP
import net.dcnnt.plugins.RemoteCommand
import net.dcnnt.plugins.RemoteCommandPlugin
import net.dcnnt.ui.*
import kotlin.concurrent.thread


class CommandsFragment(toolbarView: Toolbar): BasePluginFargment(toolbarView) {
    override val TAG = "DC/Commands"
    var remoteCommands: List<RemoteCommand> = listOf()
    var commandsListView: LinearLayout? = null

    fun commandView(context: Context, command: RemoteCommand) = VerticalLayout(context).apply {
        padding = context.dip(6)
        if (command.index == null) {
            addView(TextBlockView(context, command.name))
        } else {
            addView(Button(context).apply {
                text = command.name
                LParam.set(this, LParam.mw())
                setOnClickListener {
                    selectedDevice?.also { device ->
                        thread {
                            try {
                                RemoteCommandPlugin(APP, device).apply {
                                    init()
                                    connect()
                                    exec("${command.index}").also { toast(context, it.message) }
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

    fun updateCommandsList(context: Context) {
        selectedDevice?.also { device ->
            thread {
                try {
                    RemoteCommandPlugin(APP, device).apply {
                        init()
                        connect()
                        remoteCommands = list()
                        Log.d(TAG, "$remoteCommands")
                        activity?.runOnUiThread { redrawCommandsListView(context) }
                    }
                } catch (e: Exception) {
                    showError(context, e)
                }
            }
        }
    }

    fun redrawCommandsListView(context: Context) {
        commandsListView?.also { container ->
            container.removeAllViews()
            if (remoteCommands.isEmpty()) {
                container.addView(
                    TextBlockView(context, context.getString(R.string.no_commands_available)))
            } else {
                remoteCommands.forEach { command ->
                    if (command.index == null) {
                        container.addView(TextBlockView(context, command.name))
                    } else {
                        container.addView(commandView(context, command))
                    }
                }
            }
        }
    }

    fun fragmentMainView(context: Context): View = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context).apply {
            onUpdateOptons = {_, changed, _ -> if (changed) updateCommandsList(context) }
        })
        addView(ScrollView(context).apply {
            commandsListView = VerticalLayout(context).apply {
                addView(TextBlockView(context, context.getString(R.string.no_commands_available)))
            }
            addView(commandsListView)
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
        updateCommandsList(context ?: return)
    }
}
