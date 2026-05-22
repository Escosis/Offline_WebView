package com.escosis.offlinewebview

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 自定义 DocumentsProvider，用于暴露 App 私有目录下的文件。
 * 可通过系统文件选择器浏览并选择文件（例如 HTML 文件）。
 *
 * 使用流程：
 * 1. 解压 ZIP 成功后调用 PrivateDirDocumentsProvider.setRootDirectory(unzippedDir)
 * 2. 在需要选择文件时，启动 Intent.ACTION_OPEN_DOCUMENT_TREE，
 *    并将 EXTRA_INITIAL_URI 设为 getRootDocumentUri(context)
 * 3. 用户授权后，回调 Uri 的 documentId 即为文件的绝对路径，
 *    可通过 getFileFromUri(uri) 获取真实 File 对象。
 */
class PrivateDirDocumentsProvider : DocumentsProvider() {

    companion object {
        private var rootDirectory: File? = null

        /** 设置根目录（解压成功后的私有目录） */
        fun setRootDirectory(dir: File) {
            rootDirectory = dir
        }

        /** 获取根文档的 Uri，用于文件选择器的初始路径 */
        fun getRootDocumentUri(context: Context): Uri? {
            val rootDir = rootDirectory ?: return null
            // 注意：这里使用标准的 root 路径格式，系统才能识别
            return Uri.parse("content://${context.packageName}.provider/root/private_root")
        }

        /**
         * 从系统文件选择器返回的 Uri 中提取真实的 File 对象
         * @param uri 选择器返回的 Uri（例如 content://.../document/%2Fdata%2F...）
         * @return 对应的 File 对象，如果 Uri 无效或文件不存在则返回 null
         */
        fun getFileFromUri(uri: Uri): File? {
            val documentId = DocumentsContract.getDocumentId(uri)
            // documentId 可能被 URL 编码，需要解码
            val decoded = android.net.Uri.decode(documentId)
            val file = File(decoded)
            return if (file.exists()) file else null
        }
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val rootDir = rootDirectory
        if (rootDir == null || !rootDir.exists()) {
            return MatrixCursor(projection ?: emptyArray())
        }

        val cols = projection ?: arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )
        val cursor = MatrixCursor(cols)
        cursor.addRow(
            arrayOf(
                "private_root",                     // ROOT_ID
                rootDir.absolutePath,               // DOCUMENT_ID（根文档标识）
                "Offline WebView",                     // 显示名称
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
                null,
                "*/*"
            )
        )
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = File(documentId)
        val cols = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val cursor = MatrixCursor(cols)
        cursor.addRow(
            arrayOf(
                documentId,
                file.name,
                file.length(),
                if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else getMimeType(file.name),
                file.lastModified()
            )
        )
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parentFile = File(parentDocumentId)
        val children = parentFile.listFiles() ?: return MatrixCursor(projection ?: emptyArray())
        val cols = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val cursor = MatrixCursor(cols)
        for (child in children) {
            cursor.addRow(
                arrayOf(
                    child.absolutePath,
                    child.name,
                    child.length(),
                    if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else getMimeType(child.name),
                    child.lastModified()
                )
            )
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = File(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun deleteDocument(documentId: String) {
        val file = File(documentId)
        if (!file.delete()) {
            throw FileNotFoundException("Failed to delete $documentId")
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String? {
        // 暂不支持创建文件
        return null
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return File(documentId).canonicalPath.startsWith(File(parentDocumentId).canonicalPath)
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}