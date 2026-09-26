package com.timersound

/**
 * Б5: единый логгер модуля. Пишет в android.util.Log, если доступен рантайм;
 * в чистых JVM-тестах (android.jar-заглушка) падает назад на System.out,
 * чтобы юнит-тесты машины состояний не требовали Robolectric ради логов.
 */
object AppLog {
    const val TAG = "TimerSound"

    private val androidAvailable: Boolean by lazy {
        runCatching {
            Class.forName("android.util.Log")
            Class.forName("android.os.SystemClock") // триггер реального рантайма
            true
        }.getOrDefault(false) && !isUnitTestStub()
    }

    private fun isUnitTestStub(): Boolean = runCatching {
        android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)
        false
    }.getOrDefault(true)

    fun i(message: String) {
        if (androidAvailable) android.util.Log.i(TAG, message)
        else println("I/$TAG: $message")
    }

    fun w(message: String) {
        if (androidAvailable) android.util.Log.w(TAG, message)
        else println("W/$TAG: $message")
    }

    fun e(message: String) {
        if (androidAvailable) android.util.Log.e(TAG, message)
        else println("E/$TAG: $message")
    }
}
