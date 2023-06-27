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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.navigation.NavigationView
import net.dcnnt.core.*
import net.dcnnt.fragments.*
import net.dcnnt.ui.*
import java.util.*
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
        val fragment = DeviceManagerFragment()
        transaction.add(fragmentContainerId, fragment)
        transaction.commit()
        usedFargments["/dm"] = fragment
        stack.add("/dm")
        currentFragment = fragment
        fragment.prepareToolbar(toolbarView)
    }

    /**
     * Go to some fragment (from stack or newly created) by its link
     * @param link - string associated with fragment, incorrect links just skipped
     * @param args - list of arguments to pass to fragment constructor
     * @param createNew - if true - do not search already existing fragment in stack
     */
    fun go(link: String, args: List<Any?> = listOf(), createNew: Boolean = false) {
        val key = if (args.isEmpty()) link else "$link : $args"
        Log.d(TAG, "$key = ${usedFargments[key]}")
        usedFargments.keys.forEach {
            Log.d(TAG, ">>>>> $it")
        }
        ((if (createNew) null else usedFargments[key]) ?: when (link) {
            "/dm" -> DeviceManagerFragment()
            "/settings" -> {
                val action = args.firstOrNull()
                SettingsFragment.newInstance(if (action is Int) action else SettingsFragment.ACTION_NONE)
            }
            "/device" -> {
                val uin = args.firstOrNull()
                Log.i(TAG, "args = $args, uin = $uin")
                when(uin is Int) {
                    true -> DeviceEditFragment.newInstance(uin)
                    false -> null
                }
            }
            "/download" -> DownloadFileFragment()
            "/upload" -> UploadFileFragment.newInstance(
                args.filterIsInstance<Intent>().firstOrNull()
            )
            "/open" -> OpenerFragment.newInstance(
                args.filterIsInstance<Intent>().firstOrNull()
            )
            "/commands" -> CommandsFragment()
            "/notifications" -> NotificationsFragment()
            "/sync" -> SyncFragment()
            "/log" -> LogDirectoryFragment()
            "/sync/task" -> {
                val uin = args.getOrNull(0);
                val taskKey = args.getOrNull(1);
                when((uin is Int) and (taskKey is String)) {
                    true -> SyncTaskEditFragment.newInstance(uin as Int, taskKey as String)
                    false -> null
                }
            }
            else -> null
        })?.also { fragment ->
            supportFragmentManager.beginTransaction().also { transaction ->
                usedFargments[key] = fragment
                stack.add(key)
                transaction.replace(fragmentContainerId, fragment)
                transaction.commit()
            }
            currentFragment = fragment
            fragment.prepareToolbar(toolbarView)
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
                fragment.prepareToolbar(toolbarView)
                return true
            }
        }
        return false
    }

    /**
     * Get text link to current fragment usable in go()
     * @return - some string
     */
    fun linkForCurrent(): String = stack.last()
}


class MainActivity : AppCompatActivity() {
    val TAG = "DC/UI"
    private val CODE_RESTART = 1337
    lateinit var toolbarEl: Toolbar
    lateinit var drawerEl: DrawerLayout
    lateinit var navMenuEl: NavigationView
    lateinit var fragmentEl: FrameLayout
    lateinit var navigation: Navigation
    val notificationAccessActivityLauncher = newActivityCaller(this) { _, _ -> }

    /**
     * Init basic UI and navigation menu then go to start fragment
     */
    private fun initUI() {
        setSupportActionBar(toolbarEl)
        val toggle = ActionBarDrawerToggle(this, drawerEl, toolbarEl, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawerEl.addDrawerListener(toggle)
        toggle.syncState()
        navMenuEl.itemIconPadding = dip(8)
        navMenuEl.setPadding(navMenuEl.paddingLeft / 3, navMenuEl.paddingTop,
            navMenuEl.paddingRight, navMenuEl.paddingBottom)
        navMenuEl.menu.also {
            it.add(0, Menu.FIRST + 0, Menu.NONE, R.string.menu_devices).setIcon(R.drawable.ic_phonelink)
            it.add(0, Menu.FIRST + 1, Menu.NONE, R.string.menu_settings).setIcon(R.drawable.ic_settings)
            it.add(0, Menu.FIRST + 2, Menu.NONE, R.string.menu_logs).setIcon(R.drawable.ic_lines)
            it.add(0, Menu.FIRST + 3, Menu.NONE, R.string.menu_upload).setIcon(R.drawable.ic_upload)
            it.add(0, Menu.FIRST + 4, Menu.NONE, R.string.menu_open).setIcon(R.drawable.ic_open)
            it.add(0, Menu.FIRST + 5, Menu.NONE, R.string.menu_download).setIcon(R.drawable.ic_download)
            it.add(0, Menu.FIRST + 6, Menu.NONE, R.string.menu_commands).setIcon(R.drawable.ic_cr)
            it.add(0, Menu.FIRST + 7, Menu.NONE, R.string.menu_notifications).setIcon(R.drawable.ic_notification)
            it.add(0, Menu.FIRST + 8, Menu.NONE, R.string.menu_sync).setIcon(R.drawable.ic_sync)
            it.add(0, Menu.FIRST + 9, Menu.NONE, R.string.menu_exit).setIcon(R.drawable.ic_exit)
        }
        navMenuEl.setNavigationItemSelectedListener {
            drawerEl.closeDrawer(navMenuEl)
            if (it.itemId == Menu.FIRST + 0) navigation.go("/dm", listOf())
            if (it.itemId == Menu.FIRST + 1) navigation.go("/settings", listOf())
            if (it.itemId == Menu.FIRST + 2) navigation.go("/log", listOf())
            if (it.itemId == Menu.FIRST + 3) navigation.go("/upload", listOf())
            if (it.itemId == Menu.FIRST + 4) navigation.go("/open", listOf())
            if (it.itemId == Menu.FIRST + 5) navigation.go("/download", listOf())
            if (it.itemId == Menu.FIRST + 6) navigation.go("/commands", listOf())
            if (it.itemId == Menu.FIRST + 7) navigation.go("/notifications", listOf())
            if (it.itemId == Menu.FIRST + 8) navigation.go("/sync", listOf())
            if (it.itemId == Menu.FIRST + 9) exitDialog()
            return@setNavigationItemSelectedListener true
        }
        navigation.start()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerEl.isDrawerOpen(GravityCompat.START)) {
                    drawerEl.closeDrawer(GravityCompat.START)
                } else {
                    Log.d(TAG, "BACK PRESSED")
                    navigation.stack.forEach {
                        Log.d(TAG, "Navigation: $it")
                    }
                    if (!navigation.back()) {
                        Log.d(TAG, "FINISH IT")
                        finish()
                    }
                }
            }
        })
    }

    /**
     * Restart app using alarm
     */
    fun restartApp(): Boolean {
        val intent = Intent(this, MainActivity::class.java)
        val mPendingIntent = PendingIntent.getActivity(this, CODE_RESTART, intent, PendingIntent.FLAG_CANCEL_CURRENT)
        val mgr = this.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, mPendingIntent)
        stopApp()
        return true
    }

    /**
     * Stop application
     */
    fun stopApp(): Boolean {
        APP.log("Exit process by user command")
        APP.dumpLogs()
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
            setNegativeButton(R.string.restart) { _, _ -> restartApp() }
            setPositiveButton(R.string.stop) { _, _ -> stopApp() }
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
                1001.also { id = it }
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

    /**
     * Extract optional navigation extra from intent and go to target
     */
    private fun processNavIntent(intent: Intent?) {
        intent?.getStringExtra("goto")?.also {
            Log.d(TAG, "Intent contains navigation extra: goto $it")
            navigation.go(it)
        }
    }

//    fun bundleToString(bundle: Bundle?): String? {
//        val out = StringBuilder("Bundle[")
//        if (bundle == null) {
//            out.append("null")
//        } else {
//            var first = true
//            for (key in bundle.keySet()) {
//                if (!first) {
//                    out.append(", ")
//                }
//                out.append(key).append('=')
//                val value = bundle[key]
//                if (value is IntArray) {
//                    out.append(Arrays.toString(value as IntArray?))
//                } else if (value is ByteArray) {
//                    out.append(Arrays.toString(value as ByteArray?))
//                } else if (value is BooleanArray) {
//                    out.append(Arrays.toString(value as BooleanArray?))
//                } else if (value is ShortArray) {
//                    out.append(Arrays.toString(value as ShortArray?))
//                } else if (value is LongArray) {
//                    out.append(Arrays.toString(value as LongArray?))
//                } else if (value is FloatArray) {
//                    out.append(Arrays.toString(value as FloatArray?))
//                } else if (value is DoubleArray) {
//                    out.append(Arrays.toString(value as DoubleArray?))
//                } else if (value is Bundle) {
//                    out.append(bundleToString(value as Bundle?))
//                } else {
//                    out.append(value)
//                }
//                first = false
//            }
//        }
//        out.append("]")
//        return out.toString()
//    }

    fun handleStartIntent() {
//        Log.d(TAG, "===================================================")
//        /*
//        Note: If the launch mode of the designated activity is "standard",
//        it too is removed from the stack and a new instance is launched in
//         its place to handle the incoming intent.
//         That's because a new instance is always created for a new intent when the launch mode is "standard".
//         */
//        Log.d(TAG, "Intent flags: ${intent.flags} (${intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP}, ${intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP})")
//        Log.d(TAG, bundleToString(intent?.extras).toString())
//        Log.d(TAG, "===================================================")
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            // Just save more than one file
            return navigation.go("/upload", listOf(intent, randId()), createNew = true)
        }
        if (intent?.action == Intent.ACTION_SEND) {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            val title = intent.getStringExtra(Intent.EXTRA_TITLE)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            // Check if intent contains web URL and go to opener
            text?.also {
                if (it.startsWith("http://") or it.startsWith("https://")) {
                    return navigation.go("/open", listOf(intent, it, 0, randId()), createNew = true)
                }
            }
            // Check user settings for single shared file
            val titleStr = simplifyFilename(title ?: subject ?: text ?: getString(R.string.file))
            when (APP.conf.actionForSharedFile.value) {
                "open" -> return navigation.go(
                    "/open",
                    listOf(intent, titleStr, 0, randId()),
                    createNew = true
                )
                "upload" -> return navigation.go(
                    "/upload",
                    listOf(intent, titleStr, 0, randId()),
                    createNew = true
                )
                else -> {
                    // Show select dialog on other options
                    val options = mutableListOf(
                        Option(getString(R.string.menu_open), "/open"),
                        Option(getString(R.string.menu_upload), "/upload")
                    )
                    SelectInputView.showListDialog(this, titleStr, options) { _, s ->
                        navigation.go(
                            s.value.toString(),
                            listOf(intent, titleStr, 1, randId()),
                            createNew = true
                        )
                    }
                }
            }
        }
        processNavIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        processNavIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        APP.activity = this
        setContentView(createUI(this))
        navigation = Navigation(toolbarEl, supportFragmentManager, fragmentEl.id)
        initUI()
        handleStartIntent()
    }

    override fun onResume() {
        super.onResume()
        APP.activity = this
    }

    override fun onDestroy() {
        super.onDestroy()
        APP.activity = null
    }
}
