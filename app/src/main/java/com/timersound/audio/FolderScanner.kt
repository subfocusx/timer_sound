package com.timersound.audio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.timersound.AppLog
import com.timersound.model.FolderEntry

/**
 * Результат сканирования папки SAF.
 */
sealed interface FolderScanResult {
    /** Папка доступна; files — список аудиофайлов первого уровня, отсортированный по имени. */
    data class Ok(val files: List<FolderEntry>) : FolderScanResult

    /** Доступ отозван / папка удалена / uri не дерево. */
    data object AccessLost : FolderScanResult
}

/**
 * Сканер папки SAF (Storage Access Framework).
 *
 * Работает без androidx.documentfile: читаем дерево напрямую через
 * [DocumentsContract] + [ContentResolver.query]. Зависимостей не добавляем.
 *
 * Сканируется ТОЛЬКО первый уровень папки (подпапки игнорируются).
 * Файл считается аудио, если mime-тип провайдера начинается с `audio/`
 * ИЛИ расширение имени есть в [AUDIO_EXTENSIONS].
 *
 * Все методы не бросают исключений: любой сбой → [FolderScanResult.AccessLost].
 */
object FolderScanner {

    /** Расширения аудио, которые принимаем кроме mime-типа `audio/`. */
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "wav", "ogg", "oga", "m4a", "aac", "flac",
        "mid", "midi", "opus", "amr", "3gp", "wma",
    )

    /**
     * Сканирование папки (первый уровень). Никогда не бросает.
     *
     * @param context контекст (нужен contentResolver).
     * @param treeUri uri ДЕРЕВА папки (content://…/tree/…).
     * @return [FolderScanResult.Ok] со списком аудиофайлов (по имени, без учёта регистра)
     *         или [FolderScanResult.AccessLost], если доступ закрыт/папки нет.
     */
    fun scan(context: Context, treeUri: Uri): FolderScanResult {
        val result: FolderScanResult = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val entries = mutableListOf<FolderEntry>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                    // Подпапки не трогаем — сканируем только первый уровень.
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val documentId = if (idIdx >= 0) cursor.getString(idIdx) else null
                    if (documentId.isNullOrEmpty()) continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val displayName = name?.takeIf { it.isNotEmpty() } ?: documentId
                    if (!isAudio(displayName, mime)) continue
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    entries.add(FolderEntry(uri = fileUri.toString(), name = displayName))
                }
            }
            // Сортировка по имени (без учёта регистра) — режим «По порядку» стабилен.
            val sorted = entries.sortedWith(
                Comparator<FolderEntry> { a, b ->
                    val byName = a.name.compareTo(b.name, ignoreCase = true)
                    if (byName != 0) byName else a.uri.compareTo(b.uri)
                },
            )
            AppLog.i("FolderScanner.scan: папка $treeUri — аудиофайлов ${sorted.size}")
            FolderScanResult.Ok(sorted)
        }.getOrElse { e ->
            AppLog.w("FolderScanner.scan: нет доступа к папке $treeUri: $e")
            FolderScanResult.AccessLost
        }
        return result
    }

    /**
     * Отображаемое имя документа (DISPLAY_NAME) или null.
     *
     * Имя корня дерева лежит в документе дерева, поэтому пробуем сначала
     * документ дерева, потом сам tree-uri.
     *
     * Каждая попытка изолирована своим `runCatching`: провайдеры не умеют
     * отдавать DISPLAY_NAME по tree-uri (`UnsupportedOperationException`),
     * и без изоляции падение первой попытки отменяло бы вторую.
     */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        val cr = context.contentResolver
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        // 1) Документ дерева: buildDocumentUriUsingTree даёт uri, который провайдер понимает.
        val viaTreeDocument = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            queryDisplayNameInternal(cr, DocumentsContract.buildDocumentUriUsingTree(uri, docId), projection)
        }.getOrNull()
        if (!viaTreeDocument.isNullOrEmpty()) return viaTreeDocument
        // 2) Сам tree-uri — часть провайдеров отдаёт имя только так.
        return runCatching {
            queryDisplayNameInternal(cr, uri, projection)
        }.getOrElse { e ->
            AppLog.w("FolderScanner.queryDisplayName: не удалось получить имя $uri: $e")
            null
        }
    }

    /** Есть ли persistable-право чтения на uri. */
    fun hasPersistedAccess(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }.getOrElse { e ->
            AppLog.w("FolderScanner.hasPersistedAccess: не удалось проверить доступ $uri: $e")
            false
        }
    }

    // ------------------------------------------------------------------ internals

    /** Имя документа по колонке DISPLAY_NAME; null — нет строки или колонки. */
    private fun queryDisplayNameInternal(
        cr: ContentResolver,
        uri: Uri,
        projection: Array<String>,
    ): String? {
        cr.query(uri, projection, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                val value = c.getString(idx)
                if (!value.isNullOrEmpty()) return value
            }
        }
        return null
    }

    /** Аудио по mime-типу провайдера или по расширению имени. */
    private fun isAudio(name: String, mimeType: String?): Boolean {
        if (mimeType != null && mimeType.startsWith("audio/", ignoreCase = true)) return true
        return extensionOf(name) in AUDIO_EXTENSIONS
    }

    /** Расширение имени файла в нижнем регистре; "" — расширения нет. */
    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }
}
