package com.escosis.offlinewebview   // 替换为你的包名

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.AllowOrDeny

class MainActivity : AppCompatActivity() {

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
    private lateinit var rootLayout: LinearLayout
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

    // 夜间模式
    private lateinit var prefs: SharedPreferences
    private var isNightMode = false

    // 指引相关
    private var isGuideRunning = false

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_GUIDE_SHOWN = "guide_shown"
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

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isNightMode = prefs.getBoolean("night_mode", false)

        initViews()
        setupGeckoView()
        setupListeners()
        applyNightMode()
        updateUIAfterDirSelected()

        if (!isGuideShown()) {
            window.decorView.post {
                if (!isGuideShown() && !isGuideRunning) {
                    startUserGuide()
                }
            }
        }
    }

    private fun initViews() {
        rootLayout = findViewById(R.id.rootLayout)
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
    }

    private fun setupGeckoView() {
        geckoView = findViewById(R.id.geckoview)
        geckoRuntime = GeckoRuntime.create(this)
        geckoSession = GeckoSession()
        geckoSession.open(geckoRuntime)
        geckoView.setSession(geckoSession)
        geckoSession.settings.setAllowJavascript(true)

        // 设置 NavigationDelegate 监听后退/前进能力变化
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
                // 允许所有加载请求
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            // 修正：只使用两个参数，没有 WebResponseInfo
            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                // 不处理新窗口，返回 null 让 GeckoView 自动处理
                return null
            }
        }

        // 监听页面 URL 变化
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                runOnUiThread { updateUrlBar(url) }
            }
        }

        // 地址栏回车
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
        backButton.setOnClickListener {
            if (canGoBack) geckoSession.goBack()
        }
        forwardButton.setOnClickListener {
            if (canGoForward) geckoSession.goForward()
        }
        refreshButton.setOnClickListener {
            geckoSession.reload()
        }
        selectDirButton.setOnClickListener {
            openFolderPicker()
        }
        selectFileButton.setOnClickListener {
            if (rootUri != null && isServerStarted) openFilePickerInRoot()
            else Toast.makeText(this, "请先选择目录", Toast.LENGTH_SHORT).show()
        }
        nightModeButton.setOnClickListener {
            isNightMode = !isNightMode
            applyNightMode()
            prefs.edit().putBoolean("night_mode", isNightMode).apply()
        }
    }

    private fun updateNavigationButtonsState() {
        backButton.isEnabled = canGoBack
        forwardButton.isEnabled = canGoForward
        // 刷新图标颜色（因为启用状态改变）
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

    // Document ID 辅助方法
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
        localWebServer?.stop()
        geckoSession.close()
    }

    // 状态栏与导航栏
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

        // 背景设置
        if (isNightMode) {
            toolbar.setBackgroundColor(Color.BLACK)
            rootLayout.setBackgroundColor(Color.BLACK)
            urlBar.setBackgroundColor(Color.parseColor("#333333"))
            urlEditText.setBackgroundColor(Color.parseColor("#444444"))
            urlEditText.setTextColor(Color.WHITE)
            urlEditText.setHintTextColor(Color.LTGRAY)
            setStatusBarColor(Color.parseColor("#333333"))
            setNavigationBarColor(Color.BLACK)
        } else {
            toolbar.setBackgroundColor(Color.WHITE)
            rootLayout.setBackgroundColor(Color.WHITE)
            urlBar.setBackgroundColor(Color.parseColor("#F5F5F5"))
            urlEditText.setBackgroundColor(Color.WHITE)
            urlEditText.setTextColor(Color.BLACK)
            urlEditText.setHintTextColor(Color.GRAY)
            setStatusBarColor(Color.parseColor("#F5F5F5"))
            setNavigationBarColor(Color.WHITE)
        }

        // 后退/前进按钮（根据 canGoBack/canGoForward 状态）
        setIconColor(backButton, if (canGoBack) normalIconColor else disabledColor)
        setIconColor(forwardButton, if (canGoForward) normalIconColor else disabledColor)
        setIconColor(refreshButton, normalIconColor)
        setIconColor(goButton, normalIconColor)

        // 目录按钮
        if (isServerStarted) {
            selectDirButton.setColorFilter(Color.rgb(0, 75, 171), PorterDuff.Mode.SRC_IN)
        } else {
            setIconColor(selectDirButton, normalIconColor)
        }

        // 文件按钮
        if (selectFileButton.isEnabled) {
            setIconColor(selectFileButton, normalIconColor)
        } else {
            setIconColor(selectFileButton, disabledColor)
        }

        // 夜间模式按钮
        if (isNightMode) {
            setIconColor(nightModeButton, Color.rgb(0, 75, 171))
        } else {
            setIconColor(nightModeButton, normalIconColor)
        }
    }

    // 首次启动指引
    private fun isGuideShown(): Boolean {
        return prefs.getBoolean(KEY_GUIDE_SHOWN, false)
    }

    private fun markGuideShown() {
        prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
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