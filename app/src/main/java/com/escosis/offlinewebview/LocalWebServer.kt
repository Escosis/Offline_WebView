package com.escosis.offlinewebview

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

interface DebugLogger {
    fun log(message: String)
}

class LocalWebServer(
    private val port: Int,
    private val rootUri: Uri? = null,          // SAF 模式根目录
    private val rootFile: File? = null,         // 文件模式根目录
    private val context: Context,
    private val debugLogger: DebugLogger? = null
) : NanoHTTPD(port) {

    private val contentResolver: ContentResolver = context.contentResolver
    private val isUriMode = rootUri != null

    // 缓存：Uri 模式下的 DocumentFile 对象
    private val docCache = ConcurrentHashMap<String, DocumentFile>()

    // 内容缓存（小文件字节数组，LRU 策略）
    private val contentCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > 200
        }
    }

    // HTML 注入结果缓存
    private val htmlCache = ConcurrentHashMap<String, String>()

    // 最大缓存文件大小（字节）
    private val MAX_CACHE_SIZE = 200 * 1024  // 200KB

    private fun log(message: String) {
        debugLogger?.log("[Server] $message")
        println("[LocalWebServer] $message")
    }

    private fun serveSwfPlayer(): Response {
        return try {
            val htmlContent = context.assets.open("swf_player.html").bufferedReader().use { it.readText() }
            newFixedLengthResponse(Response.Status.OK, "text/html", htmlContent)
        } catch (e: Exception) {
            log("加载播放器页面失败: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to load player")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        var uri = session.uri
        // 内置 Ruffle 播放器路由
        if (uri == "/__ruffle_player__" || uri.startsWith("/__ruffle_player__?")) {
            return serveSwfPlayer()
        }
        if (uri == "/") uri = "/index.html"
        val relativePath = uri.removePrefix("/")
        log("请求: $relativePath")

        return if (isUriMode && rootUri != null) {
            serveFromUri(relativePath)
        } else if (rootFile != null && rootFile.exists()) {
            serveFromFile(relativePath)
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server not initialized")
        }
    }

    // ==================== Uri 模式（原有逻辑，仅提取） ====================
    private fun serveFromUri(relativePath: String): Response {
        val targetDoc = docCache[relativePath] ?: findDocumentByPath(rootUri!!, relativePath)?.also {
            docCache[relativePath] = it
            log("缓存文件查找结果: $relativePath -> ${it.uri}")
        }

        if (targetDoc == null || !targetDoc.exists()) {
            log("文件不存在: $relativePath")
            return newErrorRedirect("error://404?path=$relativePath")
        }

        if (targetDoc.isDirectory) {
            log("禁止访问目录: $relativePath")
            return newErrorRedirect("error://403?path=$relativePath")
        }

        val mimeType = guessMimeType(relativePath)

        // HTML 特殊处理
        if (mimeType == "text/html") {
            val cachedHtml = htmlCache[relativePath]
            if (cachedHtml != null) {
                log("HTML 缓存命中: $relativePath")
                return newFixedLengthResponse(Response.Status.OK, mimeType, cachedHtml)
            }
            return try {
                val inputStream = contentResolver.openInputStream(targetDoc.uri)
                    ?: throw Exception("无法打开文件流")
                val htmlContent = inputStream.bufferedReader().use { it.readText() }
                log("读取 HTML 文件: $relativePath (${htmlContent.length} 字符)")
                val injectedHtml = injectSelectPolyfill(htmlContent)
                if (htmlContent.length < 512 * 1024) {
                    htmlCache[relativePath] = injectedHtml
                    log("HTML 已缓存: $relativePath")
                }
                newFixedLengthResponse(Response.Status.OK, mimeType, injectedHtml)
            } catch (e: Exception) {
                log("处理 HTML 出错: $relativePath - ${e.message}")
                newErrorRedirect("error://500?path=$relativePath")
            }
        }

        // 非 HTML 资源（图片、CSS、JS 等）
        val cachedContent = synchronized(contentCache) { contentCache[relativePath] }
        if (cachedContent != null) {
            log("内容缓存命中: $relativePath (${cachedContent.size} bytes)")
            return newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(cachedContent),
                cachedContent.size.toLong()
            )
        }

        return try {
            val inputStream = contentResolver.openInputStream(targetDoc.uri)
                ?: throw Exception("无法打开文件流")
            val fileSize = inputStream.available().toLong()
            if (fileSize <= 0 || fileSize > MAX_CACHE_SIZE) {
                log("流式传输 (大小 > ${MAX_CACHE_SIZE} 或未知): $relativePath ($fileSize bytes)")
                newChunkedResponse(Response.Status.OK, mimeType, inputStream)
            } else {
                val bytes = inputStream.readBytes()
                synchronized(contentCache) {
                    contentCache[relativePath] = bytes
                }
                log("已缓存文件: $relativePath (${bytes.size} bytes)")
                newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            log("读取文件出错: $relativePath - ${e.message}")
            newErrorRedirect("error://500?path=$relativePath")
        }
    }

    // ==================== 文件模式（新增，基于 File API） ====================
    private fun serveFromFile(relativePath: String): Response {
        val targetFile = File(rootFile!!, relativePath)
        if (!targetFile.exists()) {
            log("文件不存在: $relativePath")
            return newErrorRedirect("error://404?path=$relativePath")
        }
        if (targetFile.isDirectory) {
            log("禁止访问目录: $relativePath")
            return newErrorRedirect("error://403?path=$relativePath")
        }

        val mimeType = guessMimeType(relativePath)

        // HTML 特殊处理
        if (mimeType == "text/html") {
            val cachedHtml = htmlCache[relativePath]
            if (cachedHtml != null) {
                log("HTML 缓存命中: $relativePath")
                return newFixedLengthResponse(Response.Status.OK, mimeType, cachedHtml)
            }
            return try {
                val htmlContent = targetFile.readText()
                log("读取 HTML 文件: $relativePath (${htmlContent.length} 字符)")
                val injectedHtml = injectSelectPolyfill(htmlContent)
                if (htmlContent.length < 512 * 1024) {
                    htmlCache[relativePath] = injectedHtml
                    log("HTML 已缓存: $relativePath")
                }
                newFixedLengthResponse(Response.Status.OK, mimeType, injectedHtml)
            } catch (e: Exception) {
                log("处理 HTML 出错: $relativePath - ${e.message}")
                newErrorRedirect("error://500?path=$relativePath")
            }
        }

        // 非 HTML 资源
        val cachedContent = synchronized(contentCache) { contentCache[relativePath] }
        if (cachedContent != null) {
            log("内容缓存命中: $relativePath (${cachedContent.size} bytes)")
            return newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(cachedContent),
                cachedContent.size.toLong()
            )
        }

        return try {
            val fileSize = targetFile.length()
            if (fileSize <= 0 || fileSize > MAX_CACHE_SIZE) {
                log("流式传输 (大小 > ${MAX_CACHE_SIZE} 或未知): $relativePath ($fileSize bytes)")
                newChunkedResponse(Response.Status.OK, mimeType, targetFile.inputStream())
            } else {
                val bytes = targetFile.readBytes()
                synchronized(contentCache) {
                    contentCache[relativePath] = bytes
                }
                log("已缓存文件: $relativePath (${bytes.size} bytes)")
                newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            log("读取文件出错: $relativePath - ${e.message}")
            newErrorRedirect("error://500?path=$relativePath")
        }
    }

    // 辅助方法：生成错误重定向响应
    private fun newErrorRedirect(location: String): Response {
        val response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
        response.addHeader("Location", location)
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        return response
    }

    // 原有的 findDocumentByPath 方法（不变）
    private fun findDocumentByPath(rootUri: Uri, relativePath: String): DocumentFile? {
        var currentDir = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            val next = currentDir.findFile(part)
            if (next == null || !next.exists()) return null
            if (next.isDirectory) {
                currentDir = next
            } else {
                if (parts.last() == part) return next
                else return null
            }
        }
        return currentDir
    }

    // 原有的 MIME 类型猜测（不变）
    private fun guessMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".html") || fileName.endsWith(".htm") -> "text/html"
            fileName.endsWith(".css") -> "text/css"
            fileName.endsWith(".js") -> "application/javascript"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".gif") -> "image/gif"
            fileName.endsWith(".webp") -> "image/webp"
            fileName.endsWith(".svg") -> "image/svg+xml"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".swf") -> "application/x-shockwave-flash"
            else -> "text/plain"
        }
    }

    // 原有的 HTML 注入脚本（不变）
    private fun injectSelectPolyfill(html: String): String {
        val globalStyle = """
        <style>
            html, body {
                background-color: transparent !important;
            }
        </style>
    """.trimIndent()

        val polyfillScript = """
        <script>
        (function() {
            // 样式注入
            var baseStyle = document.createElement('style');
            baseStyle.textContent = `
                .custom-select-container {
                    position: relative;
                    display: inline-block;
                    cursor: pointer;
                    user-select: none;
                    box-sizing: border-box;
                    vertical-align: middle;
                }
                .custom-select-value {
                    position: absolute;
                    top: 0; left: 0; right: 0; bottom: 0;
                    display: flex;
                    align-items: center;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    box-sizing: border-box;
                }
                .custom-select-arrow {
                    position: absolute;
                    right: 0; top: 0; bottom: 0;
                    width: 20px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    pointer-events: none;
                    color: #666;
                }
                .custom-select-options {
                    position: absolute;
                    top: 100%;
                    left: 0; right: 0;
                    background: white;
                    border: 1px solid #ccc;
                    border-top: none;
                    border-radius: 0 0 4px 4px;
                    max-height: 200px;
                    overflow-y: auto;
                    z-index: 10000;
                    display: none;
                    box-shadow: 0 2px 5px rgba(0,0,0,0.2);
                    box-sizing: border-box;
                }
                .custom-select-option {
                    cursor: pointer;
                    white-space: nowrap;
                    padding: 4px 8px;
                }
                .custom-select-option:hover {
                    background-color: #f0f0f0;
                }
            `;
            document.head.appendChild(baseStyle);

            // 替换单个 select 元素
            function replaceSelect(select) {
                if (select.getAttribute('data-replaced') === 'true') return;
                
                var origWidth = select.offsetWidth;
                var origHeight = select.offsetHeight;
                // 如果元素尚未渲染，延迟重试
                if (!origWidth || !origHeight) {
                    setTimeout(function() { replaceSelect(select); }, 50);
                    return;
                }

                select.setAttribute('data-replaced', 'true');
                var computed = getComputedStyle(select);

                // 容器
                var container = document.createElement('div');
                container.className = 'custom-select-container';
                container.style.width = origWidth + 'px';
                container.style.height = origHeight + 'px';
                container.style.border = computed.border;
                container.style.backgroundColor = computed.backgroundColor;
                container.style.borderRadius = computed.borderRadius;

                // 显示值区域
                var valueDiv = document.createElement('div');
                valueDiv.className = 'custom-select-value';
                valueDiv.style.fontFamily = computed.fontFamily;
                valueDiv.style.fontSize = computed.fontSize;
                valueDiv.style.fontWeight = computed.fontWeight;
                valueDiv.style.lineHeight = computed.lineHeight;
                valueDiv.style.color = computed.color;
                valueDiv.style.backgroundColor = computed.backgroundColor;

                var padLeft = parseFloat(computed.paddingLeft) || 0;
                var padRight = parseFloat(computed.paddingRight) || 0;
                valueDiv.style.paddingLeft = padLeft + 'px';
                valueDiv.style.paddingRight = (padRight + 20) + 'px';
                valueDiv.textContent = select.options[select.selectedIndex]?.text || '请选择';

                // 箭头
                var arrow = document.createElement('div');
                arrow.className = 'custom-select-arrow';
                arrow.textContent = '▼';
                var fontSizePx = parseFloat(computed.fontSize);
                arrow.style.fontSize = (fontSizePx * 0.8) + 'px';

                // 选项列表
                var optionsDiv = document.createElement('div');
                optionsDiv.className = 'custom-select-options';
                var optionStyle = select.options.length > 0 ? getComputedStyle(select.options[0]) : null;
                Array.from(select.options).forEach(function(option, idx) {
                    var optDiv = document.createElement('div');
                    optDiv.className = 'custom-select-option';
                    optDiv.textContent = option.text;
                    if (optionStyle) {
                        optDiv.style.fontSize = optionStyle.fontSize;
                        optDiv.style.fontWeight = optionStyle.fontWeight;
                        optDiv.style.fontFamily = optionStyle.fontFamily;
                        optDiv.style.color = optionStyle.color;
                    } else {
                        optDiv.style.fontSize = computed.fontSize;
                        optDiv.style.fontWeight = computed.fontWeight;
                        optDiv.style.fontFamily = computed.fontFamily;
                        optDiv.style.color = computed.color;
                    }
                    optDiv.addEventListener('click', function(e) {
                        e.stopPropagation();
                        select.selectedIndex = idx;
                        valueDiv.textContent = option.text;
                        var event = new Event('change', { bubbles: true });
                        select.dispatchEvent(event);
                        optionsDiv.style.display = 'none';
                    });
                    optionsDiv.appendChild(optDiv);
                });

                container.appendChild(valueDiv);
                container.appendChild(arrow);
                container.appendChild(optionsDiv);

                container.addEventListener('click', function(e) {
                    e.stopPropagation();
                    document.querySelectorAll('.custom-select-options').forEach(function(o) {
                        if (o !== optionsDiv) o.style.display = 'none';
                    });
                    optionsDiv.style.display = optionsDiv.style.display === 'none' ? 'block' : 'none';
                });
                document.addEventListener('click', function() {
                    optionsDiv.style.display = 'none';
                });

                select.style.display = 'none';
                select.parentNode.insertBefore(container, select);
            }

            // 处理所有尚未替换的 select
            function replaceSelects() {
                var selects = document.querySelectorAll('select:not([data-replaced])');
                selects.forEach(function(select) {
                    replaceSelect(select);
                });
            }

            // 初始化：在 DOM 加载完成后执行
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', replaceSelects);
            } else {
                replaceSelects();
            }

            // 监听动态添加的节点，对新出现的 select 进行替换
            var observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(mutation) {
                    mutation.addedNodes.forEach(function(node) {
                        if (node.nodeType === Node.ELEMENT_NODE) {
                            if (node.matches && node.matches('select')) {
                                replaceSelect(node);
                            } else if (node.querySelectorAll) {
                                node.querySelectorAll('select').forEach(function(select) {
                                    replaceSelect(select);
                                });
                            }
                        }
                    });
                });
            });
            observer.observe(document.body, { childList: true, subtree: true });

            // 页面完全加载后再次尝试，确保所有元素都被处理（尤其是懒加载的）
            if (document.readyState !== 'complete') {
                window.addEventListener('load', function() {
                    replaceSelects();
                });
            } else {
                replaceSelects();
            }
        })();
        </script>
    """.trimIndent()

        val fullInjection = "$globalStyle$polyfillScript"
        return if (html.contains("</body>", ignoreCase = true)) {
            html.replace("</body>", "$fullInjection</body>", ignoreCase = true)
        } else {
            "$html$fullInjection"
        }
    }

    override fun stop() {
        super.stop()
        docCache.clear()
        contentCache.clear()
        htmlCache.clear()
        log("服务器已停止，已清空所有缓存")
    }
}