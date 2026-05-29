package com.escosis.offlinewebview

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers

/**
 * 单例管理器，负责实例元数据的持久化和内存缓存
 */
object InstanceManager {
    private const val TAG = "InstanceManager"
    private const val INSTANCES_FILE = "instances.json"
    private lateinit var instancesDir: File
    private lateinit var instancesFile: File
    private val instances = mutableListOf<Instance>()
    private var isInitialized = false
    var logger: ((String) -> Unit)? = null

    /**
     * 必须在 Application 或 MainActivity 的 onCreate 中调用一次
     */
    fun init(context: Context) {
        if (isInitialized) return
        instancesDir = File(context.filesDir, "instances")
        if (!instancesDir.exists()) {
            instancesDir.mkdirs()
        }
        instancesFile = File(instancesDir, INSTANCES_FILE)
        loadFromFile()
        isInitialized = true
        log("InstanceManager 初始化完成，已加载 ${instances.size} 个实例")
    }

    /**
     * 从 JSON 文件加载实例列表
     */
    private fun loadFromFile() {
        if (!instancesFile.exists()) {
            instances.clear()
            saveToFile()
            return
        }
        try {
            val gson = Gson()
            val type = object : TypeToken<List<Instance>>() {}.type
            FileReader(instancesFile).use { reader ->
                val loaded: List<Instance>? = gson.fromJson(reader, type)
                if (loaded != null) {
                    instances.clear()
                    instances.addAll(loaded)
                } else {
                    instances.clear()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载实例文件失败", e)
            instances.clear()
        }
    }

    /**
     * 将当前实例列表保存到 JSON 文件
     */
    private fun saveToFile() {
        try {
            val gson = Gson()
            val json = gson.toJson(instances)
            FileWriter(instancesFile).use { writer ->
                writer.write(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存实例文件失败", e)
        }
    }

    /**
     * 获取所有实例，按创建时间升序排列（最早保存的在前）
     */
    fun getAllInstances(): List<Instance> {
        return instances.sortedBy { it.createdAt }
    }

    /**
     * 根据 ID 获取实例
     */
    fun getInstanceById(id: String): Instance? {
        return instances.find { it.id == id }
    }

    /**
     * 检查实例名称是否已存在（不区分大小写？根据要求，精确匹配）
     */
    fun isNameExists(name: String): Boolean {
        return instances.any { it.name.equals(name, ignoreCase = false) }
    }

    /**
     * 添加新实例
     * @return 成功 true，失败 false（例如名称重复）
     */
    fun addInstance(instance: Instance): Boolean {
        if (isNameExists(instance.name)) {
            log("实例名称已存在: ${instance.name}")
            return false
        }
        instances.add(instance)
        saveToFile()
        log("添加实例: ${instance.name}, ID=${instance.id}")
        return true
    }

    /**
     * 删除实例
     * @param instanceId 实例ID
     * @param deleteFiles 是否同时删除存储目录（默认为true）
     * @return 是否删除成功
     */
    fun deleteInstance(instanceId: String, deleteFiles: Boolean = true): Boolean {
        val instance = instances.find { it.id == instanceId } ?: return false
        if (deleteFiles && instance.storageDir.isNotBlank()) {
            val dir = File(instance.storageDir)
            if (dir.exists()) {
                val deleted = dir.deleteRecursively()
                if (!deleted) {
                    log("删除实例目录失败: ${instance.storageDir}")
                    // 即使目录删除失败，也继续删除元数据
                } else {
                    log("已删除实例目录: ${instance.storageDir}")
                }
            }
        }
        val removed = instances.removeIf { it.id == instanceId }
        if (removed) {
            saveToFile()
            log("删除实例: ${instance.name}")
        }
        return removed
    }

    /**
     * 更新实例的 sourceUri（用于重新授权后）
     */
    fun updateInstanceSourceUri(instanceId: String, newSourceUri: String): Boolean {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index == -1) return false
        val old = instances[index]
        val updated = old.copy(sourceUri = newSourceUri)
        instances[index] = updated
        saveToFile()
        log("更新实例 ${old.name} 的 sourceUri")
        return true
    }

    /**
     * 更新实例的 savedUrl（可选，后续可能用到）
     */
    fun updateInstanceSavedUrl(instanceId: String, newSavedUrl: String): Boolean {
        val index = instances.indexOfFirst { it.id == instanceId }
        if (index == -1) return false
        val old = instances[index]
        val updated = old.copy(savedUrl = newSavedUrl)
        instances[index] = updated
        saveToFile()
        return true
    }

    /**
     * 获取实例的存储目录 File 对象
     */
    fun getInstanceDir(instance: Instance): File? {
        return if (instance.storageDir.isNotBlank()) {
            File(instance.storageDir)
        } else null
    }

    /**
     * 保存 ZIP 解压模式的实例（移动 unzippedDir 到保存目录）
     * @param name 用户输入的实例名称
     * @param savedUrl 当前页面相对URL
     * @param unzippedDir 当前解压目录的 File 对象
     * @return 保存成功返回 Instance，失败返回 null
     */
    fun saveZipInstance(name: String, savedUrl: String, sourceDir: File): Instance? {
        if (isNameExists(name)) return null
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val timestamp = System.currentTimeMillis()
        val targetDirName = "${safeName}_$timestamp"
        val targetDir = File(instancesDir, targetDirName)

        val success = sourceDir.renameTo(targetDir)
        if (!success) return null

        val instance = Instance(
            name = name,
            type = "zip_move",
            storageDir = targetDir.absolutePath,
            savedUrl = savedUrl,
            isZipMode = true,
            createdAt = timestamp
        )
        return if (addInstance(instance)) instance else null
    }

    /**
     * 保存外部文件夹模式的引用实例（仅保存路径）
     * @param name 实例名称
     * @param savedUrl 当前页面相对URL
     * @param rootUri 当前服务器的根 Uri
     * @return 成功返回 Instance，失败返回 null
     */
    fun saveReferenceInstance(name: String, savedUrl: String, rootUri: Uri): Instance? {
        if (isNameExists(name)) return null

        val timestamp = System.currentTimeMillis()
        val instance = Instance(
            name = name,
            type = "reference",
            storageDir = "", // 引用模式没有本地存储目录
            sourceUri = rootUri.toString(),
            savedUrl = savedUrl,
            isZipMode = false,
            createdAt = timestamp
        )

        return if (addInstance(instance)) instance else null
    }

    /**
     * 复制 SAF 文件夹的所有内容到目标目录（可取消）
     * @param rootUri 源文件夹的 Uri
     * @param destDir 目标目录（已创建）
     * @param context Context
     * @param onProgress 进度回调，参数为当前文件名
     * @param isCancelled 检查是否取消的函数
     * @return true 成功，false 失败
     */
    suspend fun copyUriToDirectory(
        rootUri: Uri,
        destDir: File,
        context: Context,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            if (rootDoc == null || !rootDoc.exists()) {
                log("源文件夹不存在或无法访问")
                return@withContext false
            }
            return@withContext copyDocumentRecursively(rootDoc, destDir, context, onProgress, isCancelled)
        } catch (e: Exception) {
            log("复制失败: ${e.message}")
            return@withContext false
        }
    }

    private suspend fun copyDocumentRecursively(
        sourceDoc: DocumentFile,
        destDir: File,
        context: Context,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (isCancelled()) return@withContext false
        val children = sourceDoc.listFiles()
        for (child in children) {
            if (isCancelled()) return@withContext false

            val rawName = child.name ?: continue
            val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val destFile = File(destDir, safeName)

            try {
                if (child.isDirectory) {
                    if (!destFile.exists() && !destFile.mkdirs()) {
                        log("创建目录失败: ${destFile.absolutePath}")
                        return@withContext false
                    }
                    if (!copyDocumentRecursively(child, destFile, context, onProgress, isCancelled)) {
                        return@withContext false
                    }
                } else {
                    // 更新进度（必须在主线程）
                    withContext(Dispatchers.Main) {
                        onProgress(safeName)
                    }
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output, 32 * 1024)
                        }
                    } ?: throw Exception("无法打开输入流")
                }
            } catch (e: Exception) {
                log("复制失败: $rawName - ${e.message}")
                return@withContext false
            }
        }
        return@withContext true
    }

    // 添加保存复制实例的方法（步骤五完整实现）
    fun saveCopyInstance(
        name: String,
        savedUrl: String,
        rootUri: Uri,
        context: Context,
        onProgress: (String) -> Unit,
        onComplete: (Instance?) -> Unit,
        isCancelled: () -> Boolean
    ) {
        // 重复检查
        if (isNameExists(name)) {
            onComplete(null)
            return
        }

        // 准备目标目录
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val timestamp = System.currentTimeMillis()
        val targetDirName = "${safeName}_$timestamp"
        val targetDir = File(instancesDir, targetDirName)

        // 在后台线程执行复制
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // 创建目标目录
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    log("创建目标目录失败")
                    withContext(Dispatchers.Main) { onComplete(null) }
                    return@launch
                }

                // 执行复制
                val success = copyUriToDirectory(rootUri, targetDir, context, onProgress, isCancelled)

                if (success && !isCancelled()) {
                    // 创建实例
                    val instance = Instance(
                        name = name,
                        type = "copy",
                        storageDir = targetDir.absolutePath,
                        savedUrl = savedUrl,
                        isZipMode = false,
                        createdAt = timestamp
                    )
                    val added = addInstance(instance)
                    withContext(Dispatchers.Main) {
                        onComplete(if (added) instance else null)
                    }
                } else {
                    // 复制失败或取消，清理目标目录
                    targetDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        onComplete(null)
                    }
                }
            } catch (e: Exception) {
                log("复制过程异常: ${e.message}")
                targetDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    onComplete(null)
                }
            }
        }
    }

    private fun log(message: String) {
        // 优先使用外部 logger（如 MainActivity 的 log 方法）
        logger?.invoke(message) ?: Log.d(TAG, message)
    }
}