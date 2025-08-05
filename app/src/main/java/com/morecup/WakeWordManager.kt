package com.morecup

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

class WakeWordManager {
    private lateinit var porcupineManager: PorcupineManager

    private val ACCESS_KEY = "Vo9ii0CIafLGsSI3C7LEIbuLdKhxzr+IJrosP6lTNi4EDef4hX17/g=="
    private val defaultKeyword = Porcupine.BuiltInKeyword.COMPUTER

    fun init(applicationContext: Context,callBack: () -> Unit){
        porcupineManager = PorcupineManager.Builder()
            .setAccessKey(ACCESS_KEY)
            .setKeyword(defaultKeyword)
            .setSensitivity(0.7f)
            .build(applicationContext){ keywordIndex ->
                playBeepSound()
                Handler(Looper.getMainLooper()).postDelayed({
                    callBack()
                }, 200)
            }
    }

    private fun playBeepSound() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 150)
            Handler(Looper.getMainLooper()).postDelayed({ toneGenerator.release() }, 200)
        } catch (e: Exception) {
            Log.e("MainActivity", "播放提示音失败", e)
        }
    }

}