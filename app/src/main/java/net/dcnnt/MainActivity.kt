package net.dcnnt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.widget.FrameLayout
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.navigation.NavigationView
import net.dcnnt.core.APP
import net.dcnnt.core.ENABLED_NOTIFICATION_LISTENERS
import net.dcnnt.fragments.*
import net.dcnnt.ui.*
import kotlin.system.exitProcess

/**
 * Simple navigation facility for app
 */
class Navigation(private val toolbarView: Toolbar,
                 private val supportFragmentManager: FragmentManager,
                 private val fragmentContainerId: Int) {
    val TAG = "DC/Navigation"
    val stack = listOf<String>().toMutableList()
    val usedFargments = hashMapOf<String, DCFragment>()
    lateinit var currentFragment: DCFragment

    /**
     * Create initial state of navigation at apps start
     */
    fun start() {
        val transaction = supportFragmentManager.beginTransaction()
        val fragment = DeviceManagerFragment(toolbarView)
        transaction.add(fragmentContainerId, fragment)
        transaction.commit()
        usedFargments["/dm"] = fragment
        stack.add("/dm")
        currentFragment = fragment
        fragment.prepareToolbar()
    }

    /**
     * Go to some fragment (from stack or newly created) by its link
     * @param link - string associated with fragment, incorrect links just skipped
     * @param args - list of arguments to pass to fragment constructor
     * @param createNew - if true - do not search already existing fragment in stack
     */
    fun go(link: String, args: List<Any?>, createNew: Boolean = false) {
        val key = "$link : $args"
        ((if (createNew) null else usedFargments[key]) ?: when (link) {
            "/dm" -> DeviceManagerFragment(toolbarView)
            "/settings" -> SettingsFragment(toolbarView)
            "/device" -> {
                val uin = args.firstOrNull()
                Log.i(TAG, "args = $args, uin = $uin")
                when(uin is Int) {
                    true -> DeviceEditFragment(toolbarView, uin)
                    false -> null
                }
            }
            "/download" -> DownloadFileFragment(toolbarView)
            "/upload" -> UploadFileFragment(
                toolbarView,
                args.filterIsInstance<Intent>().firstOrNull()
            )
            "/commands" -> CommandsFragment(toolbarView)
            "/notifications" -> NotificationsFragment(toolbarView)
            else -> null
        })?.also { fragment ->
            supportFragmentManager.beginTransaction().also { transaction ->
                usedFargments[key] = fragment
                stack.add(key)
                transaction.replace(fragmentContainerId, fragment)
                transaction.commit()
            }
            currentFragment = fragment
            fragment.prepareToolbar()
        }
    }

    /**
     * Return to previous fragment if possible
     * @return - true on success
     */
    fun back(): Boolean {
        if (!currentFragment.onBackPressed()) return true
        if (stack.size > 1) {
            val currentLink = stack.removeAt(stack.lastIndex)
            if (!stack.contains(currentLink)) usedFargments.remove(currentLink)
            usedFargments[stack.last()]?.also { fragment ->
                supportFragmentManager.beginTransaction().also {
                    it.replace(fragmentContainerId, fragment)
                    it.commit()
                }
                currentFragment = fragment
                fragment.prepareToolbar()
                return true
            }
        }
        return false
    }
}


class MainActivity : AppCompatActivity() {
    val TAG = "DC/UI"
    private val CODE_NOTIFICATIONS = 42
    private val CODE_RESTART = 1337
    lateinit var toolbarEl: Toolbar
    lateinit var drawerEl: DrawerLayout
    lateinit var navMenuEl: NavigationView
    lateinit var fragmentEl: FrameLayout
    lateinit var navigation: Navigation
    var onNotificationAccessActivityResult = {}

    /**
     * Init basic UI and navigation menu then go to start fragment
     */
    private fun initUI() {
        setSupportActionBar(toolbarEl)
        val toggle = ActionBarDrawerToggle(this, drawerEl, toolbarEl, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawerEl.addDrawerListener(toggle)
        toggle.syncState()
        navMenuEl.menu.also {
            it.add(0, Menu.FIRST + 0, Menu.NONE, R.string.menu_devices)
            it.add(0, Menu.FIRST + 1, Menu.NONE, R.string.menu_settings)
            it.add(0, Menu.FIRST + 2, Menu.NONE, R.string.menu_upload)
            it.add(0, Menu.FIRST + 3, Menu.NONE, R.string.menu_download)
            it.add(0, Menu.FIRST + 4, Menu.NONE, R.string.menu_commands)
            it.add(0, Menu.FIRST + 5, Menu.NONE, R.string.menu_notifications)
            it.add(0, Menu.FIRST + 6, Menu.NONE, R.string.menu_exit)
        }
        navMenuEl.setNavigationItemSelectedListener {
            drawerEl.closeDrawer(navMenuEl)
            if (it.itemId == Menu.FIRST + 0) navigation.go("/dm", listOf())
            if (it.itemId == Menu.FIRST + 1) navigation.go("/settings", listOf())
            if (it.itemId == Menu.FIRST + 2) navigation.go("/upload", listOf())
            if (it.itemId == Menu.FIRST + 3) navigation.go("/download", listOf())
            if (it.itemId == Menu.FIRST + 4) navigation.go("/commands", listOf())
            if (it.itemId == Menu.FIRST + 5) navigation.go("/notifications", listOf())
            if (it.itemId == Menu.FIRST + 6) exitDialog()
            return@setNavigationItemSelectedListener true
        }
        navigation.start()
    }

    /**
     * Restart app using alarm
     */
    fun restartApp(context: Context): Boolean {
        val intent = Intent(context, MainActivity::class.java)
        val mPendingIntent = PendingIntent.getActivity(context, CODE_RESTART, intent, PendingIntent.FLAG_CANCEL_CURRENT)
        val mgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, mPendingIntent)
        exitProcess(0)
    }

    /**
     * Show exit/restart dialog when "exit" in menu selected
     */
    fun exitDialog() {
       AlertDialog.Builder(this).apply {
            setTitle(R.string.restart_dialog_title)
            setMessage(R.string.restart_dialog_text)
            setNeutralButton(R.string.cancel) { _, _ ->  }
            setNegativeButton(R.string.restart) { _, _ -> restartApp(this@MainActivity) }
            setPositiveButton(R.string.stop) { _, _ -> exitProcess(0) }
        }.create().show()
    }

    /**
     * Check if notification listener service enabled
     * @return - true if enabled
     */
    fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, ENABLED_NOTIFICATION_LISTENERS)
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (i in names.indices) {
                val cn = ComponentName.unflattenFromString(names[i])
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.packageName)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Create basic part of UI in activity, no XML used
     */
    private fun createUI(context: Context) = DrawerLayout(context).apply {
        drawerEl = this
        fitsSystemWindows = true
        layoutParams = DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT)
        removeAllViews()
        addView(VerticalLayout(this.context).apply {
            gravity = Gravity.NO_GRAVITY
            LParam.set(this, LParam.mm())
            addView(AppBarLayout(context).apply {
                LParam.set(this, LParam.mw())
                addView(Toolbar(context).apply {
                    toolbarEl = this
                    title = "Device Connect"
                })
            })
            addView(FrameLayout(context).apply {
                id = 1001
                LParam.set(this, LParam.mm())
                fragmentEl = this
            })
        })
        addView(NavigationView(this.context).apply {
            fitsSystemWindows = true
            layoutParams = DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.WRAP_CONTENT,
                DrawerLayout.LayoutParams.MATCH_PARENT, GravityCompat.START)
            navMenuEl = this
        })
    }

    /* Debug
    fun bundleToString(bundle: Bundle?): String? {
        val out = StringBuilder("Bundle[")
        if (bundle == null) {
            out.append("null")
        } else {
            var first = true
            for (key in bundle.keySet()) {
                if (!first) {
                    out.append(", ")
                }
                out.append(key).append('=')
                val value = bundle[key]
                if (value is IntArray) {
                    out.append(Arrays.toString(value as IntArray?))
                } else if (value is ByteArray) {
                    out.append(Arrays.toString(value as ByteArray?))
                } else if (value is BooleanArray) {
                    out.append(Arrays.toString(value as BooleanArray?))
                } else if (value is ShortArray) {
                    out.append(Arrays.toString(value as ShortArray?))
                } else if (value is LongArray) {
                    out.append(Arrays.toString(value as LongArray?))
                } else if (value is FloatArray) {
                    out.append(Arrays.toString(value as FloatArray?))
                } else if (value is DoubleArray) {
                    out.append(Arrays.toString(value as DoubleArray?))
                } else if (value is Bundle) {
                    out.append(bundleToString(value as Bundle?))
                } else {
                    out.append(value)
                }
                first = false
            }
        }
        out.append("]")
        return out.toString()
    }
    */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        APP.activity = this
        setContentView(createUI(this))
        navigation = Navigation(toolbarEl, supportFragmentManager, fragmentEl.id)
        initUI()
        /* Debug
        Log.d(TAG, "$intent ${bundleToString(intent.extras)}")
         */
        if ((intent?.action == Intent.ACTION_SEND) or (intent?.action == Intent.ACTION_SEND_MULTIPLE)) {
            navigation.go("/upload", listOf(intent), createNew = true)
        }
    }

    override fun onResume() {
        super.onResume()
        APP.activity = this
    }

    override fun onDestroy() {
        super.onDestroy()
        APP.activity = null
    }

    override fun onBackPressed() {
        if (drawerEl.isDrawerOpen(GravityCompat.START)) {
            drawerEl.closeDrawer(GravityCompat.START)
        } else {
            if (!navigation.back()) {
                super.onBackPressed()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            CODE_NOTIFICATIONS -> onNotificationAccessActivityResult()
            else -> {
                if (resultCode != RESULT_OK) return
                APP.rootDirectory = data?.data ?: return
                grantUriPermission(packageName, APP.rootDirectory,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(APP.rootDirectory,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }
}
