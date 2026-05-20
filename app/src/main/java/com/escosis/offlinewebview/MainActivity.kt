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
import android.widget.EditText
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
import java.util.zip.ZipInputStream

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
    private lateinit var refreshButton: ImageButton
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

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_GUIDE_SHOWN = "guide_shown"
        private const val KEY_LANDSCAPE_GUIDE_SHOWN = "landscape_guide_shown"
        private const val AUTO_HIDE_PREFS = "auto_hide_prefs"
        private const val KEY_AUTO_HIDE_ENABLED = "auto_hide_enabled"
        private const val ORIENTATION_PREFS = "orientation_prefs"
        private const val KEY_ORIENTATION_ALLOWED = "orientation_allowed"
    }

    // 调试日志缓冲区
    private val logBuffer = mutableListOf<String>()
    private val maxLogLines = 500

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

        initZipMode()  // 初始化解压目录
        initViews()
        setupGeckoView()
        setupListeners()
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
    }

    private fun initZipMode() {
        unzippedDir = File(filesDir, "unzipped_www")
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
        refreshButton = findViewById(R.id.refreshButton)
        selectDirButton = findViewById(R.id.selectDirButton)
        selectFileButton = findViewById(R.id.selectFileButton)
        menuButton = findViewById(R.id.menuButton)
        floatingBall = findViewById(R.id.floatingBall)
        placeholderView = findViewById(R.id.placeholderView)
        floatingBall.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        errorView = findViewById(R.id.errorView)
        errorTextView = findViewById(R.id.errorTextView)
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
        refreshButton.setOnClickListener {
            if (errorView.visibility == View.VISIBLE && lastRequestedUrl != null) {
                log("刷新: 重新加载错误页面 $lastRequestedUrl")
                loadUrl(lastRequestedUrl!!)
            } else {
                log("刷新页面")
                geckoSession.reload()
            }
            resetHideTimer()
        }

        // 原有短按：选择文件夹模式
        selectDirButton.setOnClickListener {
            if (isServerStarted && rootUri != null && !isZipMode) {
                showStopServerDialog()
            } else if (isZipMode && isServerStarted) {
                // zip 模式下短按，询问是否切换回文件夹模式
                AlertDialog.Builder(this)
                    .setTitle("切换数据源")
                    .setMessage("当前为 ZIP 解压模式，是否停止并切换回文件夹选择模式？")
                    .setPositiveButton("切换") { _, _ ->
                        stopServerAndReset()
                        // 清空 zip 相关状态
                        isZipMode = false
                        currentServerRoot = null
                        // 恢复文件夹图标
                        selectDirButton.setImageResource(R.drawable.baseline_folder_open_24)
                        // 重新变为未选择文件夹状态
                        updateUIAfterDirSelected()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                log("打开文件夹选择器")
                openFolderPicker()
            }
            resetHideTimer()
        }

        // 长按：选择 ZIP 包
        selectDirButton.setOnLongClickListener {
            if (isServerStarted) {
                AlertDialog.Builder(this)
                    .setTitle("切换数据源")
                    .setMessage("当前服务器正在运行，是否停止并重新选择 ZIP 包？")
                    .setPositiveButton("继续") { _, _ -> chooseZipAndExtract() }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                chooseZipAndExtract()
            }
            true
        }

        // 文件选择按钮的行为在 updateUIAfterDirSelected 中动态设置
        urlEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                hideHandler.removeCallbacks(hideRunnable)
            } else {
                resetHideTimer()
            }
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
    }

    private fun updateNavigationButtonsState() {
        backButton.isEnabled = isServerStarted && canGoBack
        forwardButton.isEnabled = isServerStarted && canGoForward
        refreshButton.isEnabled = isServerStarted
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
            try {
                // 清空旧目录
                if (unzippedDir.exists()) {
                    unzippedDir.deleteRecursively()
                }
                unzippedDir.mkdirs()

                contentResolver.openInputStream(zipUri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            val targetFile = File(unzippedDir, entry.name)
                            if (entry.isDirectory) {
                                targetFile.mkdirs()
                            } else {
                                targetFile.parentFile?.mkdirs()
                                targetFile.outputStream().use { out ->
                                    zipStream.copyTo(out)
                                }
                            }
                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                        }
                    }
                }
                runOnUiThread {
                    progressDialog.dismiss()
                    // 解压成功，启动新服务器
                    stopServerAndReset()
                    startServerFromFile(unzippedDir)
                }
            } catch (e: Exception) {
                log("解压失败: ${e.message}")
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "解压失败: ${e.message}", Toast.LENGTH_LONG).show()
                    unzippedDir.deleteRecursively()
                }
            }
        }.start()
    }

    // 从 File 根目录启动服务器（zip 模式）
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
            isZipMode = true
            currentServerRoot = rootFile
            // 切换图标为 ZIP 文件夹图标
            selectDirButton.setImageResource(R.drawable.baseline_folder_zip_24)
            geckoSession.purgeHistory()
            log("服务器已启动，根目录: ${rootFile.absolutePath}")
            runOnUiThread {
                updateUIAfterDirSelected()
                loadUrl("http://localhost:8080/")
                Toast.makeText(this, "服务器已启动 (ZIP解压模式)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            log("服务器启动失败: ${e.message}")
            isServerStarted = false
            runOnUiThread {
                Toast.makeText(this, "服务器启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
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
            isZipMode = false
            currentServerRoot = rootUri
            // 确保图标为普通文件夹图标
            selectDirButton.setImageResource(R.drawable.baseline_folder_open_24)
            geckoSession.purgeHistory()
            log("服务器启动成功，端口 8080")
        } catch (e: Exception) {
            log("服务器启动失败: ${e.message}")
            Toast.makeText(this, "服务器启动失败: ${e.message}", Toast.LENGTH_LONG).show()
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
            // 根据模式设置文件选择按钮的行为
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

    // ZIP 模式下的简易文件选择对话框
    private fun showFilePickerForPrivateDir() {
        if (!isZipMode || currentServerRoot !is File) {
            Toast.makeText(this, "当前不在 ZIP 模式", Toast.LENGTH_SHORT).show()
            return
        }
        val rootFile = currentServerRoot as File
        val files = rootFile.listFiles()?.filter { it.isFile && (it.extension.equals("html", ignoreCase = true) || it.extension.equals("htm", ignoreCase = true)) } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "根目录下没有 HTML 文件", Toast.LENGTH_SHORT).show()
            return
        }
        val items = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要加载的 HTML 文件")
            .setItems(items) { _, which ->
                val selectedFile = files[which]
                val relativePath = rootFile.toURI().relativize(selectedFile.toURI()).path
                loadUrl("http://localhost:8080/$relativePath")
            }
            .show()
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
            urlEditText.setBackgroundColor(Color.parseColor("#333333"))
            urlEditText.setTextColor(Color.WHITE)
            urlEditText.setHintTextColor(Color.LTGRAY)
            setStatusBarColor(Color.parseColor("#333333"))
            setNavigationBarColor(Color.BLACK)
        } else {
            rootFrame.setBackgroundColor(Color.WHITE)
            toolbar.setBackgroundColor(Color.WHITE)
            urlBar.setBackgroundColor(Color.parseColor("#EFEFEF"))
            urlEditText.setBackgroundColor(Color.WHITE)
            urlEditText.setTextColor(Color.BLACK)
            urlEditText.setHintTextColor(Color.GRAY)
            setStatusBarColor(Color.parseColor("#F5F5F5"))
            setNavigationBarColor(Color.WHITE)
        }

        setIconColor(backButton, if (backButton.isEnabled) normalIconColor else disabledColor)
        setIconColor(forwardButton, if (forwardButton.isEnabled) normalIconColor else disabledColor)
        setIconColor(refreshButton, if (refreshButton.isEnabled) normalIconColor else disabledColor)
        setIconColor(selectFileButton, if (selectFileButton.isEnabled) normalIconColor else disabledColor)
        setIconColor(goButton, normalIconColor)

        // 目录按钮：服务器已启动时显示钴蓝色（无论 ZIP 模式还是普通模式）
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
                    TapTarget.forView(selectDirButton, "选择服务器根目录", "点击此按钮，选择一个文件夹作为本地服务器的根目录。建议选择 Download 文件夹下的任意目录，不要选择非内部存储空间（如最近，下载等）的目录。\n\n长按此按钮可选择 ZIP 压缩包，解压后使用。")
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
                    TapTarget.forView(urlEditText, "地址栏与启动参数", "这里会显示当前页面的地址，您也可以在这里直接输入自定义的参数或新的页面路径。")
                        .outerCircleColorInt(Color.parseColor("#444444"))
                        .targetCircleColorInt(Color.parseColor("#888888"))
                        .titleTextSize(18)
                        .descriptionTextSize(14)
                        .textColorInt(Color.WHITE)
                        .cancelable(false)
                        .tintTarget(false),
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

    private fun stopServerAndReset() {
        localWebServer?.stop()
        localWebServer = null
        isServerStarted = false
        rootUri = null
        // 注意：不清空 isZipMode 和 currentServerRoot，由调用者决定是否清空
        geckoSession.loadUri("about:blank")
        geckoSession.purgeHistory()
        updateUIAfterDirSelected()
        canGoBack = false
        canGoForward = false
        updateNavigationButtonsState()
        urlEditText.setText("")
        urlEditText.hint = if (isZipMode) "请先选择 ZIP 包" else "请先选择服务器根目录"
        log("服务器已停止，界面已重置")
    }
}