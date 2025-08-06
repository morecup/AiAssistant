package com.morecup

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

class WakeWordManager :IWakeWordManager{
    private lateinit var porcupineManager: PorcupineManager
    private var wakeWordCallback: (() -> Unit)? = null

    private val ACCESS_KEY = "dauNLkyx6pZwC222/6zy8WlPPePJ9SufDpmmpigAjSUGmMNDTJTEqw=="
    private val defaultKeyword = Porcupine.BuiltInKeyword.COMPUTER

    override fun init(applicationContext: Context, callBack: () -> Unit){
        wakeWordCallback = callBack
        
        porcupineManager = PorcupineManager.Builder()
            .setAccessKey(ACCESS_KEY)
            .setKeyword(defaultKeyword)
            .setSensitivity(0.7f)
            .build(applicationContext, porcupineManagerCallback)
    }
    
    private val porcupineManagerCallback = object : PorcupineManagerCallback {
        override fun invoke(keywordIndex: Int) {
            playBeepSound()
            wakeWordCallback?.invoke()
        }
    }

    private fun playBeepSound() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 150)
            Handler(Looper.getMainLooper()).postDelayed({ toneGenerator.release() }, 200)
        } catch (e: Exception) {
            Log.e("WakeWordManager", "播放提示音失败", e)
        }
    }

    override fun start() {
        try {
            porcupineManager.start()
        } catch (e: Exception) {
            Log.e("WakeWordManager", "启动唤醒词检测失败", e)
        }
    }

    override fun stop() {
        try {
            porcupineManager.stop()
        } catch (e: Exception) {
            Log.e("WakeWordManager", "停止唤醒词检测失败", e)
        }
    }

    override fun destroy() {
        try {
            porcupineManager.delete()
        } catch (e: Exception) {
            Log.e("WakeWordManager", "销毁唤醒词检测失败", e)
        }
    }
}