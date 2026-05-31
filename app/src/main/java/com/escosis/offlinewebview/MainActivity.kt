package com.escosis.offlinewebview

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.File
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.view.ViewTreeObserver
import android.widget.ProgressBar
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity(), DebugLogger {

    // GeckoView 相关
    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var geckoRuntime: GeckoRuntime
    private var localWebServer: LocalWebServer? = null
    private var rootUri: Uri? = null
    private var isServerStarted = false

    // 新增：Zip 解压模式
    private var isZipMode = false               // 当前是否为 zip 解压模式
    private lateinit var unzippedDir: File      // 私有解压目录
    private var currentServerRoot: Any? = null  // Uri 或 File

    // 导航状态
    private var canGoBack = false
    private var canGoForward = false

    // UI 组件
    private lateinit var rootFrame: FrameLayout
    private lateinit var urlBar: LinearLayout
    private lateinit var urlEditText: EditText
    private lateinit var goButton: ImageButton
    private lateinit var toolbar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var selectDirButton: ImageButton
    private lateinit var selectFileButton: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var floatingBall: ImageView

    // 调试相关
    private lateinit var debugPanel: LinearLayout
    private lateinit var debugLogTextView: TextView
    private var isDebugEnabled = true

    // 占位视图
    private lateinit var placeholderView: FrameLayout
    private lateinit var errorView: FrameLayout
    private lateinit var errorTextView: TextView
    private var lastRequestedUrl: String? = null

    // 夜间模式
    private lateinit var prefs: SharedPreferences
    private var isNightMode = false

    // 横屏自动隐藏 + 动画
    private var isBarVisible = true
    private val hideHandler = Handler(Looper.getMainLooper())
    private lateinit var hideRunnable: Runnable
    private var urlBarHeight = 0
    private var toolbarHeight = 0
    private var isAnimating = false
    private val animationDuration = 200L

    // 系统状态栏自动隐藏计时器
    private val statusBarHideHandler = Handler(Looper.getMainLooper())
    private lateinit var statusBarHideRunnable: Runnable

    // 引导相关
    private var isGuideRunning = false
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var isGuideActive = false

    // 自动隐藏开关
    private lateinit var autoHidePrefs: SharedPreferences
    private var isAutoHideEnabled = false

    // 次级菜单 PopupWindow 引用
    private var overflowPopup: PopupWindow? = null

    private var isOrientationAllowed = true
    private lateinit var orientationPrefs: SharedPreferences
    private var currentPopupView: LinearLayout? = null
    private var currentSessionUrl: String = ""

    private lateinit var instancesLayer: FrameLayout
    private var isInstancesLayerVisible = false
    private lateinit var instancesRecyclerView: RecyclerView
    private lateinit var instanceAdapter: InstanceAdapter
    private lateinit var currentInstanceText: TextView
    private var currentInstanceId: String? = null
    private lateinit var instancesButton: ImageButton
    private lateinit var instancesLayerContent: LinearLayout
    private lateinit var saveInstanceButton: ImageButton
    private lateinit var instancesTopBar: LinearLayout
    private var isSaving = false
    private var copyJob: Job? = null
    private var copyProgressDialog: ProgressDialog? = null
    private var isCopyCancelled = false

    private var pendingReferenceInstance: Instance? = null
    private var currentInstanceRootDir: File? = null

    private var currentFileBrowserDialog: AlertDialog? = null
    private var currentBrowserRoot: File? = null
    private var currentBrowserCurrentDir: File? = null

    private var isCurrentInstanceSaved = false

    // 文件类型处理策略接口
    interface FileOpenStrategy {
        fun open(file: File, relativePath: String)
    }

    // HTML 文件的处理策略
    class HtmlFileOpenStrategy(private val activity: MainActivity) : FileOpenStrategy {
        override fun open(file: File, relativePath: String) {
            activity.loadUrl("http://localhost:8080/$relativePath")
            activity.currentFileBrowserDialog?.dismiss()
        }
    }

    private val reauthorizeFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val newUri = result.data?.data
            if (newUri != null && pendingReferenceInstance != null) {
                val updated = InstanceManager.updateInstanceSourceUri(pendingReferenceInstance!!.id, newUri.toString())
                if (updated) {
                    log("重新授权成功，更新实例: ${pendingReferenceInstance!!.name}")
                    loadInstance(pendingReferenceInstance!!)
                } else {
                    Toast.makeText(this, "更新实例失败", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "授权取消或失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("授权失败")
                .setMessage("无法访问原文件夹，是否删除该实例？")
                .setPositiveButton("删除") { _, _ ->
                    pendingReferenceInstance?.let {
                        InstanceManager.deleteInstance(it.id, deleteFiles = false)
                        refreshInstanceList()
                    }
                    Toast.makeText(this, "已删除实例", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("保留", null)
                .show()
        }
        pendingReferenceInstance = null
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_GUIDE_SHOWN = "guide_shown"
        private const val KEY_LANDSCAPE_GUIDE_SHOWN = "landscape_guide_shown"
        private const val AUTO_HIDE_PREFS = "auto_hide_prefs"
        private const val KEY_AUTO_HIDE_ENABLED = "auto_hide_enabled"
        private const val ORIENTATION_PREFS = "orientation_prefs"
        private const val KEY_ORIENTATION_ALLOWED = "orientation_allowed"
        private const val KEY_SELECT_MODE = "select_mode"
    }

    // 调试日志缓冲区
    private val logBuffer = mutableListOf<String>()
    private val maxLogLines = 500

    // 选择模式枚举
    enum class SelectMode {
        FOLDER,  // 外部文件夹模式（默认）
        ZIP      // ZIP 解压模式
    }

    private var currentSelectMode: SelectMode = SelectMode.FOLDER
    private lateinit var selectModePrefs: SharedPreferences

    override fun log(message: String) {
        if (!isDebugEnabled) return
        val isPanelVisible = ::debugPanel.isInitialized && debugPanel.visibility == View.VISIBLE
        if (!isPanelVisible) {
            android.util.Log.d("MainActivity", message)
            return
        }
        runOnUiThread {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())
            val formatted = "[$timestamp] $message"
            logBuffer.add(formatted)
            if (logBuffer.size > maxLogLines) {
                logBuffer.removeAt(0)
            }
            debugLogTextView.text = logBuffer.joinToString("\n")
            (debugLogTextView.parent as? android.widget.ScrollView)?.post {
                (debugLogTextView.parent as? android.widget.ScrollView)?.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
            }
            android.util.Log.d("MainActivity", message)
        }
    }

    // 文件夹选择器（原有）
    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                log("已选择文件夹: $uri")
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                rootUri = uri
                startServer()
                updateUIAfterDirSelected()
                loadUrl("http://localhost:8080/")
                Toast.makeText(this, "服务器已启动", Toast.LENGTH_SHORT).show()
            } else {
                log("未选择文件夹")
                Toast.makeText(this, "未选择文件夹", Toast.LENGTH_SHORT).show()
            }
        } else {
            log("选择文件夹取消或失败")
        }
    }

    // 文件选择器（原有，用于文件夹模式）
    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val fileUri = result.data?.data
            if (fileUri != null && rootUri != null) {
                log("已选择文件: $fileUri")
                if (isFileUnderRoot(fileUri, rootUri!!)) {
                    val relativePath = getRelativePath(fileUri, rootUri!!) ?: ""
                    val url = "http://localhost:8080/$relativePath"
                    log("加载文件: $url")
                    loadUrl(url)
                } else {
                    log("文件不在授权文件夹内: $fileUri")
                    Toast.makeText(this, "文件不在授权文件夹内", Toast.LENGTH_LONG).show()
                }
            } else {
                log("未选择文件")
                Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show()
            }
        } else {
            log("选择文件取消或失败")
        }
    }

    // 新增：ZIP 包选择器
    private val zipPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val zipUri = result.data?.data ?: return@registerForActivityResult
            log("已选择 ZIP: $zipUri")
            extractZipInBackground(zipUri)
        } else {
            log("取消选择 ZIP")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideRunnable = Runnable {
            if (isBarVisible && isAutoHideEnabled) {
                hideBars()
            }
        }

        statusBarHideRunnable = Runnable {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                hideSystemBars()
            }
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isNightMode = prefs.getBoolean("night_mode", false)

        autoHidePrefs = getSharedPreferences(AUTO_HIDE_PREFS, Context.MODE_PRIVATE)
        isAutoHideEnabled = autoHidePrefs.getBoolean(KEY_AUTO_HIDE_ENABLED, false)

        orientationPrefs = getSharedPreferences(ORIENTATION_PREFS, Context.MODE_PRIVATE)
        isOrientationAllowed = orientationPrefs.getBoolean(KEY_ORIENTATION_ALLOWED, true)
        if (!isOrientationAllowed) {
            lockCurrentOrientation()
        }

        initZipMode()
        initViews()
        setupGeckoView()
        setupListeners()
        InstanceManager.init(this)
        InstanceManager.logger = { message -> log(message) }
        initInstancesUI()
        applyNightMode()
        updateUIAfterDirSelected()
        setupDebugPanel()
        setupMenuButton()

        rootFrame.post {
            measureOriginalHeights()
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                hideSystemBars()
                if (isBarVisible && isAutoHideEnabled) {
                    hideBars()
                } else if (!isBarVisible) {
                    floatingBall.visibility = View.VISIBLE
                }
            } else {
                showSystemBars()
                showBars()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    statusBarHideHandler.removeCallbacks(statusBarHideRunnable)
                    statusBarHideHandler.postDelayed(statusBarHideRunnable, 3000)
                } else {
                    statusBarHideHandler.removeCallbacks(statusBarHideRunnable)
                }
            }
        }

        if (!isGuideShown()) {
            window.decorView.post {
                if (!isGuideShown() && !isGuideRunning) {
                    startUserGuide()
                }
            }
        }
        selectModePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMode = selectModePrefs.getString(KEY_SELECT_MODE, SelectMode.FOLDER.name) ?: SelectMode.FOLDER.name
        currentSelectMode = SelectMode.valueOf(savedMode)
        updateSelectModeIcon()
        updateUIForCurrentMode()  // 根据模式更新UI（提示文字等）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isInstancesLayerVisible) {
                    hideInstancesLayer()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun initZipMode() {
        val instancesRoot = File(filesDir, "instances")
        if (!instancesRoot.exists()) {
            instancesRoot.mkdirs()
        }
        unzippedDir = File(instancesRoot, "temp_www")
        if (!unzippedDir.exists()) {
            unzippedDir.mkdirs()
        }
    }

    private fun setupDebugPanel() {
        debugPanel = findViewById(R.id.debugPanel)
        debugLogTextView = findViewById(R.id.debugLogTextView)
        val clearButton = findViewById<ImageButton>(R.id.clearDebugLogButton)
        val closeButton = findViewById<ImageButton>(R.id.closeDebugPanelButton)

        clearButton.setOnClickListener {
            logBuffer.clear()
            debugLogTextView.text = ""
            log("调试日志已清空")
        }
        closeButton.setOnClickListener {
            debugPanel.visibility = View.GONE
            log("调试面板已关闭")
        }
        if (isDebugEnabled) {
            log("调试窗口已初始化")
        }
    }

    private fun setupMenuButton() {
        menuButton.setOnClickListener {
            showOverflowMenu()
        }
    }

    private fun dismissOverflowMenu() {
        overflowPopup?.dismiss()
        overflowPopup = null
        currentPopupView = null
        resetHideTimer()
    }

    private fun showOverflowMenu() {
        if (isAnimating || !isBarVisible || urlBar.height == 0 || toolbar.height == 0) {
            return
        }

        dismissOverflowMenu()

        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(if (isNightMode) Color.BLACK else Color.WHITE)
            elevation = 8f
        }
        currentPopupView = popupView

        fun getIconColor(enabled: Boolean): Int {
            return if (enabled) Color.rgb(0, 75, 171) else if (isNightMode) Color.WHITE else Color.DKGRAY
        }

        fun createOptionItem(iconRes: Int, text: String, isEnabled: Boolean = false, isToggle: Boolean = false, onToggle: (() -> Unit)? = null): View {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8))
                isClickable = true
                isFocusable = true
                background = null
            }

            val imageView = ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(getIconColor(isEnabled))
            }
            val textView = TextView(this).apply {
                this.text = text
                textSize = 12f
                val initialEnabled = when (text) {
                    "夜间模式" -> this@MainActivity.isNightMode
                    "自动隐藏" -> isAutoHideEnabled
                    "允许旋转" -> isOrientationAllowed
                    else -> false
                }
                val textColor = if (initialEnabled) {
                    Color.rgb(0, 75, 171)
                } else {
                    if (isNightMode) Color.WHITE else Color.DKGRAY
                }
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(4) }
            }

            itemLayout.addView(imageView)
            itemLayout.addView(textView)

            if (onToggle != null) {
                itemLayout.setOnClickListener {
                    onToggle?.invoke()
                    val newEnabled = when (text) {
                        "夜间模式" -> this@MainActivity.isNightMode
                        "自动隐藏" -> isAutoHideEnabled
                        "允许旋转" -> isOrientationAllowed
                        else -> false
                    }
                    imageView.setColorFilter(getIconColor(newEnabled))
                    val newTextColor = if (newEnabled) {
                        Color.rgb(0, 75, 171)
                    } else {
                        if (isNightMode) Color.WHITE else Color.DKGRAY
                    }
                    textView.setTextColor(newTextColor)
                    resetHideTimer()
                }
            }
            return itemLayout
        }

        // 夜间模式
        val nightItem = createOptionItem(
            R.drawable.baseline_dark_mode_24, "夜间模式",
            isEnabled = isNightMode,
            isToggle = true
        ) {
            isNightMode = !isNightMode
            applyNightMode()
            prefs.edit().putBoolean("night_mode", isNightMode).apply()
            currentPopupView?.setBackgroundColor(if (isNightMode) Color.BLACK else Color.WHITE)
            for (i in 0 until (currentPopupView?.childCount ?: 0)) {
                val child = currentPopupView?.getChildAt(i) as? LinearLayout
                val img = child?.getChildAt(0) as? ImageView
                val txt = child?.getChildAt(1) as? TextView
                when (child?.getChildAt(0)?.tag) {
                    "night" -> img?.setColorFilter(getIconColor(isNightMode))
                    "auto_hide" -> img?.setColorFilter(getIconColor(isAutoHideEnabled))
                    "rotate" -> img?.setColorFilter(getIconColor(isOrientationAllowed))
                    else -> img?.setColorFilter(if (isNightMode) Color.WHITE else Color.DKGRAY)
                }
                val textColor = when (child?.getChildAt(0)?.tag) {
                    "night" -> if (isNightMode) Color.rgb(0, 75, 171) else if (isNightMode) Color.WHITE else Color.DKGRAY
                    "auto_hide" -> if (isAutoHideEnabled) Color.rgb(0, 75, 171) else if (isNightMode) Color.WHITE else Color.DKGRAY
                    "rotate" -> if (isOrientationAllowed) Color.rgb(0, 75, 171) else if (isNightMode) Color.WHITE else Color.DKGRAY
                    else -> if (isNightMode) Color.WHITE else Color.DKGRAY
                }
                txt?.setTextColor(textColor)
            }
            log("夜间模式: ${if (isNightMode) "开启" else "关闭"}")
        }
        (nightItem as? LinearLayout)?.getChildAt(0)?.tag = "night"
        popupView.addView(nightItem)

        // 调试面板
        val debugItem = createOptionItem(
            R.drawable.baseline_bug_report_24, "调试面板",
            isEnabled = false,
            isToggle = false
        ) {
            if (debugPanel.visibility == View.VISIBLE) {
                debugPanel.visibility = View.GONE
                log("调试面板已关闭")
            } else {
                debugPanel.visibility = View.VISIBLE
                log("调试面板已打开")
            }
        }
        popupView.addView(debugItem)

        // 自动隐藏
        val autoHideItem = createOptionItem(
            R.drawable.baseline_visibility_off_24, "自动隐藏",
            isEnabled = isAutoHideEnabled,
            isToggle = true
        ) {
            isAutoHideEnabled = !isAutoHideEnabled
            autoHidePrefs.edit().putBoolean(KEY_AUTO_HIDE_ENABLED, isAutoHideEnabled).apply()
            log("工具栏自动隐藏: ${if (isAutoHideEnabled) "开启" else "关闭"}")
            if (!isAutoHideEnabled && !isBarVisible) showBars()
            if (isAutoHideEnabled && isBarVisible) resetHideTimer()
        }
        (autoHideItem as? LinearLayout)?.getChildAt(0)?.tag = "auto_hide"
        popupView.addView(autoHideItem)

        // 允许旋转
        val rotateItem = createOptionItem(
            R.drawable.mobile_rotate_24, "允许旋转",
            isEnabled = isOrientationAllowed,
            isToggle = true
        ) {
            isOrientationAllowed = !isOrientationAllowed
            orientationPrefs.edit().putBoolean(KEY_ORIENTATION_ALLOWED, isOrientationAllowed).apply()
            if (isOrientationAllowed) unlockOrientation() else lockCurrentOrientation()
            log("允许旋转: ${if (isOrientationAllowed) "开启" else "关闭"}")
        }
        (rotateItem as? LinearLayout)?.getChildAt(0)?.tag = "rotate"
        popupView.addView(rotateItem)

        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 0f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val location = IntArray(2)
        toolbar.getLocationOnScreen(location)
        val toolbarTop = location[1]
        val menuHeight = popupView.measuredHeight
        val menuTop = toolbarTop - menuHeight
        popup.showAtLocation(toolbar, Gravity.NO_GRAVITY, 0, menuTop)
        overflowPopup = popup
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dismissOverflowMenu()

        if (isGuideActive) {
            forceShowBarsForGuide()
            return
        }
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                hideSystemBars()
                if (isBarVisible && isAutoHideEnabled) {
                    hideBars()
                } else if (!isBarVisible) {
                    floatingBall.visibility = View.VISIBLE
                }
                hideHandler.removeCallbacks(hideRunnable)
            }
            Configuration.ORIENTATION_PORTRAIT -> {
                showSystemBars()
                showBars()
                hideHandler.removeCallbacks(hideRunnable)
            }
        }

        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && !isGuideActive) {
            setStatusBarColor(Color.TRANSPARENT)
        } else {
            if (isNightMode) {
                setStatusBarColor(Color.parseColor("#333333"))
            } else {
                setStatusBarColor(Color.parseColor("#F5F5F5"))
            }
        }
    }

    private fun initViews() {
        rootFrame = findViewById(R.id.rootFrame)
        urlBar = findViewById(R.id.urlBar)
        urlEditText = findViewById(R.id.urlEditText)
        goButton = findViewById(R.id.goButton)
        toolbar = findViewById(R.id.toolbar)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        selectDirButton = findViewById(R.id.selectDirButton)
        selectFileButton = findViewById(R.id.selectFileButton)
        menuButton = findViewById(R.id.menuButton)
        floatingBall = findViewById(R.id.floatingBall)
        placeholderView = findViewById(R.id.placeholderView)
        floatingBall.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        errorView = findViewById(R.id.errorView)
        errorTextView = findViewById(R.id.errorTextView)
        instancesLayer = findViewById(R.id.instancesLayer)
        instancesButton = findViewById(R.id.instancesButton)
    }

    private fun measureOriginalHeights() {
        urlBarHeight = urlBar.height
        toolbarHeight = toolbar.height
        if (urlBarHeight == 0) {
            urlBar.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            toolbar.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            urlBarHeight = urlBar.measuredHeight
            toolbarHeight = toolbar.measuredHeight
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !isBarVisible && isAutoHideEnabled) {
            urlBar.layoutParams.height = 0
            toolbar.layoutParams.height = 0
            urlBar.requestLayout()
            toolbar.requestLayout()
        }
    }

    private fun setupGeckoView() {
        geckoView = findViewById(R.id.geckoview)
        geckoRuntime = GeckoRuntime.create(this)
        geckoSession = GeckoSession()
        geckoSession.open(geckoRuntime)
        geckoView.setSession(geckoSession)
        geckoSession.settings.setAllowJavascript(true)

        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
                runOnUiThread { updateNavigationButtonsState() }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                this@MainActivity.canGoForward = canGoForward
                runOnUiThread { updateNavigationButtonsState() }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                log("导航请求: $uri")
                if (uri.startsWith("error://")) {
                    log("拦截错误页面: $uri")
                    runOnUiThread {
                        showErrorView(uri)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                if (uri.startsWith("http://localhost:8080/")) {
                    lastRequestedUrl = uri
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                log("新窗口请求: $uri")
                return null
            }
        }

        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                currentSessionUrl = url
                runOnUiThread {
                    updateUrlBar(url)
                    if (errorView.visibility == View.VISIBLE) {
                        hideErrorView()
                    }
                }
            }
        }

        urlEditText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                goButton.performClick()
                true
            } else false
        }

        goButton.setOnClickListener {
            val currentIcon = goButton.drawable?.constantState
            val refreshIcon = ContextCompat.getDrawable(this, R.drawable.baseline_refresh_24)?.constantState
            if (currentIcon == refreshIcon) {
                // 刷新模式
                if (errorView.visibility == View.VISIBLE && lastRequestedUrl != null) {
                    log("刷新: 重新加载错误页面 $lastRequestedUrl")
                    loadUrl(lastRequestedUrl!!)
                } else {
                    log("刷新页面")
                    geckoSession.reload()
                }
            } else {
                // 前往模式
                val input = urlEditText.text.toString().trim()
                if (input.isNotEmpty()) {
                    log("地址栏输入: $input")
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(urlEditText.windowToken, 0)
                    Handler(Looper.getMainLooper()).postDelayed({
                        loadUserInputUrl(input)
                    }, 200)
                }
            }
            resetHideTimer()
        }
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            if (canGoBack) {
                log("执行后退")
                geckoSession.goBack()
            }
            resetHideTimer()
        }
        forwardButton.setOnClickListener {
            if (canGoForward) {
                log("执行前进")
                geckoSession.goForward()
            }
            resetHideTimer()
        }
        selectDirButton.setOnClickListener {
            if (isServerStarted) {
                // 服务器运行中，询问是否停止
                AlertDialog.Builder(this)
                    .setTitle("停止服务器")
                    .setMessage("当前服务器正在运行，是否停止？")
                    .setPositiveButton("停止") { _, _ ->
                        stopServerAndReset()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                // 根据当前模式执行相应操作
                when (currentSelectMode) {
                    SelectMode.FOLDER -> openFolderPicker()
                    SelectMode.ZIP -> chooseZipAndExtract()
                }
            }
            resetHideTimer()
        }

        selectDirButton.setOnLongClickListener {
            if (isServerStarted) {
                AlertDialog.Builder(this)
                    .setTitle("无法切换模式")
                    .setMessage("服务器正在运行，无法切换模式。请先停止服务器。")
                    .setPositiveButton("了解", null)
                    .show()
            } else {
                switchSelectMode()
            }
            true
        }

        urlEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                hideHandler.removeCallbacks(hideRunnable)
            } else {
                val inputText = urlEditText.text.toString()
                if (inputText.isNotEmpty() && inputText != currentSessionUrl && currentSessionUrl.isNotEmpty()) {
                    urlEditText.setText(currentSessionUrl)
                    urlEditText.setSelection(currentSessionUrl.length)
                }
                resetHideTimer()
            }
            updateActionButtonState()
        }

        urlEditText.setOnTouchListener { _, _ ->
            resetHideTimer()
            false
        }

        floatingBall.setOnClickListener {
            if (!isBarVisible) {
                showBars()
            }
        }

        instancesButton.setOnClickListener {
            if (isInstancesLayerVisible) {
                hideInstancesLayer()
            } else {
                showInstancesLayer()
            }
            resetHideTimer()
        }
    }

    private fun updateNavigationButtonsState() {
        backButton.isEnabled = isServerStarted && canGoBack
        forwardButton.isEnabled = isServerStarted && canGoForward
        goButton.isEnabled = isServerStarted
        applyNightMode()
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, getDownloadsUri())
        }
        selectFolderLauncher.launch(intent)
    }

    private fun openFilePickerInRoot() {
        if (rootUri == null) {
            Toast.makeText(this, "请先选择根文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootUri)
        }
        selectFileLauncher.launch(intent)
    }

    // 新增：选择 ZIP 包
    private fun chooseZipAndExtract() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            val downloadsUri = getDownloadsUri()
            if (downloadsUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
            }
        }
        zipPickerLauncher.launch(intent)
    }

    // 后台解压 ZIP
    private fun extractZipInBackground(zipUri: Uri) {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("正在解压，请稍候...")
            setCancelable(false)
            show()
        }

        Thread {
            var zipStream: ZipArchiveInputStream? = null
            try {
                cleanupUnzippedDir()
                unzippedDir.mkdirs()

                val inputStream = contentResolver.openInputStream(zipUri)
                    ?: throw Exception("无法打开 ZIP 文件流")

                // 定义尝试的编码列表：优先 GBK（中文 Windows 常用），其次 UTF-8
                val encodings = listOf("GBK", "UTF-8")

                var lastException: Exception? = null
                var success = false

                for (encodingName in encodings) {
                    if (success) break
                    try {
                        // 重新打开流（因为一次解压会消耗流）
                        val freshStream = contentResolver.openInputStream(zipUri)
                            ?: throw Exception("重新打开文件流失败")
                        zipStream = ZipArchiveInputStream(freshStream, encodingName, true)

                        var entry = zipStream.nextZipEntry
                        while (entry != null) {
                            val entryName = entry.name.replace('\\', '/')
                            val targetFile = File(unzippedDir, entryName)

                            if (entry.isDirectory) {
                                targetFile.mkdirs()
                            } else {
                                targetFile.parentFile?.mkdirs()
                                targetFile.outputStream().use { output ->
                                    zipStream.copyTo(output)  // 流式复制，不占额外内存
                                }
                            }
                            entry = zipStream.nextZipEntry
                        }
                        success = true
                        log("解压成功，编码：$encodingName")
                    } catch (e: Exception) {
                        lastException = e
                        log("使用编码 $encodingName 解压失败：${e.message}")
                        // 清理已解压的内容，尝试下一种编码
                        cleanupUnzippedDir()
                        unzippedDir.mkdirs()
                    } finally {
                        zipStream?.close()
                    }
                }

                if (!success) {
                    throw lastException ?: Exception("所有编码尝试均失败")
                }

                runOnUiThread {
                    progressDialog.dismiss()
                    startServerFromFile(unzippedDir)
                }
            } catch (e: Exception) {
                log("解压失败: ${e.message}")
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "解压失败: ${e.message}", Toast.LENGTH_LONG).show()
                    cleanupUnzippedDir()
                }
            }
        }.start()
    }

    // 从 File 根目录启动服务器（ZIP 解压模式）
    private fun startServerFromFile(rootFile: File) {
        if (!rootFile.exists()) {
            log("根目录不存在: ${rootFile.absolutePath}")
            return
        }
        localWebServer?.stop()
        localWebServer = LocalWebServer(8080, rootFile = rootFile, context = this, debugLogger = this)
        try {
            localWebServer?.start()
            isServerStarted = true
            isZipMode = true   // 使用私有目录文件选择器
            currentServerRoot = rootFile
            currentInstanceRootDir = rootFile   // 记录当前实例根目录
            isCurrentInstanceSaved = false   // 新增
            updateDeleteAndSaveButtonsState() // 新增
            geckoSession.purgeHistory()
            log("服务器已启动，根目录: ${rootFile.absolutePath}")
            runOnUiThread {
                updateUIAfterDirSelected()
                loadUrl("http://localhost:8080/")
            }
        } catch (e: Exception) {
            log("服务器启动失败: ${e.message}")
            isServerStarted = false
        }
    }

    // 原有基于 Uri 的服务器启动（文件夹模式）
    private fun startServer() {
        if (rootUri == null) return
        localWebServer?.stop()
        localWebServer = LocalWebServer(8080, rootUri = rootUri, context = this, debugLogger = this)
        try {
            localWebServer?.start()
            isServerStarted = true
            isZipMode = false   // 文件夹模式
            currentServerRoot = rootUri
            isCurrentInstanceSaved = false
            updateDeleteAndSaveButtonsState()
            geckoSession.purgeHistory()
            log("服务器启动成功，端口 8080")
            runOnUiThread {
                updateUIAfterDirSelected()
                loadUrl("http://localhost:8080/")
                Toast.makeText(this, "服务器已启动", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            log("服务器启动失败: ${e.message}")
            isServerStarted = false
        }
    }

    private fun updateUIAfterDirSelected() {
        val enabled = isServerStarted
        selectFileButton.isEnabled = enabled
        selectFileButton.alpha = if (enabled) 1.0f else 0.4f
        urlEditText.isEnabled = enabled
        if (enabled) {
            urlEditText.setText("http://localhost:8080/")
            urlEditText.hint = ""
            placeholderView.visibility = View.GONE
            hideErrorView()
            if (isZipMode) {
                selectFileButton.setOnClickListener {
                    showFilePickerForPrivateDir()
                }
            } else {
                selectFileButton.setOnClickListener {
                    openFilePickerInRoot()
                }
            }
        } else {
            urlEditText.setText("")
            urlEditText.hint = if (isZipMode) "请先选择 ZIP 包" else "请先选择服务器根目录"
            urlEditText.isEnabled = false
            placeholderView.visibility = View.VISIBLE
        }
        applyNightMode()
    }

    // ZIP 解压模式下文件选择
    private fun showFilePickerForPrivateDir() {
        val serverRoot = currentInstanceRootDir ?: unzippedDir
        if (!serverRoot.exists()) {
            Toast.makeText(this, "当前服务器根目录不存在", Toast.LENGTH_SHORT).show()
            return
        }
        showFileBrowser(serverRoot)
    }

    private fun loadUserInputUrl(input: String) {
        var url = input.trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.startsWith("/")) "http://localhost:8080$url"
            else "http://localhost:8080/$url"
        }
        if (url.startsWith("http://localhost:8080/") && (rootUri != null || (isZipMode && currentServerRoot != null))) {
            log("加载用户输入: $url")
            loadUrl(url)
        } else {
            log("拒绝加载非本地地址: $url")
            Toast.makeText(this, "仅允许访问本地服务器内容", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUrl(url: String) {
        log("加载 URL: $url")
        geckoSession.loadUri(url)
        updateUrlBar(url)
    }

    private fun updateUrlBar(url: String) {
        urlEditText.setText(url)
        urlEditText.setSelection(url.length)
        updateActionButtonState()
    }

    private fun isFileUnderRoot(fileUri: Uri, rootUri: Uri): Boolean {
        val rootId = getDocumentId(rootUri)
        val fileId = getDocumentId(fileUri)
        return rootId != null && fileId != null && (fileId == rootId || fileId.startsWith("$rootId/"))
    }

    private fun getRelativePath(fileUri: Uri, rootUri: Uri): String? {
        val rootId = getDocumentId(rootUri) ?: return null
        val fileId = getDocumentId(fileUri) ?: return null
        if (!fileId.startsWith("$rootId/") && fileId != rootId) return null
        return if (fileId == rootId) "" else fileId.substring(rootId.length + 1)
    }

    private fun getDocumentId(uri: Uri): String? {
        return try {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        } catch (e: Exception) {
            log("获取 DocumentId 失败: ${e.message}")
            null
        }
    }

    private fun getDownloadsUri(): Uri? {
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Download"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        statusBarHideHandler.removeCallbacks(statusBarHideRunnable)
        localWebServer?.stop()
        geckoSession.close()
        dismissOverflowMenu()
        cleanupUnzippedDir()
        copyJob?.cancel()
        copyProgressDialog?.dismiss()
        currentFileBrowserDialog?.dismiss()
    }

    private fun setStatusBarColor(color: Int) {
        window.statusBarColor = color
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNightMode
    }

    private fun setNavigationBarColor(color: Int) {
        window.navigationBarColor = color
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightNavigationBars = !isNightMode
    }

    private fun setIconColor(button: ImageButton, color: Int) {
        val drawable = button.drawable
        drawable?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        button.setImageDrawable(drawable)
    }

    private fun applyNightMode() {
        val normalIconColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        val disabledColor = if (isNightMode) Color.DKGRAY else Color.LTGRAY

        if (isNightMode) {
            rootFrame.setBackgroundColor(Color.BLACK)
            toolbar.setBackgroundColor(Color.BLACK)
            urlBar.setBackgroundColor(Color.BLACK)
            urlEditText.setBackgroundColor(Color.BLACK)
            urlEditText.setTextColor(Color.WHITE)
            urlEditText.setHintTextColor(Color.LTGRAY)
            setStatusBarColor(Color.parseColor("#333333"))
            setNavigationBarColor(Color.BLACK)
        } else {
            rootFrame.setBackgroundColor(Color.WHITE)
            toolbar.setBackgroundColor(Color.WHITE)
            urlBar.setBackgroundColor(Color.WHITE)
            urlEditText.setBackgroundColor(Color.WHITE)
            urlEditText.setTextColor(Color.BLACK)
            urlEditText.setHintTextColor(Color.GRAY)
            setStatusBarColor(Color.parseColor("#F5F5F5"))
            setNavigationBarColor(Color.WHITE)
        }

        setIconColor(backButton, if (backButton.isEnabled) normalIconColor else disabledColor)
        setIconColor(forwardButton, if (forwardButton.isEnabled) normalIconColor else disabledColor)
        setIconColor(goButton, if (goButton.isEnabled) normalIconColor else disabledColor)
        setIconColor(selectFileButton, if (selectFileButton.isEnabled) normalIconColor else disabledColor)
        setIconColor(instancesButton, normalIconColor)

        // 目录按钮：服务器已启动时显示钴蓝色（无论 ZIP 解压模式还是普通模式）
        if (isServerStarted) {
            selectDirButton.setColorFilter(Color.rgb(0, 75, 171), PorterDuff.Mode.SRC_IN)
        } else {
            setIconColor(selectDirButton, normalIconColor)
        }

        setIconColor(menuButton, normalIconColor)

        if (isNightMode) {
            placeholderView.setBackgroundColor(Color.parseColor("#333333"))
            (placeholderView.getChildAt(0) as? TextView)?.setTextColor(Color.parseColor("#F5F5F5"))
            errorView.setBackgroundColor(Color.parseColor("#333333"))
            errorTextView.setTextColor(Color.parseColor("#F5F5F5"))
        } else {
            placeholderView.setBackgroundColor(Color.parseColor("#F5F5F5"))
            (placeholderView.getChildAt(0) as? TextView)?.setTextColor(Color.DKGRAY)
            errorView.setBackgroundColor(Color.parseColor("#F5F5F5"))
            errorTextView.setTextColor(Color.DKGRAY)
        }

        if (::instancesLayerContent.isInitialized) {
            val bgColor = if (isNightMode) Color.parseColor("#DD333333") else Color.parseColor("#DDFFFFFF")
            instancesLayerContent.setBackgroundColor(bgColor)

            // 顶部栏背景（透明或跟随列表项背景）
            instancesTopBar.setBackgroundColor(Color.TRANSPARENT)

            // 当前实例文字颜色
            currentInstanceText.setTextColor(if (isNightMode) Color.WHITE else Color.BLACK)

            // 保存按钮图标颜色
            val iconColor = if (isNightMode) Color.WHITE else Color.DKGRAY
            saveInstanceButton.drawable?.setColorFilter(
                PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            )

            // 同步夜间模式到适配器
            if (::instanceAdapter.isInitialized) {
                instanceAdapter.isNightMode = isNightMode
            }

            // 分割线颜色
            val divider = instancesLayer.findViewById<View>(R.id.divider)
            divider?.setBackgroundColor(if (isNightMode) Color.parseColor("#444444") else Color.parseColor("#CCCCCC"))
        }
        if (::saveInstanceButton.isInitialized) {
            val iconColor = if (saveInstanceButton.isEnabled) normalIconColor else disabledColor
            saveInstanceButton.drawable?.setColorFilter(PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN))
        }
    }

    private fun updateActionButtonState() {
        if (urlEditText.hasFocus()) {
            goButton.setImageResource(R.drawable.baseline_arrow_forward_ios_24)
            goButton.contentDescription = "前往"
        } else {
            goButton.setImageResource(R.drawable.baseline_refresh_24)
            goButton.contentDescription = "刷新"
        }
        // 重新应用正确的图标颜色
        val normalIconColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        setIconColor(goButton, normalIconColor)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars())
                it.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                )
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
            window.insetsController?.setSystemBarsBehavior(
                android.view.WindowInsetsController.BEHAVIOR_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
        }
    }

    private fun hideBars() {
        dismissOverflowMenu()
        menuButton.isEnabled = false

        if (isGuideActive) return
        if (!isAutoHideEnabled) return
        if (isBarVisible && !isAnimating && urlBarHeight > 0 && toolbarHeight > 0) {
            isAnimating = true
            window.setBackgroundDrawableResource(if (isNightMode) android.R.color.black else android.R.color.white)

            val urlBarAnim = ValueAnimator.ofInt(urlBar.height, 0)
            urlBarAnim.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                urlBar.layoutParams.height = value
                urlBar.requestLayout()
                rootFrame.invalidate()
            }
            val toolbarAnim = ValueAnimator.ofInt(toolbar.height, 0)
            toolbarAnim.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                toolbar.layoutParams.height = value
                toolbar.requestLayout()
                rootFrame.invalidate()
            }
            val animatorSet = AnimatorSet()
            animatorSet.playTogether(urlBarAnim, toolbarAnim)
            animatorSet.duration = animationDuration
            animatorSet.addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    menuButton.isEnabled = true
                    isBarVisible = false
                    isAnimating = false
                    floatingBall.visibility = View.VISIBLE
                    urlBar.visibility = View.GONE
                    toolbar.visibility = View.GONE
                    window.setBackgroundDrawable(null)
                    if (!isLandscapeGuideShown()) {
                        showLandscapeGuide()
                    }
                }
                override fun onAnimationCancel(animation: Animator) { isAnimating = false }
                override fun onAnimationRepeat(animation: Animator) {}
            })
            animatorSet.start()
        }
    }

    private fun showBars() {
        if (!isBarVisible && !isAnimating && urlBarHeight > 0 && toolbarHeight > 0) {
            isAnimating = true
            window.setBackgroundDrawableResource(if (isNightMode) android.R.color.black else android.R.color.white)

            urlBar.visibility = View.VISIBLE
            toolbar.visibility = View.VISIBLE
            urlBar.layoutParams.height = 0
            toolbar.layoutParams.height = 0
            urlBar.requestLayout()
            toolbar.requestLayout()

            val urlBarAnim = ValueAnimator.ofInt(0, urlBarHeight)
            urlBarAnim.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                urlBar.layoutParams.height = value
                urlBar.requestLayout()
                rootFrame.invalidate()
            }
            val toolbarAnim = ValueAnimator.ofInt(0, toolbarHeight)
            toolbarAnim.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                toolbar.layoutParams.height = value
                toolbar.requestLayout()
                rootFrame.invalidate()
            }
            val animatorSet = AnimatorSet()
            animatorSet.playTogether(urlBarAnim, toolbarAnim)
            animatorSet.duration = animationDuration
            animatorSet.addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                    floatingBall.visibility = View.GONE
                }
                override fun onAnimationEnd(animation: Animator) {
                    isBarVisible = true
                    isAnimating = false
                    urlBar.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    toolbar.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    urlBar.requestLayout()
                    toolbar.requestLayout()
                    window.setBackgroundDrawable(null)
                    resetHideTimer()
                }
                override fun onAnimationCancel(animation: Animator) { isAnimating = false }
                override fun onAnimationRepeat(animation: Animator) {}
            })
            animatorSet.start()
        }
    }

    private fun resetHideTimer() {
        if (isGuideActive) return
        if (!isAutoHideEnabled) return
        if (urlEditText.isFocused()) return
        if (isBarVisible) {
            hideHandler.removeCallbacks(hideRunnable)
            hideHandler.postDelayed(hideRunnable, 3000)
        }
    }

    private fun showLandscapeGuide() {
        if (isLandscapeGuideShown() || !::floatingBall.isInitialized || isGuideRunning) return
        isGuideRunning = true
        try {
            TapTargetSequence(this)
                .targets(
                    TapTarget.forView(floatingBall, "点击恢复地址栏和工具栏", "点击悬浮球可展开两栏，3秒无操作将自动收起")
                        .outerCircleColorInt(Color.parseColor("#444444"))
                        .targetCircleColorInt(Color.parseColor("#DDDDDD"))
                        .titleTextSize(18)
                        .targetRadius(18)
                        .descriptionTextSize(14)
                        .textColorInt(Color.WHITE)
                        .cancelable(false)
                        .tintTarget(false)
                )
                .listener(object : TapTargetSequence.Listener {
                    override fun onSequenceFinish() {
                        markLandscapeGuideShown()
                        isGuideRunning = false
                    }
                    override fun onSequenceStep(lastTarget: TapTarget?, targetClicked: Boolean) {}
                    override fun onSequenceCanceled(lastTarget: TapTarget?) {
                        markLandscapeGuideShown()
                        isGuideRunning = false
                    }
                })
                .start()
        } catch (e: Exception) {
            e.printStackTrace()
            markLandscapeGuideShown()
            isGuideRunning = false
        }
    }

    private fun isLandscapeGuideShown(): Boolean {
        return prefs.getBoolean(KEY_LANDSCAPE_GUIDE_SHOWN, false)
    }

    private fun markLandscapeGuideShown() {
        prefs.edit().putBoolean(KEY_LANDSCAPE_GUIDE_SHOWN, true).apply()
    }

    private fun isGuideShown(): Boolean {
        return prefs.getBoolean(KEY_GUIDE_SHOWN, false)
    }

    private fun markGuideShown() {
        prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
    }

    private fun startUserGuide() {
        if (isGuideShown() || isGuideRunning) return
        isGuideRunning = true
        isGuideActive = true

        if (!::selectDirButton.isInitialized ||
            !::selectFileButton.isInitialized ||
            !::instancesButton.isInitialized ||   // 新增检查
            !::urlEditText.isInitialized
        ) {
            isGuideRunning = false
            isGuideActive = false
            return
        }

        originalOrientation = requestedOrientation
        val currentOrientation = resources.configuration.orientation
        requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        forceShowBarsForGuide()

        try {
            TapTargetSequence(this)
                .targets(
                    TapTarget.forView(selectDirButton, "选择服务器根目录", "长按此按钮可切换模式：\n文件夹模式（初始，读取文件速度慢，不兼容鸿蒙 NEXT 设备）：点击此按钮，选择一个文件夹作为本地服务器的根目录。建议选择 Download 文件夹下的任意目录。\nZIP 解压模式（推荐，速度很快但需占用存储）：点击此按钮，选择一个 ZIP 压缩包，将其解压至应用数据目录作为本地服务器的根目录。\n\n服务器运行中，点击此按钮可停止服务器。")
                        .outerCircleColorInt(Color.parseColor("#444444"))
                        .targetCircleColorInt(Color.parseColor("#DDDDDD"))
                        .titleTextSize(18)
                        .descriptionTextSize(14)
                        .textColorInt(Color.WHITE)
                        .cancelable(false)
                        .tintTarget(true),
                    TapTarget.forView(selectFileButton, "选择网页文件", "点击此按钮，从您刚才选择的根目录下，选取一个 .html 或 .htm 文件进行浏览。")
                        .outerCircleColorInt(Color.parseColor("#444444"))
                        .targetCircleColorInt(Color.parseColor("#DDDDDD"))
                        .titleTextSize(18)
                        .descriptionTextSize(14)
                        .textColorInt(Color.WHITE)
                        .cancelable(false)
                        .tintTarget(true),
                    TapTarget.forView(instancesButton, "实例管理", "保存当前网页状态为实例，或加载已保存的实例。\n实例会保存服务器根目录和当前页面路径，便于快速切换不同的网页项目。\n对于文件夹模式，保存实例可选择仅保存路径或复制全部文件至私有目录。")
                        .outerCircleColorInt(Color.parseColor("#444444"))
                        .targetCircleColorInt(Color.parseColor("#DDDDDD"))
                        .titleTextSize(18)
                        .descriptionTextSize(14)
                        .textColorInt(Color.WHITE)
                        .cancelable(false)
                        .tintTarget(true),
                    TapTarget.forView(urlEditText, "地址栏与启动参数", "这里会显示当前页面的地址，您也可以在这里直接输入自定义的参数或新的页面路径。")
                        .outerCircleColorInt(Color.parseColor("#444444"))
                        .targetCircleColorInt(Color.parseColor("#888888"))
                        .titleTextSize(18)
                        .descriptionTextSize(14)
                        .textColorInt(Color.WHITE)
                        .cancelable(false)
                        .tintTarget(false)
                )
                .listener(object : TapTargetSequence.Listener {
                    override fun onSequenceFinish() {
                        markGuideShown()
                        guideEnd()
                        isGuideRunning = false
                        Toast.makeText(this@MainActivity, "指引完成！", Toast.LENGTH_SHORT).show()
                    }
                    override fun onSequenceStep(lastTarget: TapTarget?, targetClicked: Boolean) {}
                    override fun onSequenceCanceled(lastTarget: TapTarget?) {
                        markGuideShown()
                        guideEnd()
                        isGuideRunning = false
                    }
                })
                .start()
        } catch (e: Exception) {
            e.printStackTrace()
            markGuideShown()
            guideEnd()
            isGuideRunning = false
        }
    }

    private fun forceShowBarsForGuide() {
        hideHandler.removeCallbacks(hideRunnable)
        if (isAnimating) {
            isAnimating = false
        }
        if (!isBarVisible || urlBar.height == 0 || toolbar.height == 0) {
            urlBar.visibility = View.VISIBLE
            toolbar.visibility = View.VISIBLE
            urlBar.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            toolbar.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            urlBar.requestLayout()
            toolbar.requestLayout()
            isBarVisible = true
        }
        floatingBall.visibility = View.GONE
        urlBar.post {
            if (urlBarHeight == 0) {
                urlBarHeight = urlBar.height
                toolbarHeight = toolbar.height
            }
        }
    }

    private fun guideEnd() {
        isGuideActive = false
        requestedOrientation = originalOrientation
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            resetHideTimer()
        }
    }

    private fun showErrorView(errorUri: String) {
        val message = when {
            errorUri.contains("404") -> "文件不存在，请选择文件或输入路径"
            errorUri.contains("403") -> "无权访问目录，请重试"
            errorUri.contains("500") -> "服务器内部错误，请重试"
            else -> "加载失败，请重试"
        }
        errorTextView.text = "$message"
        errorView.visibility = View.VISIBLE
        if (::placeholderView.isInitialized && placeholderView.visibility == View.VISIBLE) {
            placeholderView.visibility = View.GONE
        }
    }

    private fun hideErrorView() {
        errorView.visibility = View.GONE
    }

    private fun lockCurrentOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun unlockOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onResume() {
        super.onResume()
        resetHideTimer()
        if (!isServerStarted && currentSelectMode == SelectMode.ZIP) {
            cleanupUnzippedDir()
        }
    }

    private fun showStopServerDialog() {
        AlertDialog.Builder(this)
            .setTitle("停止服务器")
            .setMessage("当前服务器正在运行，是否停止并重新选择目录？")
            .setPositiveButton("停止") { _, _ ->
                log("用户确认停止服务器")
                stopServerAndReset()
            }
            .setNegativeButton("取消") { dialog, _ ->
                log("用户取消停止服务器")
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun stopServerAndReset(onComplete: (() -> Unit)? = null) {
        localWebServer?.stop()
        localWebServer = null
        isServerStarted = false
        isZipMode = false
        currentServerRoot = null
        rootUri = null
        currentInstanceRootDir = null
        geckoSession.loadUri("about:blank")
        geckoSession.purgeHistory()
        updateUIAfterDirSelected()
        canGoBack = false
        canGoForward = false
        updateNavigationButtonsState()
        urlEditText.setText("")
        urlEditText.hint = when (currentSelectMode) {
            SelectMode.FOLDER -> "请先选择服务器根目录"
            SelectMode.ZIP -> "请先选择 ZIP 包"
        }
        log("服务器已停止，界面已重置")

        if (currentSelectMode == SelectMode.ZIP) {
            cleanupUnzippedDir()
        }
        updateSelectModeIcon()
        isCurrentInstanceSaved = false
        updateDeleteAndSaveButtonsState()
        onComplete?.invoke()
    }

    private fun cleanupUnzippedDir() {
        if (::unzippedDir.isInitialized && unzippedDir.exists()) {
            try {
                unzippedDir.deleteRecursively()
                log("已清理解压目录: ${unzippedDir.absolutePath}")
            } catch (e: Exception) {
                log("清理解压目录失败: ${e.message}")
            }
        }
    }
    private fun switchSelectMode() {
        if (isServerStarted) {
            // 服务器运行中，提示需先停止
            AlertDialog.Builder(this)
                .setTitle("无法切换模式")
                .setMessage("服务器正在运行，请先停止服务器后再切换模式。\n是否停止服务器？")
                .setPositiveButton("停止并切换") { _, _ ->
                    stopServerAndReset {
                        // 停止完成后切换模式
                        doSwitchMode()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            doSwitchMode()
        }
    }

    private fun doSwitchMode() {
        currentSelectMode = if (currentSelectMode == SelectMode.FOLDER) {
            SelectMode.ZIP
        } else {
            SelectMode.FOLDER
        }
        // 保存模式
        selectModePrefs.edit().putString(KEY_SELECT_MODE, currentSelectMode.name).apply()
        updateSelectModeIcon()
        updateUIForCurrentMode()
        val modeName = if (currentSelectMode == SelectMode.FOLDER) "文件夹模式" else " ZIP 解压模式"
        Toast.makeText(this, "已切换到$modeName", Toast.LENGTH_SHORT).show()
        log("切换模式为: ${currentSelectMode.name}")
    }

    private fun updateSelectModeIcon() {
        val iconRes = when (currentSelectMode) {
            SelectMode.FOLDER -> R.drawable.baseline_folder_open_24  // 你需要替换为实际的文件夹图标
            SelectMode.ZIP -> R.drawable.baseline_folder_zip_24       // 你需要替换为实际的ZIP图标
        }
        selectDirButton.setImageResource(iconRes)
        // 如果图标需要着色，可以应用夜间模式颜色
        applyNightMode()
    }

    private fun updateUIForCurrentMode() {
        if (!isServerStarted) {
            urlEditText.hint = when (currentSelectMode) {
                SelectMode.FOLDER -> "请先选择服务器根目录"
                SelectMode.ZIP -> "请先选择 ZIP 包"
            }
            // 确保文件选择按钮禁用
            selectFileButton.isEnabled = false
            selectFileButton.alpha = 0.4f
            placeholderView.visibility = View.VISIBLE
        }
    }

    /**
     * 显示实例图层，并刷新列表数据
     */
    private fun showInstancesLayer() {
        if (isInstancesLayerVisible) return
        // 每次显示前刷新实例列表（确保数据最新）
        refreshInstanceList()
        instancesLayer.visibility = View.VISIBLE
        isInstancesLayerVisible = true
        log("实例图层已显示")
    }

    /**
     * 隐藏实例图层
     */
    private fun hideInstancesLayer() {
        if (!isInstancesLayerVisible) return
        instancesLayer.visibility = View.GONE
        isInstancesLayerVisible = false
        log("实例图层已隐藏")
    }

    // 在 onCreate 中初始化实例列表（在 InstanceManager.init 之后）
    private fun initInstancesUI() {
        instancesLayerContent = instancesLayer.findViewById(R.id.instancesLayerContent)
        instancesRecyclerView = instancesLayer.findViewById(R.id.instancesRecyclerView) as RecyclerView
        currentInstanceText = instancesLayer.findViewById(R.id.currentInstanceText)
        saveInstanceButton = instancesLayer.findViewById(R.id.saveInstanceButton)
        instancesTopBar = instancesLayer.findViewById(R.id.instancesTopBar)

        instanceAdapter = InstanceAdapter(
            instances = InstanceManager.getAllInstances(),
            onItemClick = { instance ->
                log("加载实例: ${instance.name}")
                loadInstance(instance)
            },
            onDeleteClick = { instance ->
                AlertDialog.Builder(this)
                    .setTitle("删除实例")
                    .setMessage("确定要删除实例“${instance.name}”吗？")
                    .setPositiveButton("删除") { _, _ ->
                        val deleted = InstanceManager.deleteInstance(instance.id, deleteFiles = false)
                        if (deleted) {
                            refreshInstanceList()
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        instancesRecyclerView.layoutManager = LinearLayoutManager(this)
        instancesRecyclerView.adapter = instanceAdapter

        saveInstanceButton.setOnClickListener {
            if (!saveInstanceButton.isEnabled) {
                Toast.makeText(this, "当前正在运行已保存的实例时，不可重复保存", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isSaving) {
                Toast.makeText(this, "正在保存中，请稍候", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isServerStarted) {
                Toast.makeText(this, "没有运行中的服务器，无法保存", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showSaveInstanceDialog()
        }

        refreshInstanceList()
    }

    // 刷新列表并更新当前实例显示
    private fun refreshInstanceList() {
        val instances = InstanceManager.getAllInstances()
        instanceAdapter.updateData(instances)
        updateCurrentInstanceDisplay()
    }

    // 更新顶部当前实例名称
    private fun updateCurrentInstanceDisplay() {
        val displayText = if (isCurrentInstanceSaved) {
            val currentName = currentInstanceId?.let { InstanceManager.getInstanceById(it)?.name } ?: "无"
            "当前实例：$currentName"
        } else {
            val folderName = when {
                isZipMode && currentInstanceRootDir != null -> currentInstanceRootDir!!.name
                rootUri != null -> getFolderNameFromUri(rootUri!!)
                else -> "未知"
            }
            "当前实例：（临时）$folderName"
        }
        currentInstanceText.text = displayText
    }

    private fun getFolderNameFromUri(uri: Uri): String {
        return try {
            val doc = DocumentFile.fromTreeUri(this, uri)
            doc?.name ?: "外部文件夹"
        } catch (e: Exception) {
            "外部文件夹"
        }
    }

    // 显示保存实例对话框
    private fun showSaveInstanceDialog() {
        val currentUrl = getCurrentRelativeUrl()
        val defaultName = if (isCurrentInstanceSaved) {
            generateDefaultInstanceName()  // 保留原逻辑（备用）
        } else {
            // 临时模式，使用文件夹名作为默认名称
            when {
                isZipMode && currentInstanceRootDir != null -> currentInstanceRootDir!!.name
                rootUri != null -> getFolderNameFromUri(rootUri!!)
                else -> generateDefaultInstanceName()
            }
        }

        val inputEditText = EditText(this).apply {
            setText(defaultName)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle("保存实例")
            .setMessage("请输入实例名称：")
            .setView(inputEditText)
            .setPositiveButton("保存") { _, _ ->
                val name = inputEditText.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    showSaveInstanceDialog() // 重新弹出
                    return@setPositiveButton
                }
                startSaveInstance(name, currentUrl)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 生成默认实例名称（当前日期时间）
    private fun generateDefaultInstanceName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "实例_${sdf.format(Date())}"
    }

    // 获取当前页面的相对 URL（去掉 http://localhost:8080/ 前缀）
    private fun getCurrentRelativeUrl(): String {
        val fullUrl = currentSessionUrl
        val prefix = "http://localhost:8080/"
        return if (fullUrl.startsWith(prefix)) {
            fullUrl.substring(prefix.length)
        } else {
            ""
        }
    }

    // 开始保存实例
    private fun startSaveInstance(name: String, savedUrl: String) {
        if (isSaving) return
        isSaving = true

        // 检查名称是否已存在
        if (InstanceManager.isNameExists(name)) {
            Toast.makeText(this, "实例名称已存在，请重新输入", Toast.LENGTH_SHORT).show()
            isSaving = false
            showSaveInstanceDialog()
            return
        }

        when {
            isZipMode -> {
                // ZIP 解压模式：移动 unzippedDir
                saveZipInstance(name, savedUrl)
            }
            rootUri != null -> {
                // 外部文件夹模式：弹出选择
                showSaveModeDialog(name, savedUrl)
            }
            else -> {
                Toast.makeText(this, "无法确定当前服务器模式", Toast.LENGTH_SHORT).show()
                isSaving = false
            }
        }
    }

    // 显示保存模式选择对话框（仅外部文件夹模式）
    private fun showSaveModeDialog(name: String, savedUrl: String) {
        val options = arrayOf("仅保存路径（引用原文件夹）", "复制全部文件（占用存储空间）")
        AlertDialog.Builder(this)
            .setTitle("选择保存方式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveReferenceInstance(name, savedUrl)
                    1 -> saveCopyInstanceWithProgress(name, savedUrl)
                }
            }
            .setOnCancelListener { isSaving = false }
            .show()
    }

    // ZIP 模式保存
    private fun saveZipInstance(name: String, savedUrl: String) {
        val currentUnzippedDir = unzippedDir
        // 1. 仅停止服务器，不清理
        stopServerOnly()

        // 2. 在后台线程移动目录
        Thread {
            val instance = InstanceManager.saveZipInstance(name, savedUrl, currentUnzippedDir)
            runOnUiThread {
                isSaving = false
                if (instance != null) {
                    // 移动成功，完全重置当前状态
                    isZipMode = false
                    currentServerRoot = null
                    rootUri = null
                    updateUIAfterDirSelected()  // 清空 UI
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
                    refreshInstanceList()
                    hideInstancesLayer()
                    // 自动加载新实例
                    loadInstance(instance)
                } else {
                    // 移动失败，尝试重启原服务器（原目录还在）
                    startServerFromFile(currentUnzippedDir)
                    AlertDialog.Builder(this)
                        .setTitle("保存失败")
                        .setMessage("无法移动目录，请重试")
                        .setPositiveButton("重试") { _, _ -> showSaveInstanceDialog() }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }.start()
    }

    // 引用模式保存
    private fun saveReferenceInstance(name: String, savedUrl: String) {
        val instance = InstanceManager.saveReferenceInstance(name, savedUrl, rootUri!!)
        isSaving = false
        if (instance != null) {
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
            refreshInstanceList()
            hideInstancesLayer()
            // 自动加载新实例（引用模式加载时需要处理授权）
            loadInstance(instance)
        } else {
            Toast.makeText(this, "保存失败（可能名称重复）", Toast.LENGTH_SHORT).show()
        }
    }

    // 加载实例
    private fun loadInstance(instance: Instance) {
        stopServerOnly()

        when (instance.type) {
            "reference" -> {
                val uri = Uri.parse(instance.sourceUri)
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    rootUri = uri
                    startServer()
                    isZipMode = false
                    currentServerRoot = uri
                    isServerStarted = true
                    currentInstanceId = instance.id
                    updateCurrentInstanceDisplay()
                    val url = if (instance.savedUrl.isNotEmpty()) "http://localhost:8080/${instance.savedUrl}" else "http://localhost:8080/"
                    loadUrl(url)
                    selectFileButton.setOnClickListener { openFilePickerInRoot() }
                    // 引用实例图标：baseline_folder_open_24
                    selectDirButton.setImageResource(R.drawable.baseline_folder_open_24)
                } catch (e: Exception) {
                    handleReferenceAuthFailure(instance)
                    return
                }
            }
            else -> { // "copy" 或 "zip_move"
                val dir = File(instance.storageDir)
                if (!dir.exists()) {
                    Toast.makeText(this, "实例目录不存在，可能已被删除", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this)
                        .setTitle("实例失效")
                        .setMessage("实例“${instance.name}”的目录不存在，是否从列表中删除？")
                        .setPositiveButton("删除") { _, _ ->
                            InstanceManager.deleteInstance(instance.id, deleteFiles = false)
                            refreshInstanceList()
                        }
                        .setNegativeButton("忽略", null)
                        .show()
                    return
                }
                startServerFromFile(dir)
                isZipMode = true   // 本地实例使用私有目录文件选择器
                currentServerRoot = dir
                isServerStarted = true
                currentInstanceId = instance.id
                updateCurrentInstanceDisplay()
                val url = if (instance.savedUrl.isNotEmpty()) "http://localhost:8080/${instance.savedUrl}" else "http://localhost:8080/"
                loadUrl(url)
                selectFileButton.setOnClickListener { showFilePickerForPrivateDir() }
                // 本地实例图标：baseline_folder_24
                selectDirButton.setImageResource(R.drawable.baseline_folder_24)
            }
        }
        hideInstancesLayer()
        isCurrentInstanceSaved = true
        updateDeleteAndSaveButtonsState()   // 新增方法，控制按钮启用/禁用
    }
    private fun saveCopyInstanceWithProgress(name: String, savedUrl: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_copy_progress, null)
        val progressMessage = dialogView.findViewById<TextView>(R.id.progressMessage)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val progressCountText = dialogView.findViewById<TextView>(R.id.progressCountText)

        val alertDialog = AlertDialog.Builder(this)
            .setTitle("正在复制文件")
            .setView(dialogView)
            .setNegativeButton("取消") { _, _ ->
                isCopyCancelled = true
                copyJob?.cancel()
            }
            .setCancelable(true)
            .create()

        alertDialog.setOnCancelListener {
            isCopyCancelled = true
            copyJob?.cancel()
        }

        alertDialog.show()

        isCopyCancelled = false
        isSaving = true

        copyJob = GlobalScope.launch(Dispatchers.Main) {
            InstanceManager.saveCopyInstance(
                name = name,
                savedUrl = savedUrl,
                rootUri = rootUri!!,
                context = this@MainActivity,
                onProgress = { fileName ->
                    // 仅显示当前文件名
                    progressMessage.text = "正在复制: $fileName"
                },
                onCountUpdate = { copied, total ->
                    // 进度条直接反映文件数
                    progressBar.max = total
                    progressBar.progress = copied
                    progressCountText.text = "$copied / $total"
                },
                onComplete = { instance ->
                    alertDialog.dismiss()
                    isSaving = false
                    copyJob = null

                    if (instance != null && !isCopyCancelled) {
                        Toast.makeText(this@MainActivity, "保存成功", Toast.LENGTH_SHORT).show()
                        refreshInstanceList()
                        hideInstancesLayer()
                        loadInstance(instance)
                    } else {
                        if (isCopyCancelled) {
                            Toast.makeText(this@MainActivity, "复制已取消", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("保存失败")
                                .setMessage("复制文件时发生错误，请重试或选择仅保存路径")
                                .setPositiveButton("重试") { _, _ ->
                                    showSaveInstanceDialog()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                },
                isCancelled = { isCopyCancelled }
            )
        }
    }

    /**
     * 仅停止服务器，不清理任何目录或重置 UI（用于保存时的移动操作）
     */
    private fun stopServerOnly() {
        localWebServer?.stop()
        localWebServer = null
        isServerStarted = false
        isCurrentInstanceSaved = false
        updateDeleteAndSaveButtonsState()
    }

    private fun handleReferenceAuthFailure(instance: Instance) {
        pendingReferenceInstance = instance
        AlertDialog.Builder(this)
            .setTitle("需要重新授权")
            .setMessage("实例“${instance.name}”的原文件夹权限已失效，请重新选择该文件夹。")
            .setPositiveButton("选择文件夹") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, getDownloadsUri())
                }
                reauthorizeFolderLauncher.launch(intent)
            }
            .setNegativeButton("取消") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("加载失败")
                    .setMessage("无法加载实例“${instance.name}”，是否从列表中删除？")
                    .setPositiveButton("删除") { _, _ ->
                        InstanceManager.deleteInstance(instance.id, deleteFiles = false)
                        refreshInstanceList()
                    }
                    .setNegativeButton("保留", null)
                    .show()
            }
            .show()
    }

    private fun showFileBrowser(rootDir: File) {
        if (!rootDir.exists()) {
            Toast.makeText(this, "目录不存在", Toast.LENGTH_SHORT).show()
            return
        }
        currentBrowserRoot = rootDir
        currentBrowserCurrentDir = rootDir

        val dialogView = layoutInflater.inflate(R.layout.dialog_file_browser, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.fileRecyclerView)
        val pathText = dialogView.findViewById<TextView>(R.id.currentPathText)
        val closeButton = dialogView.findViewById<ImageButton>(R.id.closeFileBrowserButton)
        val backButton = dialogView.findViewById<ImageButton>(R.id.backButton)
        val titleBar = dialogView.findViewById<LinearLayout>(R.id.titleBar)

        // 根据夜间模式设置颜色
        val bgColor = if (isNightMode) Color.parseColor("#FF333333") else Color.WHITE
        val titleBarColor = if (isNightMode) Color.BLACK else Color.parseColor("#F5F5F5")
        val textColor = if (isNightMode) Color.WHITE else Color.BLACK
        val iconColor = if (isNightMode) Color.WHITE else Color.DKGRAY

        dialogView.setBackgroundColor(bgColor)
        titleBar.setBackgroundColor(titleBarColor)
        pathText.setTextColor(textColor)

        backButton.drawable?.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        closeButton.drawable?.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)

        lateinit var adapter: FileBrowserAdapter

        fun refreshFileList() {
            val files = currentBrowserCurrentDir?.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                ?: emptyList()
            adapter.updateItems(files)
            val displayPath = when {
                currentBrowserCurrentDir?.absolutePath == currentBrowserRoot?.absolutePath -> "根目录"
                else -> currentBrowserCurrentDir?.name ?: ""
            }
            pathText.text = displayPath
        }

        adapter = FileBrowserAdapter(emptyList()) { file ->
            when {
                file.isDirectory -> {
                    currentBrowserCurrentDir = file
                    refreshFileList()
                }
                file.isFile -> {
                    val relativePath = getRelativePathForFile(file)
                    val strategy = getFileOpenStrategy(file)
                    strategy?.open(file, relativePath)
                        ?: Toast.makeText(this, "不支持的文件类型", Toast.LENGTH_SHORT).show()
                }
            }
        }
        adapter.nightMode = isNightMode

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        closeButton.setOnClickListener {
            currentFileBrowserDialog?.dismiss()
        }

        backButton.setOnClickListener {
            if (currentBrowserCurrentDir?.absolutePath != currentBrowserRoot?.absolutePath) {
                currentBrowserCurrentDir = currentBrowserCurrentDir?.parentFile
                refreshFileList()
            } else {
                Toast.makeText(this, "已在根目录", Toast.LENGTH_SHORT).show()
            }
        }

        pathText.setOnClickListener {
            if (currentBrowserCurrentDir?.absolutePath != currentBrowserRoot?.absolutePath) {
                currentBrowserCurrentDir = currentBrowserCurrentDir?.parentFile
                refreshFileList()
            } else {
                Toast.makeText(this, "已在根目录", Toast.LENGTH_SHORT).show()
            }
        }

        refreshFileList()
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.show()

        // 限制最大高度为屏幕高度的80%（兼容所有API）
        dialogView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                dialogView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val maxHeight = (resources.displayMetrics.heightPixels * 0.8).toInt()
                if (dialogView.height > maxHeight) {
                    val params = dialogView.layoutParams
                    params.height = maxHeight
                    dialogView.layoutParams = params
                }
            }
        })

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        currentFileBrowserDialog = dialog
    }

    private fun getRelativePathForFile(file: File): String {
        val serverRoot = currentInstanceRootDir ?: unzippedDir
        return if (file.absolutePath.startsWith(serverRoot.absolutePath)) {
            file.absolutePath.substring(serverRoot.absolutePath.length + 1)
        } else {
            file.name
        }
    }

    private fun getFileOpenStrategy(file: File): FileOpenStrategy? {
        val extension = file.extension.lowercase()
        return when (extension) {
            "html", "htm" -> HtmlFileOpenStrategy(this)
            // 未来可添加其他扩展名：
            // "txt" -> TextFileOpenStrategy(this)
            // "png", "jpg" -> ImageFileOpenStrategy(this)
            else -> null
        }
    }

    private fun updateDeleteAndSaveButtonsState() {
        if (!::saveInstanceButton.isInitialized) return
        val canSaveOrDelete = isServerStarted && isCurrentInstanceSaved
        saveInstanceButton.isEnabled = !canSaveOrDelete   // 注意：保存按钮在已保存实例时应禁用，所以取反
        // 同时更新保存按钮的图标颜色（立即生效）
        val normalIconColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        val disabledColor = if (isNightMode) Color.DKGRAY else Color.LTGRAY
        val iconColor = if (saveInstanceButton.isEnabled) normalIconColor else disabledColor
        saveInstanceButton.drawable?.setColorFilter(PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN))

        // 更新实例列表中当前项的删除按钮
        instanceAdapter.setCurrentInstanceId(currentInstanceId, canSaveOrDelete) // 注意：canSaveOrDelete 为 true 时删除按钮应禁用
    }
}