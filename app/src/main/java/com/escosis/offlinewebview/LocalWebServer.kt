package com.escosis.offlinewebview

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class LocalWebServer(
    private val port: Int,
    private val rootUri: Uri,
    private val context: Context
) : NanoHTTPD(port) {

    private val contentResolver: ContentResolver = context.contentResolver

    // 路径 -> DocumentFile 缓存
    private val docCache = ConcurrentHashMap<String, DocumentFile>()

    // 内容缓存：路径 -> 字节数组（小文件）
    private val contentCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > 200
        }
    }

    // HTML 注入结果缓存
    private val htmlCache = ConcurrentHashMap<String, String>()

    // 最大缓存文件大小（字节）
    private val MAX_CACHE_SIZE = 200 * 1024  // 200KB

    override fun serve(session: IHTTPSession): Response {
        var uri = session.uri
        if (uri == "/") uri = "/index.html"
        val relativePath = uri.removePrefix("/")
        println("LocalWebServer: 请求相对路径 = $relativePath")

        // 1. 获取 DocumentFile（从缓存或查找）
        val targetDoc = docCache[relativePath] ?: findDocumentByPath(rootUri, relativePath)?.also {
            docCache[relativePath] = it
            println("LocalWebServer: 缓存路径 $relativePath")
        }

        if (targetDoc == null || !targetDoc.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found: $relativePath")
        }
        if (targetDoc.isDirectory) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "403 Forbidden: cannot list directory")
        }

        val mimeType = guessMimeType(relativePath)

        // 2. 处理 HTML（特殊注入，且可缓存）
        if (mimeType == "text/html") {
            // 尝试从 HTML 缓存获取
            val cachedHtml = htmlCache[relativePath]
            if (cachedHtml != null) {
                println("LocalWebServer: HTML 缓存命中 $relativePath")
                return newFixedLengthResponse(Response.Status.OK, mimeType, cachedHtml)
            }

            // 否则读取文件，注入脚本，并缓存
            return try {
                val inputStream = contentResolver.openInputStream(targetDoc.uri) ?: throw Exception("无法打开文件流")
                val htmlContent = inputStream.bufferedReader().use { it.readText() }
                val injectedHtml = injectSelectPolyfill(htmlContent)
                if (htmlContent.length < 512 * 1024) {
                    htmlCache[relativePath] = injectedHtml
                }
                newFixedLengthResponse(Response.Status.OK, mimeType, injectedHtml)
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500 Error: ${e.message}")
            }
        }

        // 3. 处理非 HTML 资源（图片、CSS、JS 等）
        // 尝试从内容缓存获取
        val cachedContent = synchronized(contentCache) { contentCache[relativePath] }
        if (cachedContent != null) {
            println("LocalWebServer: 内容缓存命中 $relativePath (${cachedContent.size} bytes)")
            // 修正：将字节数组转为 InputStream 并指定长度
            return newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(cachedContent),
                cachedContent.size.toLong()
            )
        }

        // 未命中缓存，读取文件并决定是否缓存
        return try {
            val inputStream = contentResolver.openInputStream(targetDoc.uri) ?: throw Exception("无法打开文件流")
            val fileSize = inputStream.available().toLong()
            if (fileSize <= 0 || fileSize > MAX_CACHE_SIZE) {
                println("LocalWebServer: 流式传输 (大小>${MAX_CACHE_SIZE}或未知) $relativePath")
                newChunkedResponse(Response.Status.OK, mimeType, inputStream)
            } else {
                val bytes = inputStream.readBytes()
                synchronized(contentCache) {
                    contentCache[relativePath] = bytes
                }
                println("LocalWebServer: 已缓存文件 $relativePath (${bytes.size} bytes)")
                // 同样使用 InputStream 方式返回
                newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500 Error: ${e.message}")
        }
    }

    /**
     * 在 </body> 前注入自定义 select 模拟脚本
     */
    private fun injectSelectPolyfill(html: String): String {
        val polyfillScript = """
        <script>
        (function() {
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

            function replaceSelects() {
                var selects = document.querySelectorAll('select:not([data-replaced])');
                selects.forEach(function(select) {
                    var origWidth = select.offsetWidth;
                    var origHeight = select.offsetHeight;
                    if (!origWidth || !origHeight) return;

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

                    // 显示值区域 —— 完整继承文字及内边距
                    var valueDiv = document.createElement('div');
                    valueDiv.className = 'custom-select-value';
                    valueDiv.style.fontFamily = computed.fontFamily;
                    valueDiv.style.fontSize = computed.fontSize;
                    valueDiv.style.fontWeight = computed.fontWeight;
                    valueDiv.style.lineHeight = computed.lineHeight;
                    valueDiv.style.color = computed.color;
                    valueDiv.style.backgroundColor = computed.backgroundColor;

                    // ★ 关键修复：继承原生 select 的左右内边距
                    var padLeft = parseFloat(computed.paddingLeft) || 0;
                    var padRight = parseFloat(computed.paddingRight) || 0;
                    valueDiv.style.paddingLeft = padLeft + 'px';
                    // 右侧需为箭头预留空间（箭头宽度 20px）
                    valueDiv.style.paddingRight = (padRight + 20) + 'px';

                    valueDiv.textContent = select.options[select.selectedIndex]?.text || '请选择';

                    // 箭头 —— 动态字体大小
                    var arrow = document.createElement('div');
                    arrow.className = 'custom-select-arrow';
                    arrow.textContent = '▼';
                    var fontSizePx = parseFloat(computed.fontSize);
                    arrow.style.fontSize = (fontSizePx * 0.8) + 'px';

                    // 下拉选项
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
                });
            }

            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', replaceSelects);
            } else {
                replaceSelects();
            }
            var observer = new MutationObserver(function() { replaceSelects(); });
            observer.observe(document.body, { childList: true, subtree: true });
        })();
        </script>
    """.trimIndent()

        return if (html.contains("</body>", ignoreCase = true)) {
            html.replace("</body>", "$polyfillScript</body>", ignoreCase = true)
        } else {
            "$html$polyfillScript"
        }
    }

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
            else -> "text/plain"
        }
    }

    override fun stop() {
        super.stop()
        docCache.clear()
        contentCache.clear()
        htmlCache.clear()
        println("LocalWebServer: 已停止并清空所有缓存")
    }
}