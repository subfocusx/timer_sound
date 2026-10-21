package com.timersound.model

/**
 * Нормализация SAF-uri для сравнения документов.
 *
 * Один и тот же файл можно получить разными адресами:
 *  - из папки: `content://auth/tree/<treeId>/document/<docId>`;
 *  - пикером файлов: `content://auth/document/<docId>`.
 *
 * Строки разные, а документ один. Чтобы пересечение будильников находилось
 * по ФАКТИЧЕСКИМ файлам, сравниваем не uri, а ключ документа:
 * `authority + "/" + documentId`.
 *
 * Функция чистая (без Android SDK) — юнит-тесты идут на JVM.
 */
object SoundUri {

    /** Сегмент пути SAF, после которого лежит идентификатор документа. */
    private const val DOCUMENT_SEGMENT = "/document/"

    /**
     * Ключ документа для сравнения.
     *
     * Для SAF-uri с документом — `authority/docId` (docId в декодированном виде).
     * Для прочих uri (не SAF-документ) — сам uri без изменений.
     */
    fun documentKey(uri: String): String {
        if (uri.isEmpty()) return ""
        val marker = uri.lastIndexOf(DOCUMENT_SEGMENT)
        if (marker < 0) return uri
        val docId = percentDecode(uri.substring(marker + DOCUMENT_SEGMENT.length))
        val authority = uri.substringAfter("//", "").substringBefore('/')
        return "$authority/$docId"
    }

    /**
     * Раскодирует `%XX`-последовательности.
     *
     * Свой декодер вместо `URLDecoder`: тот трактует `+` как пробел, а в пути URI
     * `+` — обычный символ. Некорректные последовательности остаются как есть.
     */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
