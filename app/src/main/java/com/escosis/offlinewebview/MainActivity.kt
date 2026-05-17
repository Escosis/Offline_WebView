package com.escosis.offlinewebview

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : AppCompatActivity() {

    // GeckoView 相关
    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var geckoRuntime: GeckoRuntime
    private var localWebServer: LocalWebServer? = null
    private var rootUri: Uri? = null
    private var isServerStarted = false

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
    private lateinit var nightModeButton: ImageButton
    private lateinit var floatingBall: ImageView

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

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_GUIDE_SHOWN = "guide_shown"
        private const val KEY_LANDSCAPE_GUIDE_SHOWN = "landscape_guide_shown"
    }

    // 文件夹选择器
    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
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
                Toast.makeText(this, "未选择文件夹", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 文件选择器
    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val fileUri = result.data?.data
            if (fileUri != null && rootUri != null) {
                if (isFileUnderRoot(fileUri, rootUri!!)) {
                    val relativePath = getRelativePath(fileUri, rootUri!!) ?: ""
                    loadUrl("http://localhost:8080/$relativePath")
                } else {
                    Toast.makeText(this, "文件不在授权文件夹内", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show()
            }
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
            if (isBarVisible && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
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

        initViews()
        setupGeckoView()
        setupListeners()
        applyNightMode()
        updateUIAfterDirSelected()

        rootFrame.post {
            measureOriginalHeights()
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                hideSystemBars()
                if (isBarVisible) {
                    hideBars()
                } else {
                    floatingBall.visibility = View.VISIBLE
                }
            } else {
                showSystemBars()
                showBars()
            }
        }

        // 添加系统状态栏可见性监听，实现手动呼出后自动隐藏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    // 状态栏可见（用户手动呼出）
                    statusBarHideHandler.removeCallbacks(statusBarHideRunnable)
                    statusBarHideHandler.postDelayed(statusBarHideRunnable, 3000)
                } else {
                    statusBarHideHandler.removeCallbacks(statusBarHideRunnable)
                }
            }
        }

        // 首次启动通用指引
        if (!isGuideShown()) {
            window.decorView.post {
                if (!isGuideShown() && !isGuideRunning) {
                    startUserGuide()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                hideSystemBars()
                if (isBarVisible) {
                    hideBars()
                } else {
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
        nightModeButton = findViewById(R.id.nightModeButton)
        floatingBall = findViewById(R.id.floatingBall)
        floatingBall.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    }

    private fun measureOriginalHeights() {
        urlBarHeight = urlBar.height
        toolbarHeight = toolbar.height
        if (urlBarHeight == 0) {
            urlBar.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            toolbar.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            urlBarHeight = urlBar.measuredHeight
            toolbarHeight = toolbar.measuredHeight
        }
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !isBarVisible) {
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
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                return null
            }
        }

        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                runOnUiThread { updateUrlBar(url) }
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
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(urlEditText.windowToken, 0)
                Handler(Looper.getMainLooper()).postDelayed({
                    loadUserInputUrl(input)
                }, 200)
            }
        }
    }

    private fun setupListeners() {
        // 仅监听工具栏和地址栏相关控件的操作来重置计时器
        backButton.setOnClickListener {
            if (canGoBack) geckoSession.goBack()
            resetHideTimer()
        }
        forwardButton.setOnClickListener {
            if (canGoForward) geckoSession.goForward()
            resetHideTimer()
        }
        refreshButton.setOnClickListener {
            geckoSession.reload()
            resetHideTimer()
        }
        selectDirButton.setOnClickListener {
            openFolderPicker()
            resetHideTimer()
        }
        selectFileButton.setOnClickListener {
            if (rootUri != null && isServerStarted) openFilePickerInRoot()
            else Toast.makeText(this, "请先选择目录", Toast.LENGTH_SHORT).show()
            resetHideTimer()
        }
        nightModeButton.setOnClickListener {
            isNightMode = !isNightMode
            applyNightMode()
            prefs.edit().putBoolean("night_mode", isNightMode).apply()
            resetHideTimer()
        }
        urlEditText.setOnTouchListener { _, _ ->
            resetHideTimer()
            false
        }
        // 注意：不再监听 geckoView 的触摸，避免网页点击重置计时器

        floatingBall.setOnClickListener {
            if (!isBarVisible) {
                showBars()
            }
        }
    }

    private fun updateNavigationButtonsState() {
        backButton.isEnabled = canGoBack
        forwardButton.isEnabled = canGoForward
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

    private fun startServer() {
        localWebServer?.stop()
        if (rootUri == null) return
        val server = LocalWebServer(8080, rootUri!!, this)
        try {
            server.start()
            localWebServer = server
            isServerStarted = true
        } catch (e: Exception) {
            Toast.makeText(this, "服务器启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            isServerStarted = false
        }
    }

    private fun updateUIAfterDirSelected() {
        val enabled = rootUri != null && isServerStarted
        selectFileButton.isEnabled = enabled
        selectFileButton.alpha = if (enabled) 1.0f else 0.4f
        urlEditText.isEnabled = enabled
        if (enabled) {
            urlEditText.setText("http://localhost:8080/")
            urlEditText.hint = ""
        } else {
            urlEditText.setText("")
            urlEditText.hint = "请先选择服务器根目录"
            urlEditText.isEnabled = false
        }
        applyNightMode()
    }

    private fun loadUserInputUrl(input: String) {
        var url = input.trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.startsWith("/")) "http://localhost:8080$url"
            else "http://localhost:8080/$url"
        }
        if (url.startsWith("http://localhost:8080/") && rootUri != null) {
            loadUrl(url)
        } else {
            Toast.makeText(this, "仅允许访问本地服务器内容", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUrl(url: String) {
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
            urlBar.setBackgroundColor(Color.parseColor("#333333"))
            urlEditText.setBackgroundColor(Color.parseColor("#444444"))
            urlEditText.setTextColor(Color.WHITE)
            urlEditText.setHintTextColor(Color.LTGRAY)
            setStatusBarColor(Color.parseColor("#333333"))
            setNavigationBarColor(Color.BLACK)
        } else {
            rootFrame.setBackgroundColor(Color.WHITE)
            toolbar.setBackgroundColor(Color.WHITE)
            urlBar.setBackgroundColor(Color.parseColor("#F5F5F5"))
            urlEditText.setBackgroundColor(Color.WHITE)
            urlEditText.setTextColor(Color.BLACK)
            urlEditText.setHintTextColor(Color.GRAY)
            setStatusBarColor(Color.parseColor("#F5F5F5"))
            setNavigationBarColor(Color.WHITE)
        }

        setIconColor(backButton, if (canGoBack) normalIconColor else disabledColor)
        setIconColor(forwardButton, if (canGoForward) normalIconColor else disabledColor)
        setIconColor(refreshButton, normalIconColor)
        setIconColor(goButton, normalIconColor)

        if (isServerStarted) {
            selectDirButton.setColorFilter(Color.rgb(0, 75, 171), PorterDuff.Mode.SRC_IN)
        } else {
            setIconColor(selectDirButton, normalIconColor)
        }

        if (selectFileButton.isEnabled) {
            setIconColor(selectFileButton, normalIconColor)
        } else {
            setIconColor(selectFileButton, disabledColor)
        }

        if (isNightMode) {
            setIconColor(nightModeButton, Color.rgb(0, 75, 171))
        } else {
            setIconColor(nightModeButton, normalIconColor)
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
        }
    }

    private fun hideBars() {
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
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && isBarVisible) {
            hideHandler.removeCallbacks(hideRunnable)
            hideHandler.postDelayed(hideRunnable, 3000)
        }
    }

    // 横屏引导
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
                        .descriptionTextSize(14)
                        .textColorInt(Color.WHITE)
                        .cancelable(false)
                        .tintTarget(true)
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

    // 首次启动通用指引（竖屏锁定）
    private fun isGuideShown(): Boolean {
        return prefs.getBoolean(KEY_GUIDE_SHOWN, false)
    }

    private fun markGuideShown() {
        prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
        // 恢复屏幕旋转
        requestedOrientation = originalOrientation
    }

    private fun startUserGuide() {
        if (isGuideShown() || isGuideRunning) return
        isGuideRunning = true

        if (!::selectDirButton.isInitialized ||
            !::selectFileButton.isInitialized ||
            !::urlEditText.isInitialized
        ) {
            isGuideRunning = false
            return
        }

        // 锁定竖屏
        originalOrientation = requestedOrientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        try {
            TapTargetSequence(this)
                .targets(
                    TapTarget.forView(selectDirButton, "选择服务器根目录", "点击此按钮，选择一个文件夹作为本地服务器的根目录。建议选择 Download 文件夹下的任意目录。")
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
                        isGuideRunning = false
                        Toast.makeText(this@MainActivity, "指引完成！", Toast.LENGTH_SHORT).show()
                    }
                    override fun onSequenceStep(lastTarget: TapTarget?, targetClicked: Boolean) {}
                    override fun onSequenceCanceled(lastTarget: TapTarget?) {
                        markGuideShown()
                        isGuideRunning = false
                    }
                })
                .start()
        } catch (e: Exception) {
            e.printStackTrace()
            markGuideShown()
            isGuideRunning = false
        }
    }
}