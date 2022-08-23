package net.dcnnt

import android.widget.Button
import android.widget.EditText
import androidx.test.filters.SdkSuppress
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.uiautomator.UiSelector
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters


@RunWith(AndroidJUnit4ClassRunner::class)
@SdkSuppress(minSdkVersion = 18)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class StartupTests: BaseTest() {

    @Test
    fun t00pairingRoutine() {
        restartApp()
        assertInputTextInDialog("Pair code for", pairingCode)
        assertClickText("$serverUin")
        val passwordEdit = device.findObject(UiSelector().text(serverPassword))
        assert(passwordEdit.exists())
    }

    @Test
    fun t10appBarText() {
        restartApp()
        val textEl = device.findObject(UiSelector().text("Device Connect"))
        println(textEl.className)
        assert(textEl.exists())
    }

    @Test
    fun t20configureDownloadDirectory() {
        restartApp()
        navGo(R.string.menu_download)
        assertClickButton(R.string.configure)
        assertClickText("Download")
        assertClickText("NEW FOLDER")
        assertInputTextInDialog("New folder", "dcnnt")
        assertClickText("USE THIS FOLDER")
        assertClickDialog("Allow", "ALLOW")
        assertClickText("..")
    }
}
