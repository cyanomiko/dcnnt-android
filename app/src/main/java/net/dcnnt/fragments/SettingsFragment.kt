package net.dcnnt.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.widget.Toolbar
import net.dcnnt.MainActivity
import net.dcnnt.core.*
import net.dcnnt.ui.*


class SettingsFragment(toolbarView: Toolbar): DCFragment(toolbarView) {
    val TAG = "DC/SettingsFragment"
    private var notificationConfView: BoolInputView? = null

    fun checkNotificationAccess() {
        val view = notificationConfView ?: return
        val res = (activity as? MainActivity)?.isNotificationServiceEnabled() ?: false
        Log.d(TAG, "Notification access = $res")
        view.value = res
        Log.d(TAG, "notificationConfView.value = ${view.value}")
        APP.conf.notificationListenerService.updateValue(view.value)
    }

    fun fragmentMainView(context: Context) = ScrollView(context).apply {
        addView(ConfListView(context).apply {
            init(APP.conf)
            (confViews[APP.conf.notificationListenerService.name] as? BoolInputView)?.also {
                notificationConfView = it
                it.onInput = { _ ->
                    context.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    checkNotificationAccess()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        checkNotificationAccess()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        container?.context?.also { return fragmentMainView(it) }
        return null
    }

}
