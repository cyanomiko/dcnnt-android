package net.dcnnt.ui

import android.content.Intent
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import net.dcnnt.MainActivity

/**
 * Base class for all fragments in application
 */
open class DCFragment: Fragment() {
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
        return true
    }
}