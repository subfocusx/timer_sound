package com.timersound.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Ключ документа для сравнения SAF-uri.
 *
 * Один и тот же файл приходит разными адресами: из папки — через дерево,
 * из пикера файлов — напрямую. Пересечение будильников должно находиться
 * по документу, а не по строке uri.
 */
class SoundUriTest {

    /** Uri документа внутри дерева — так отдаёт папка. */
    private val treeFileUri =
        "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAlarmSounds" +
            "/document/primary%3AMusic%2FAlarmSounds%2F01_tone_440.wav"

    /** Uri того же файла из пикера файлов. */
    private val pickedFileUri =
        "content://com.android.externalstorage.documents/document/primary%3AMusic%2FAlarmSounds%2F01_tone_440.wav"

    @Test
    fun sameFileFromTreeAndPickerHasSameKey() {
        assertEquals(
            SoundUri.documentKey(treeFileUri),
            SoundUri.documentKey(pickedFileUri),
        )
    }

    @Test
    fun differentFilesHaveDifferentKeys() {
        val other = pickedFileUri.replace("01_tone_440.wav", "02_tone_660.wav")
        assertNotEquals(
            SoundUri.documentKey(treeFileUri),
            SoundUri.documentKey(other),
        )
    }

    @Test
    fun differentProvidersWithSameDocIdAreDifferentDocuments() {
        val a = "content://provider.one/document/doc-1"
        val b = "content://provider.two/document/doc-1"
        assertNotEquals(SoundUri.documentKey(a), SoundUri.documentKey(b))
    }

    @Test
    fun nonDocumentUriKeepsItsOwnForm() {
        assertEquals("content://audio/1", SoundUri.documentKey("content://audio/1"))
    }

    @Test
    fun emptyUriStaysEmpty() {
        assertEquals("", SoundUri.documentKey(""))
    }

    @Test
    fun percentEncodedDocIdIsDecoded() {
        assertEquals(
            "com.android.externalstorage.documents/primary:Music/AlarmSounds/01_tone_440.wav",
            SoundUri.documentKey(treeFileUri),
        )
    }

    @Test
    fun invalidPercentSequenceKeepsAsIs() {
        // «%ZZ» — не escape: оставляем как есть, чтобы ключи не «сливались».
        assertEquals("a%ZZb", SoundUri.percentDecode("a%ZZb"))
    }

    @Test
    fun plusIsNotTreatedAsSpace() {
        // В пути URI «+» — обычный символ (не как в query-строке).
        assertEquals("a+b", SoundUri.percentDecode("a+b"))
    }
}
