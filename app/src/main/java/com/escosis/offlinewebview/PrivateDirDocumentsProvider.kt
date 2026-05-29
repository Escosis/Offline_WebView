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

class PrivateDirDocumentsProvider : DocumentsProvider() {

    companion object {
        private var rootDirectory: File? = null

        fun init(context: Context) {
            rootDirectory = File(context.filesDir, "instances")
            if (!rootDirectory!!.exists()) {
                rootDirectory!!.mkdirs()
            }
        }

        /**
         * 从系统返回的 Uri 中提取真实 File 对象
         */
        fun getFileFromUri(uri: Uri): File? {
            val documentId = DocumentsContract.getDocumentId(uri)
            val decoded = Uri.decode(documentId)
            val file = File(decoded)
            return if (file.exists()) file else null
        }
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val rootDir = rootDirectory ?: return MatrixCursor(projection ?: emptyArray())
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
                "instances_root",
                rootDir.absolutePath,
                "Offline WebView",
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
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
    ): String? = null

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return File(documentId).canonicalPath.startsWith(File(parentDocumentId).canonicalPath)
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}