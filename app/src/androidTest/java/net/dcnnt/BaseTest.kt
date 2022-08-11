package net.dcnnt

import android.content.Context
import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.lang.Thread.sleep
import androidx.appcompat.app.ActionBarDrawerToggle


//@RunWith(AndroidJUnit4ClassRunner::class)
//@SdkSuppress(minSdkVersion = 18)
//@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class BaseTest {
    protected val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    protected val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        val appName = "net.dcnnt"
        val mainActivityName = "MainActivity"
        val launchTimeout = 5000L
        val pairingCode = "123456"
        val serverUin = 1337
        val serverPassword = "test_password"
    }

    /**
     * Wait for the app to appear
     */
    fun waitAppStart() {
        device.wait(Until.hasObject(By.pkg(appName).depth(0)), launchTimeout)
    }

    fun startApp() {
        Runtime.getRuntime().exec(arrayOf("am", "start", "$appName/$appName.$mainActivityName"))
        waitAppStart()
    }

    fun stopApp() {
        Runtime.getRuntime().exec(arrayOf("am", "kill", appName))
    }

    fun startAppFromHomeScreen() {
        // Start from the home screen
        device.pressHome()
        // Wait for launcher
        val launcherPackage: String = device.launcherPackageName
        assertThat(launcherPackage, notNullValue())
        device.wait(
            Until.hasObject(By.pkg(launcherPackage).depth(0)),
            launchTimeout
        )
        // Launch the app
        val intent = context.packageManager.getLaunchIntentForPackage(appName)?.apply {
            // Clear out any previous instances
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        waitAppStart()
    }

    fun restartApp() {
        stopApp()
        startAppFromHomeScreen()
    }

    /**
     * Wait some condition, return true on OK, false on timeout
     */
    fun wait(timeout: Long = 5000L, pause: Long = 100L, condition: () -> Boolean): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            if (condition()) return true
            sleep(pause)
        }
        return false
    }

    /**
     * Wait some condition, assertion error on timeout
     */
    fun assertWait(message: String = "Wait timeout", timeout: Long = 5000L,
                   pause: Long = 100L, condition: () -> Boolean) {
        assertTrue(message, wait(timeout, pause, condition))
    }

    /**
     * Get string from resources
     */
    fun str(resourceId: Int) = context.getString(resourceId)

    /**
     * Show navigation menu
     */
    fun showSideMenu() {
        val navToggleClose = device.findObject(
            UiSelector().descriptionContains(str(R.string.navigation_drawer_close)))
        if (navToggleClose.exists()) return
        val navToggleShow = device.findObject(
            UiSelector().descriptionContains(str(R.string.navigation_drawer_open)))
        assert(navToggleShow.exists())
        navToggleShow.click()
    }

    /**
     * Click UI element with text, return true if clicked
     */
    fun clickText(text: String): Boolean {
        val uiEl = device.findObject(UiSelector().textContains(text))
        if (uiEl.exists()) {
            uiEl.click()
            return true
        }
        return false
    }

    fun assertClickText(text: String) = assert(clickText(text))
    fun assertClickText(textId: Int) = assertClickText(str(textId))

    fun assertClickButton(text: String) {
        val button = device.findObject(UiSelector().text(text.uppercase()))
        assert(button.exists())
        assert(button.isEnabled)
        button.click()
    }
    fun assertClickButton(textId: Int) = assertClickButton(str(textId))

    /**
     * Go to UI fragment
     */
    fun navGo(navEntryTextId: Int) {
        showSideMenu()
//        clickText(navEntryTextId)
        val navEntry = device.findObject(UiSelector().text(str(navEntryTextId)))
        assert(navEntry.exists())
        navEntry.click()
    }

    fun inputText(text: String) {
        val textEdit = device.findObject(UiSelector().className(EditText::class.java.name))
        assert(textEdit.exists())
        textEdit.text = text
    }

    fun assertWaitDialog(header: String) {
        val dialogHeader = device.findObject(UiSelector().textContains(header))
        assertWait("Wait dialog '$header'") { dialogHeader.exists() }
    }

    fun assertInputTextInDialog(header: String, text: String) {
        assertWaitDialog(header)
        inputText(text)
        assertClickButton("OK")
    }

    fun assertClickDialog(header: String, text: String) {
        assertWaitDialog(header)
        assertClickButton(text)
    }
}
