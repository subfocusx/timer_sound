package com.timersound

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.timersound.ui.App
import com.timersound.ui.TimerSoundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Показывать таймер поверх локскрина и будить экран при звонке
        // (на этом Huawei телефон сам блокируется каждые ~минуту; без этих
        // флагов UI прячется под ключным экраном и «выглядит сброшенным»).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableEdgeToEdge()
        setContent {
            TimerSoundTheme {
                App()
            }
        }
    }
}