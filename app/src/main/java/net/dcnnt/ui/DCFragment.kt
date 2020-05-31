package net.dcnnt.ui

import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment

/**
 * Base class for all fragments in application
 */
open class DCFragment(val toolbarView: Toolbar): Fragment() {
    /**
     * Reload this function to draw toolbar buttons and menu
     */
    open fun prepareToolbar() {
        toolbarView.menu.clear()
    }

    /**
     * Do some action on back button pressed
     * @return true if event propagated, otherwise - false
     */
    open fun onBackPressed(): Boolean {
        return true
    }
}