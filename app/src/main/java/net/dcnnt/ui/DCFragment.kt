package net.dcnnt.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import net.dcnnt.MainActivity
import net.dcnnt.core.APP
import java.lang.Exception

/**
 * Base class for all fragments in application
 */
open class DCFragment: Fragment() {
    val activityResultHandlers: MutableMap<Int, (Int, Int, Intent?) -> Boolean> = mutableMapOf()
    /**
     * Reload this function to draw toolbar buttons and menu
     */
    open fun prepareToolbar(toolbarView: Toolbar) {
        toolbarView.menu.clear()
    }

    /**
     * Do some action on back button pressed
     * @return true if event propagated, otherwise - false
     */
    open fun onBackPressed(): Boolean {
        return true
    }

    /**
     * Handle result of intent activity
     * @return true if event propagated, otherwise - false
     */
    open fun onActivityResult(mainActivity: MainActivity, requestCode: Int,
                              resultCode: Int, data: Intent?): Boolean {
        activityResultHandlers[requestCode]?.also { handler ->
            return handler(requestCode, resultCode, data)
        }
        return true
    }

    /**
     * Just show toast with text
     */
    fun toast(context: Context, text: String) {
        activity?.runOnUiThread { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Show error message for user and add it to log
     */
    fun showError(context: Context, e: Exception) {
        toast(context, "Error: $e")
        APP.log("Error occurred: $e")
        APP.logException(e)
    }
}
