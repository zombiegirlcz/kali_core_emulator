package com.linux_core.core

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

class DistroDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    private fun getFileForDocId(documentId: String): File {
        val context = context ?: throw FileNotFoundException("No context available")
        val file = File(documentId)
        // Security check: must reside inside context.filesDir
        val filesDirCanonical = context.filesDir.canonicalPath
        val fileCanonical = file.canonicalPath
        if (!fileCanonical.startsWith(filesDirCanonical)) {
            throw FileNotFoundException("Access denied: path is outside files directory")
        }
        return file
    }

    private fun getDocIdForFile(file: File): String {
        return file.absolutePath
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val context = context ?: throw FileNotFoundException("No context available")
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        for (distro in RootfsManager.DISTROS) {
            if (RootfsManager.isRootfsExtracted(context, distro)) {
                val rootfsDir = File(context.filesDir, distro.rootfsDirName)
                val row = result.newRow()
                row.add(DocumentsContract.Root.COLUMN_ROOT_ID, distro.id)
                row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, getDocIdForFile(rootfsDir))
                row.add(DocumentsContract.Root.COLUMN_FLAGS, 
                    DocumentsContract.Root.FLAG_SUPPORTS_CREATE or 
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                )
                row.add(DocumentsContract.Root.COLUMN_TITLE, distro.name)
                row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_dialog_info)
                
                try {
                    val stat = android.os.StatFs(rootfsDir.absolutePath)
                    row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, stat.availableBytes)
                } catch (_: Exception) {}
            }
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = getFileForDocId(documentId)
        includeFile(result, documentId, file)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)
        if (parent.isDirectory) {
            val files = parent.listFiles()
            if (files != null) {
                for (file in files) {
                    includeFile(result, null, file)
                }
            }
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = getFileForDocId(parentDocumentId)
        val file = File(parent, displayName)
        if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
            if (!file.mkdir()) {
                throw FileNotFoundException("Failed to create directory: ${file.absolutePath}")
            }
        } else {
            try {
                if (!file.createNewFile()) {
                    throw FileNotFoundException("Failed to create file: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                throw FileNotFoundException("Failed to create file: ${e.message}")
            }
        }
        return getDocIdForFile(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        if (file.isDirectory) {
            if (!file.deleteRecursively()) {
                throw FileNotFoundException("Failed to delete directory recursively: $documentId")
            }
        } else {
            if (!file.delete()) {
                throw FileNotFoundException("Failed to delete file: $documentId")
            }
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = getFileForDocId(documentId)
        val newFile = File(file.parentFile, displayName)
        if (!file.renameTo(newFile)) {
            throw FileNotFoundException("Failed to rename file from $documentId to ${newFile.absolutePath}")
        }
        return getDocIdForFile(newFile)
    }

    private fun includeFile(result: MatrixCursor.RowBuilder, docId: String?, file: File) {
        val resolvedDocId = docId ?: getDocIdForFile(file)
        result.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, resolvedDocId)
        result.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
        result.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())

        if (file.isDirectory) {
            result.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            result.add(DocumentsContract.Document.COLUMN_FLAGS, 
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or 
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            )
            result.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
        } else {
            val extension = file.extension
            val mime = if (extension.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            } else {
                null
            }
            result.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime ?: "application/octet-stream")
            result.add(DocumentsContract.Document.COLUMN_FLAGS, 
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or 
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            )
            result.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        }
    }

    private fun includeFile(result: MatrixCursor, docId: String?, file: File) {
        includeFile(result.newRow(), docId, file)
    }

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
}
