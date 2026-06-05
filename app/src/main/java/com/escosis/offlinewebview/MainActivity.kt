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
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.mozilla.geckoview.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

/**
 * 主活动：离线 WebView 浏览器，支持本地服务器、实例管理、文件夹/ZIP 两种模式、夜间模式等
 */
class MainActivity : AppCompatActivity(), DebugLogger {

    // ==================== 视图组件 ====================
    private lateinit var rootFrame: FrameLayout
    private lateinit var urlBar: LinearLayout
    private lateinit var urlEditText: EditText
    private lateinit var goButton: ImageButton
    private lateinit var toolbar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var selectDirButton: ImageButton      // 选择目录/ZIP 包按钮
    private lateinit var selectFileButton: ImageButton     // 选择文件按钮
    private lateinit var menuButton: ImageButton           // 菜单按钮
    private lateinit var floatingBall: ImageView           // 悬浮球（横屏时显示）
    private lateinit var placeholderView: FrameLayout      // 未选择目录时的占位视图
    private lateinit var errorView: FrameLayout            // 错误提示视图
    private lateinit var errorTextView: TextView
    private lateinit var debugPanel: LinearLayout          // 调试面板
    private lateinit var debugLogTextView: TextView

    // 实例图层相关视图
    private lateinit var instancesLayer: FrameLayout
    private lateinit var instancesLayerContent: LinearLayout
    private lateinit var instancesRecyclerView: RecyclerView
    private lateinit var instanceAdapter: InstanceAdapter
    private lateinit var currentInstanceText: TextView
    private lateinit var saveInstanceButton: ImageButton
    private lateinit var instancesTopBar: LinearLayout
    private lateinit var instancesButton: ImageButton

    // ==================== GeckoView 相关 ====================
    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var geckoRuntime: GeckoRuntime
    private var localWebServer: LocalWebServer? = null
    private var isServerStarted = false
    private var currentContextId: String? = null
    private var isSwitchingInstance = false

    // ==================== 服务器模式与根目录 ====================
    enum class SelectMode { FOLDER, ZIP }   // FOLDER: 外部文件夹模式, ZIP: 解压模式
    private var currentSelectMode: SelectMode = SelectMode.FOLDER
    private var rootUri: Uri? = null                // 外部文件夹模式使用的 Uri
    private var isZipMode = false                   // 当前是否为解压模式
    private lateinit var unzippedDir: File          // 临时解压目录（ZIP 模式下使用）
    private var currentServerRoot: Any? = null      // 当前服务器根目录（Uri 或 File）
    private var currentInstanceRootDir: File? = null // 当前实例的根目录（用于 ZIP/复制模式）

    // 改动：新增变量，保存当前 ZIP 解压模式下的原始 ZIP 文件名（不含扩展名）
    private var currentZipFileName: String? = null

    // ==================== 导航状态 ====================
    private var canGoBack = false
    private var canGoForward = false
    private var currentSessionUrl: String = ""      // 当前加载的 URL
    private var lastRequestedUrl: String? = null    // 最后一次请求的 URL（用于错误页面刷新）

    // ==================== 调试与日志 ====================
    private var isDebugEnabled = true
    private val logBuffer = mutableListOf<String>()
    private val maxLogLines = 500

    // ==================== 夜间模式、自动隐藏、旋转 ====================
    private lateinit var prefs: SharedPreferences
    private var isNightMode = false
    private lateinit var autoHidePrefs: SharedPreferences
    private var isAutoHideEnabled = false
    private lateinit var orientationPrefs: SharedPreferences
    private var isOrientationAllowed = true

    // ==================== 横屏自动隐藏动画 ====================
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

    // ==================== 引导相关 ====================
    private var isGuideRunning = false
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var isGuideActive = false

    // ==================== 实例管理相关 ====================
    private var isInstancesLayerVisible = false
    private var currentInstanceId: String? = null
    private var isCurrentInstanceSaved = false          // 当前运行的服务器是否来自已保存的实例
    private var isSaving = false                        // 防止重复保存
    private var copyJob: Job? = null
    private var copyProgressDialog: ProgressDialog? = null
    private var isCopyCancelled = false
    private var pendingReferenceInstance: Instance? = null  // 用于重新授权

    // ==================== 弹出菜单相关 ====================
    private var overflowPopup: PopupWindow? = null
    private var currentPopupView: LinearLayout? = null

    // ==================== 文件浏览器对话框 ====================
    private var currentFileBrowserDialog: AlertDialog? = null
    private var currentBrowserRoot: File? = null
    private var currentBrowserCurrentDir: File? = null

    // ==================== 选择模式偏好 ====================
    private lateinit var selectModePrefs: SharedPreferences

    // ==================== 文件类型处理策略接口 ====================
    interface FileOpenStrategy {
        fun open(file: File, relativePath: String)
    }

    inner class HtmlFileOpenStrategy : FileOpenStrategy {
        override fun open(file: File, relativePath: String) {
            loadUrl("http://localhost:8080/$relativePath")
            currentFileBrowserDialog?.dismiss()
        }
    }

    // ==================== 活动结果启动器 ====================
    // 文件夹选择器（FOLDER 模式）
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
                isCurrentInstanceSaved = false
                startServer()
                Toast.makeText(this, "服务器已启动", Toast.LENGTH_SHORT).show()
            } else {
                log("未选择文件夹")
                Toast.makeText(this, "未选择文件夹", Toast.LENGTH_SHORT).show()
            }
        } else {
            log("选择文件夹取消或失败")
        }
    }

    // 文件选择器（FOLDER 模式下选择文件）
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

    // ZIP 包选择器
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

    // 重新授权文件夹（引用实例丢失权限时）
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

    // ==================== Companion 常量 ====================
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

    // ==================== 生命周期 ====================
    override fun onCreate(savedInstanceState: Bundle?) {
        // 全屏适配（Android 11+ 边到边）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化延迟执行任务
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

        // 读取偏好设置
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isNightMode = prefs.getBoolean("night_mode", false)
        autoHidePrefs = getSharedPreferences(AUTO_HIDE_PREFS, Context.MODE_PRIVATE)
        isAutoHideEnabled = autoHidePrefs.getBoolean(KEY_AUTO_HIDE_ENABLED, false)
        orientationPrefs = getSharedPreferences(ORIENTATION_PREFS, Context.MODE_PRIVATE)
        isOrientationAllowed = orientationPrefs.getBoolean(KEY_ORIENTATION_ALLOWED, true)
        if (!isOrientationAllowed) {
            lockCurrentOrientation()
        }

        // 初始化组件
        initZipMode()               // 创建临时解压目录
        initViews()                 // 绑定视图
        setupGeckoView()            // 配置 GeckoView 和 Web 服务器
        setupListeners()            // 设置按钮点击等监听
        InstanceManager.init(this)
        InstanceManager.logger = { message -> log(message) }
        initInstancesUI()           // 实例管理界面
        applyNightMode()            // 应用夜间模式
        updateUIAfterDirSelected()  // 更新 UI 状态（禁用文件选择等）
        updateDeleteAndSaveButtonsState()
        setupDebugPanel()
        setupMenuButton()

        // 横竖屏布局调整
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

        // 状态栏自动隐藏监听
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

        // 首次运行引导
        if (!isGuideShown()) {
            window.decorView.post {
                if (!isGuideShown() && !isGuideRunning) {
                    startUserGuide()
                }
            }
        }

        // 读取选择模式偏好
        selectModePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMode = selectModePrefs.getString(KEY_SELECT_MODE, SelectMode.FOLDER.name) ?: SelectMode.FOLDER.name
        currentSelectMode = SelectMode.valueOf(savedMode)
        updateSelectModeIcon()
        updateUIForCurrentMode()

        // 处理返回键（优先关闭实例图层）
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

    override fun onResume() {
        super.onResume()
        resetHideTimer()
        if (!isServerStarted && currentSelectMode == SelectMode.ZIP) {
            cleanupUnzippedDir()
        }
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

    // ==================== 初始化方法 ====================
    private fun initZipMode() {
        val instancesRoot = File(filesDir, "instances")
        if (!instancesRoot.exists()) {
            instancesRoot.mkdirs()
        }
        unzippedDir = File(instancesRoot, ".ephemera")
        if (!unzippedDir.exists()) {
            unzippedDir.mkdirs()
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

    private fun setupGeckoView() {
        geckoView = findViewById(R.id.geckoview)
        geckoRuntime = GeckoRuntime.create(this)
        val tempSession = GeckoSession()
        tempSession.open(geckoRuntime)
        geckoView.setSession(tempSession)
        geckoSession = tempSession

        urlEditText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                goButton.performClick()
                true
            } else false
        }

        goButton.setOnClickListener {
            val currentIcon = goButton.drawable?.constantState
            val refreshIcon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.baseline_refresh_24)?.constantState
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

    private fun createAndAttachSession(contextId: String): GeckoSession {
        // 关闭旧 Session（如果有）
        if (::geckoSession.isInitialized && geckoSession.isOpen) {
            geckoSession.close()
        }

        val settings = GeckoSessionSettings.Builder()
            .contextId(contextId)
            .build()
        val newSession = GeckoSession(settings)
        newSession.open(geckoRuntime)
        newSession.settings.setAllowJavascript(true)   // 原来在 setupGeckoView 中设置，现在移到此处

        // 设置 NavigationDelegate 和 ProgressDelegate
        newSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
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
        newSession.progressDelegate = object : GeckoSession.ProgressDelegate {
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

        // 更新全局变量并绑定到 GeckoView
        geckoSession = newSession
        geckoView.setSession(geckoSession)

        return newSession
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

    private fun clearDataForContextId(contextId: String) {
        try {
            geckoRuntime.storageController.clearDataForSessionContext(contextId)
            log("已清除 contextId=$contextId 的所有网站数据")
        } catch (e: Exception) {
            log("清除数据失败: ${e.message}")
        }
    }

    // ==================== 服务器操作 ====================
    /**
     * 基于外部文件夹 Uri 启动服务器（FOLDER 模式）
     */
    private fun startServer() {
        if (rootUri == null) return

        if (currentContextId == null) {
            currentContextId = if (currentInstanceId != null) currentInstanceId else UUID.randomUUID().toString()
        }
        if (!::geckoSession.isInitialized || geckoSession.isOpen) {
            createAndAttachSession(currentContextId!!)
        }

        localWebServer?.stop()
        localWebServer = LocalWebServer(8080, rootUri = rootUri, context = this, debugLogger = this)
        try {
            localWebServer?.start()
            isServerStarted = true
            isZipMode = false
            currentServerRoot = rootUri
            updateDeleteAndSaveButtonsState()
            geckoSession.purgeHistory()
            log("服务器启动成功，端口 8080")
            runOnUiThread {
                updateUIAfterDirSelected()
                if(isCurrentInstanceSaved == false) loadUrl("http://localhost:8080/")
                Toast.makeText(this, "服务器已启动", Toast.LENGTH_SHORT).show()
            }
            hideInstancesLayer()
        } catch (e: Exception) {
            log("服务器启动失败: ${e.message}")
            isServerStarted = false
        }
    }

    /**
     * 基于本地文件目录启动服务器（ZIP 解压模式或复制实例模式）
     */
    private fun startServerFromFile(rootFile: File) {
        if (!rootFile.exists()) {
            log("根目录不存在: ${rootFile.absolutePath}")
            return
        }

        if (currentContextId == null) {
            currentContextId = if (currentInstanceId != null) currentInstanceId else UUID.randomUUID().toString()
        }
        if (!::geckoSession.isInitialized || geckoSession.isOpen) {
            createAndAttachSession(currentContextId!!)
        }

        localWebServer?.stop()
        localWebServer = LocalWebServer(8080, rootFile = rootFile, context = this, debugLogger = this)
        try {
            localWebServer?.start()
            isServerStarted = true
            isZipMode = true
            currentServerRoot = rootFile
            currentInstanceRootDir = rootFile
            updateDeleteAndSaveButtonsState()
            geckoSession.purgeHistory()
            log("服务器已启动，根目录: ${rootFile.absolutePath}")
            runOnUiThread {
                updateUIAfterDirSelected()
                if(isCurrentInstanceSaved == false) loadUrl("http://localhost:8080/")
            }
            hideInstancesLayer()
        } catch (e: Exception) {
            log("服务器启动失败: ${e.message}")
            isServerStarted = false
        }
    }

    /**
     * 仅停止服务器，不清理任何目录或重置 UI（用于保存实例时的移动操作）
     */
    private fun stopServerOnly() {
        localWebServer?.stop()
        localWebServer = null
        isServerStarted = false
        isCurrentInstanceSaved = false
        currentZipFileName = null
        updateDeleteAndSaveButtonsState()
    }

    /**
     * 停止服务器并重置所有状态（清除模式、根目录等）
     */
    private fun stopServerAndReset(onComplete: (() -> Unit)? = null) {
        hideInstancesLayer()
        if (::geckoSession.isInitialized) {
            geckoSession.close()
            if (currentContextId != null && currentInstanceId == null) {
                clearDataForContextId(currentContextId!!)
            }
            val tempSession = GeckoSession()
            tempSession.open(geckoRuntime)
            geckoView.setSession(tempSession)
            geckoSession = tempSession
        }

        localWebServer?.stop()
        localWebServer = null
        isServerStarted = false
        isZipMode = false
        currentServerRoot = null
        rootUri = null
        currentInstanceRootDir = null
        currentZipFileName = null
        currentInstanceId = null
        currentContextId = null
        isCurrentInstanceSaved = false
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

    // ==================== 文件/文件夹选择 ====================
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

    /**
     * 后台解压 ZIP 文件到私有目录，尝试多种编码（GBK → UTF-8）
     */
    private fun extractZipInBackground(zipUri: Uri) {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("正在解压，请稍候...")
            setCancelable(false)
            show()
        }
        hideInstancesLayer()
        Thread {
            var zipStream: ZipArchiveInputStream? = null
            try {
                cleanupUnzippedDir()
                unzippedDir.mkdirs()

                val inputStream = contentResolver.openInputStream(zipUri)
                    ?: throw Exception("无法打开 ZIP 文件流")

                val encodings = listOf("GBK", "UTF-8")
                var lastException: Exception? = null
                var success = false

                for (encodingName in encodings) {
                    if (success) break
                    try {
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
                                    zipStream.copyTo(output)
                                }
                            }
                            entry = zipStream.nextZipEntry
                        }
                        success = true
                        log("解压成功，编码：$encodingName")
                    } catch (e: Exception) {
                        lastException = e
                        log("使用编码 $encodingName 解压失败：${e.message}")
                        cleanupUnzippedDir()
                        unzippedDir.mkdirs()
                    } finally {
                        zipStream?.close()
                    }
                }

                if (!success) {
                    throw lastException ?: Exception("所有编码尝试均失败")
                }

                // 改动：获取 ZIP 文件名（不含扩展名）并保存
                val zipFileName = DocumentFile.fromSingleUri(this@MainActivity, zipUri)?.name ?: "ZIP文件"
                val displayName = zipFileName.replace(Regex("\\.zip$", RegexOption.IGNORE_CASE), "")
                currentZipFileName = displayName
                log("ZIP 文件名: $displayName")

                runOnUiThread {
                    progressDialog.dismiss()
                    isCurrentInstanceSaved = false
                    startServerFromFile(unzippedDir)
                }
            } catch (e: Exception) {
                log("解压失败: ${e.message}")
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "解压失败: ${e.message}", Toast.LENGTH_LONG).show()
                    cleanupUnzippedDir()
                }
            }
        }.start()
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

    // ==================== 模式切换 ====================
    private fun switchSelectMode() {
        if (isServerStarted) {
            AlertDialog.Builder(this)
                .setTitle("无法切换模式")
                .setMessage("服务器正在运行，请先停止服务器后再切换模式。\n是否停止服务器？")
                .setPositiveButton("停止并切换") { _, _ ->
                    stopServerAndReset {
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
        selectModePrefs.edit().putString(KEY_SELECT_MODE, currentSelectMode.name).apply()
        updateSelectModeIcon()
        updateUIForCurrentMode()
        val modeName = if (currentSelectMode == SelectMode.FOLDER) "文件夹模式" else "ZIP 解压模式"
        Toast.makeText(this, "已切换到$modeName", Toast.LENGTH_SHORT).show()
        log("切换模式为: ${currentSelectMode.name}")
    }

    private fun updateSelectModeIcon() {
        val iconRes = if (isCurrentInstanceSaved) {
            // 已保存实例统一显示普通文件夹图标
            R.drawable.baseline_folder_24
        } else {
            when (currentSelectMode) {
                SelectMode.FOLDER -> R.drawable.baseline_folder_open_24
                SelectMode.ZIP -> R.drawable.baseline_folder_zip_24
            }
        }
        selectDirButton.setImageResource(iconRes)
        applyNightMode()  // 确保图标颜色适应夜间模式
    }

    private fun updateUIForCurrentMode() {
        if (!isServerStarted) {
            urlEditText.hint = when (currentSelectMode) {
                SelectMode.FOLDER -> "请先选择服务器根目录"
                SelectMode.ZIP -> "请先选择 ZIP 包"
            }
            selectFileButton.isEnabled = false
            selectFileButton.alpha = 0.4f
            placeholderView.visibility = View.VISIBLE
        }
    }

    // ==================== UI 状态更新 ====================
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

    private fun updateNavigationButtonsState() {
        backButton.isEnabled = isServerStarted && canGoBack
        forwardButton.isEnabled = isServerStarted && canGoForward
        goButton.isEnabled = isServerStarted
        applyNightMode()
    }

    private fun updateUrlBar(url: String) {
        urlEditText.setText(url)
        urlEditText.setSelection(url.length)
        updateActionButtonState()
    }

    private fun updateActionButtonState() {
        if (urlEditText.hasFocus()) {
            goButton.setImageResource(R.drawable.baseline_arrow_forward_ios_24)
            goButton.contentDescription = "前往"
        } else {
            goButton.setImageResource(R.drawable.baseline_refresh_24)
            goButton.contentDescription = "刷新"
        }
        val normalIconColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        setIconColor(goButton, normalIconColor)
    }

    // ==================== 页面加载与导航 ====================
    private fun loadUrl(url: String) {
        log("加载 URL: $url")
        geckoSession.loadUri(url)
        updateUrlBar(url)
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

    private fun showErrorView(errorUri: String) {
        val message = when {
            errorUri.contains("404") -> "文件不存在，请选择文件或输入路径"
            errorUri.contains("403") -> "无权访问目录，请重试"
            errorUri.contains("500") -> "服务器内部错误，请重试"
            else -> "加载失败，请重试"
        }
        errorTextView.text = message
        errorView.visibility = View.VISIBLE
        if (::placeholderView.isInitialized && placeholderView.visibility == View.VISIBLE) {
            placeholderView.visibility = View.GONE
        }
    }

    private fun hideErrorView() {
        errorView.visibility = View.GONE
    }

    // ==================== 横屏自动隐藏与动画 ====================
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

    // ==================== 夜间模式与主题 ====================
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
            instancesTopBar.setBackgroundColor(Color.TRANSPARENT)
            currentInstanceText.setTextColor(if (isNightMode) Color.WHITE else Color.BLACK)
            val iconColor = if (isNightMode) Color.WHITE else Color.DKGRAY
            saveInstanceButton.drawable?.setColorFilter(
                PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            )
            if (::instanceAdapter.isInitialized) {
                instanceAdapter.isNightMode = isNightMode
            }
            val divider = instancesLayer.findViewById<View>(R.id.divider)
            divider?.setBackgroundColor(if (isNightMode) Color.parseColor("#444444") else Color.parseColor("#CCCCCC"))
        }
        if (::saveInstanceButton.isInitialized) {
            val iconColor = if (saveInstanceButton.isEnabled) normalIconColor else disabledColor
            saveInstanceButton.drawable?.setColorFilter(PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN))
        }
    }

    // ==================== 实例管理 ====================
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
                        clearDataForContextId(instance.id)
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
            },
            onClearDataClick = { instance ->
                confirmClearData(instance)   // 新增的清除数据确认方法
            }
        )

        instancesRecyclerView.layoutManager = LinearLayoutManager(this)
        instancesRecyclerView.adapter = instanceAdapter

        saveInstanceButton.setOnClickListener {
            if (!saveInstanceButton.isEnabled) {
                Toast.makeText(this, "当前状态不可保存", Toast.LENGTH_SHORT).show()
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

    private fun showInstancesLayer() {
        if (isInstancesLayerVisible) return
        refreshInstanceList()
        instancesLayer.visibility = View.VISIBLE
        isInstancesLayerVisible = true
        log("实例图层已显示")
    }

    private fun hideInstancesLayer() {
        if (!isInstancesLayerVisible) return
        instancesLayer.visibility = View.GONE
        isInstancesLayerVisible = false
        log("实例图层已隐藏")
    }

    private fun refreshInstanceList() {
        val instances = InstanceManager.getAllInstances()
        instanceAdapter.updateData(instances)
        updateCurrentInstanceDisplay()
        updateDeleteAndSaveButtonsState()
    }

    private fun confirmClearData(instance: Instance) {
        AlertDialog.Builder(this)
            .setTitle("清除数据")
            .setMessage("确定要清除实例“${instance.name}”的所有网站数据吗？此操作不可撤销。")
            .setPositiveButton("清除") { _, _ ->
                if (currentInstanceId == instance.id && isServerStarted) {
                    // 当前正在运行该实例，需要停止并重新加载以应用清除效果
                    stopServerAndReset {
                        clearDataForContextId(instance.id)
                        loadInstance(instance)
                    }
                } else {
                    clearDataForContextId(instance.id)
                    Toast.makeText(this, "已清除实例“${instance.name}”的数据", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateCurrentInstanceDisplay() {
        val displayText = if (isCurrentInstanceSaved) {
            val currentName = currentInstanceId?.let { InstanceManager.getInstanceById(it)?.name } ?: "无"
            "当前实例：$currentName"
        } else {
            val folderName = when {
                isZipMode && currentInstanceRootDir != null -> {
                    // 优先显示 ZIP 原始文件名，若为空则显示目录名（兼容）
                    currentZipFileName ?: currentInstanceRootDir!!.name
                }
                rootUri != null -> getFolderNameFromUri(rootUri!!)
                else -> "无"
            }
            if (folderName == "无") "当前实例：无" else "当前实例：（临时）$folderName"
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

    private fun showSaveInstanceDialog() {
        val currentUrl = getCurrentRelativeUrl()
        val defaultName = if (isCurrentInstanceSaved) {
            generateDefaultInstanceName()
        } else {
            when {
                isZipMode && currentInstanceRootDir != null -> {
                    // 临时 ZIP 模式：优先使用 ZIP 文件名，否则使用目录名
                    currentZipFileName ?: currentInstanceRootDir!!.name
                }
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
                    showSaveInstanceDialog()
                    return@setPositiveButton
                }
                startSaveInstance(name, currentUrl)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun generateDefaultInstanceName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "实例_${sdf.format(Date())}"
    }

    private fun getCurrentRelativeUrl(): String {
        val fullUrl = currentSessionUrl
        val prefix = "http://localhost:8080/"
        return if (fullUrl.startsWith(prefix)) {
            fullUrl.substring(prefix.length)
        } else {
            ""
        }
    }

    private fun startSaveInstance(name: String, savedUrl: String) {
        if (isSaving) return
        isSaving = true

        if (currentContextId != null && InstanceManager.getInstanceById(currentContextId!!) != null) {
            Toast.makeText(this, "当前环境已保存为实例，请勿重复保存", Toast.LENGTH_SHORT).show()
            isSaving = false
            return
        }

        if (name.equals(".ephemera", ignoreCase = true)) {
            Toast.makeText(this, "实例名称与临时解压目录相同，请使用其他名称", Toast.LENGTH_SHORT).show()
            isSaving = false
            showSaveInstanceDialog()  // 重新弹出对话框让用户修改
            return
        }

        if (InstanceManager.isNameExists(name)) {
            Toast.makeText(this, "实例名称已存在，请使用其他名称", Toast.LENGTH_SHORT).show()
            isSaving = false
            showSaveInstanceDialog()
            return
        }

        val instanceId = currentContextId ?: UUID.randomUUID().toString()

        when {
            isZipMode -> {
                saveZipInstance(name, savedUrl, instanceId)
            }
            rootUri != null -> {
                showSaveModeDialog(name, savedUrl, instanceId)
            }
            else -> {
                Toast.makeText(this, "无法确定当前服务器模式", Toast.LENGTH_SHORT).show()
                isSaving = false
            }
        }
    }

    private fun showSaveModeDialog(name: String, savedUrl: String, instanceId: String) {
        val options = arrayOf("仅保存路径（引用原文件夹）", "复制全部文件（占用存储空间）")
        AlertDialog.Builder(this)
            .setTitle("选择保存方式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveReferenceInstance(name, savedUrl, instanceId)
                    1 -> saveCopyInstanceWithProgress(name, savedUrl, instanceId)
                }
            }
            .setOnCancelListener { isSaving = false }
            .show()
    }

    private fun saveZipInstance(name: String, savedUrl: String, instanceId: String) {
        val currentUnzippedDir = unzippedDir
        stopServerOnly()
        Thread {
            val instance = InstanceManager.saveZipInstance(name, savedUrl, currentUnzippedDir, instanceId)
            runOnUiThread {
                isSaving = false
                if (instance != null) {
                    // 更新当前状态，复用已有的 contextId 和 session
                    currentInstanceId = instance.id
                    currentContextId = instance.id
                    isZipMode = true
                    isCurrentInstanceSaved = true
                    currentInstanceRootDir = File(instance.storageDir)
                    startServerFromFile(currentInstanceRootDir!!)
                    val url = if (savedUrl.isNotEmpty()) "http://localhost:8080/$savedUrl" else "http://localhost:8080/"
                    loadUrl(url)
                    updateSelectModeIcon()
                    refreshInstanceList()
                    hideInstancesLayer()
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
                } else {
                    // 保存失败，恢复原来的临时服务器
                    isCurrentInstanceSaved = false
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

    private fun saveReferenceInstance(name: String, savedUrl: String, instanceId: String) {
        val instance = InstanceManager.saveReferenceInstance(name, savedUrl, rootUri!!, instanceId)
        isSaving = false
        if (instance != null) {
            // 复用 contextId
            currentInstanceId = instance.id
            currentContextId = instance.id
            isCurrentInstanceSaved = true
            // reference 模式下保持 isZipMode = false，rootUri 不变
            startServer()
            val url = if (savedUrl.isNotEmpty()) "http://localhost:8080/$savedUrl" else "http://localhost:8080/"
            loadUrl(url)

            updateSelectModeIcon()
            refreshInstanceList()
            hideInstancesLayer()
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "保存失败（可能名称重复）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCopyInstanceWithProgress(name: String, savedUrl: String, instanceId: String) {
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
        hideInstancesLayer()

        isCopyCancelled = false
        isSaving = true

        copyJob = GlobalScope.launch(Dispatchers.Main) {
            InstanceManager.saveCopyInstance(
                name = name,
                savedUrl = savedUrl,
                rootUri = rootUri!!,
                context = this@MainActivity,
                onProgress = { fileName ->
                    progressMessage.text = "正在复制: $fileName"
                },
                onCountUpdate = { copied, total ->
                    progressBar.max = total
                    progressBar.progress = copied
                    progressCountText.text = "$copied / $total"
                },
                onComplete = { instance ->
                    alertDialog.dismiss()
                    isSaving = false
                    copyJob = null

                    if (instance != null && !isCopyCancelled) {
                        stopServerOnly()
                        currentInstanceId = instance.id
                        currentContextId = instance.id
                        isZipMode = true
                        isCurrentInstanceSaved = true
                        currentInstanceRootDir = File(instance.storageDir)
                        startServerFromFile(currentInstanceRootDir!!)
                        val url = if (savedUrl.isNotEmpty()) "http://localhost:8080/$savedUrl" else "http://localhost:8080/"
                        loadUrl(url)
                        updateSelectModeIcon()
                        refreshInstanceList()
                        Toast.makeText(this@MainActivity, "保存成功", Toast.LENGTH_SHORT).show()
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
                isCancelled = { isCopyCancelled },
                instanceId = instanceId
            )
        }
    }

    private fun loadInstance(instance: Instance) {
        // 停止当前服务器并清理临时数据
        if (isServerStarted) {
            stopServerOnly()
            if (currentInstanceId == null && currentContextId != null) {
                clearDataForContextId(currentContextId!!)
            }
        }

        // 设置当前实例 ID 和 contextId
        currentInstanceId = instance.id
        currentContextId = instance.id
        isCurrentInstanceSaved = true

        // 创建新 Session
        createAndAttachSession(currentContextId!!)

        // 根据实例类型启动服务器
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
                    refreshInstanceList()
                    val url = if (instance.savedUrl.isNotEmpty()) "http://localhost:8080/${instance.savedUrl}" else "http://localhost:8080/"
                    loadUrl(url)
                    selectFileButton.setOnClickListener { openFilePickerInRoot() }
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
                isCurrentInstanceSaved = true
                startServerFromFile(dir)
                isZipMode = true
                currentServerRoot = dir
                isServerStarted = true
                currentInstanceId = instance.id
                refreshInstanceList()
                val url = if (instance.savedUrl.isNotEmpty()) "http://localhost:8080/${instance.savedUrl}" else "http://localhost:8080/"
                loadUrl(url)
                selectFileButton.setOnClickListener { showFilePickerForPrivateDir() }
                selectDirButton.setImageResource(R.drawable.baseline_folder_24)
            }
        }
        hideInstancesLayer()
        isCurrentInstanceSaved = true
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

    private fun updateDeleteAndSaveButtonsState() {
        if (!::saveInstanceButton.isInitialized) return
        saveInstanceButton.isEnabled = isServerStarted && !isCurrentInstanceSaved
        val normalIconColor = if (isNightMode) Color.WHITE else Color.DKGRAY
        val disabledColor = if (isNightMode) Color.DKGRAY else Color.LTGRAY
        val iconColor = if (saveInstanceButton.isEnabled) normalIconColor else disabledColor
        saveInstanceButton.drawable?.setColorFilter(PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN))
        val canDelete = isServerStarted && isCurrentInstanceSaved
        instanceAdapter.setCurrentInstanceId(currentInstanceId, canDelete)
    }

    // ==================== 内置文件浏览器（ZIP/复制模式用） ====================
    private fun showFilePickerForPrivateDir() {
        val serverRoot = currentInstanceRootDir ?: unzippedDir
        if (!serverRoot.exists()) {
            Toast.makeText(this, "当前服务器根目录不存在", Toast.LENGTH_SHORT).show()
            return
        }
        showFileBrowser(serverRoot)
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

        val bgColor = if (isNightMode) Color.parseColor("#FF333333") else Color.WHITE
        val titleBarColor = if (isNightMode) Color.BLACK else Color.parseColor("#F5F5F5")
        val textColor = if (isNightMode) Color.WHITE else Color.BLACK
        val iconColor = if (isNightMode) Color.WHITE else Color.DKGRAY

        // 窗口背景直接使用 bgColor（不透明），dialogView 背景设为透明避免叠加
        dialogView.setBackgroundColor(Color.TRANSPARENT)
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

        // 设置窗口宽高均为屏幕的80%
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val dialogWidth = (screenWidth * 0.9).toInt()
        val dialogHeight = (screenHeight * 0.8).toInt()
        dialog.window?.setLayout(dialogWidth, dialogHeight)

        // 设置窗口背景色（不透明，与 bgColor 一致）
        dialog.window?.setBackgroundDrawable(ColorDrawable(bgColor))

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
            "html", "htm" -> HtmlFileOpenStrategy()
            else -> null
        }
    }

    // ==================== Uri 辅助方法 ====================
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

    // ==================== 屏幕旋转控制 ====================
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

    // ==================== 用户引导 ====================
    private fun isGuideShown(): Boolean {
        return prefs.getBoolean(KEY_GUIDE_SHOWN, false)
    }

    private fun markGuideShown() {
        prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
    }

    private fun isLandscapeGuideShown(): Boolean {
        return prefs.getBoolean(KEY_LANDSCAPE_GUIDE_SHOWN, false)
    }

    private fun markLandscapeGuideShown() {
        prefs.edit().putBoolean(KEY_LANDSCAPE_GUIDE_SHOWN, true).apply()
    }

    private fun startUserGuide() {
        if (isGuideShown() || isGuideRunning) return
        isGuideRunning = true
        isGuideActive = true

        if (!::selectDirButton.isInitialized ||
            !::selectFileButton.isInitialized ||
            !::instancesButton.isInitialized ||
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

    // ==================== 菜单 / PopupWindow ====================
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
                    onToggle.invoke()
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

    // ==================== 调试日志 ====================
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
}