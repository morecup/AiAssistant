package com.morecup

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var initListener: ((Boolean) -> Unit)? = null
    private var isSpeaking = false
    private val ttsQueue = LinkedBlockingQueue<String>()
    private var ttsThread: Thread? = null
    private var shouldStopTTS = false

    // 句子结束标点符号
    private val sentenceEndings = setOf('.', '。', '!', '！', '?', '？', ';', '；', ':', '：')
    
    // 句子最大长度
    private val maxSentenceLength = 40
    
    // 句子超时时间（毫秒）
    private val sentenceTimeout = 500L
    
    // 用于累积句子的缓冲区
    private val sentenceBuffer = StringBuilder()
    private var lastTextReceivedTime: Long = 0

    init {
        initializeTTS()
    }

    private fun initializeTTS() {
        textToSpeech = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.getDefault())
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTSManager", "Language not supported")
                isInitialized = false
            } else {
                // 设置默认参数
                textToSpeech?.setPitch(1.0f)
                textToSpeech?.setSpeechRate(1.1f)
                
                // 设置中文语音（如果可用）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    textToSpeech?.voice = Voice(
                        "zh-CN-language",
                        Locale.CHINESE,
                        Voice.QUALITY_HIGH,
                        Voice.LATENCY_NORMAL,
                        false,
                        null
                    )
                }
                
                isInitialized = true
            }
        } else {
            Log.e("TTSManager", "TTS initialization failed")
            isInitialized = false
        }
        
        initListener?.invoke(isInitialized)
        initListener = null
    }

    fun setOnInitListener(listener: (Boolean) -> Unit) {
        if (isInitialized) {
            listener(true)
        } else {
            initListener = listener
        }
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!isInitialized) {
            Log.e("TTSManager", "TTS not initialized")
            return
        }

        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            flush()
        }
        
        ttsQueue.offer(text)
        startTTSIfNeeded()
    }
    
    /**
     * 处理流式响应的文本片段
     * 累积文本直到形成完整句子再进行朗读
     */
    fun speakStreamText(textFragment: String) {
        if (!isInitialized) {
            Log.e("TTSManager", "TTS not initialized")
            return
        }
        
        synchronized(sentenceBuffer) {
            sentenceBuffer.append(textFragment)
            lastTextReceivedTime = System.currentTimeMillis()
            
            // 检查是否应该朗读句子
            checkAndSpeakSentence()
        }
    }
    
    /**
     * 检查并朗读完整句子
     */
    private fun checkAndSpeakSentence() {
        val currentText = sentenceBuffer.toString()
        if (currentText.isEmpty()) return
        
        // 判断是否应该朗读:
        // 1. 遇到句子结束符号
        // 2. 文本长度超过最大长度
        // 3. 超过超时时间且有文本
        val isCompleteSentence = currentText.any { sentenceEndings.contains(it) }
        val isTooLong = currentText.length >= maxSentenceLength
        val isTimeout = (System.currentTimeMillis() - lastTextReceivedTime) > sentenceTimeout
        
        if (isCompleteSentence || isTooLong || isTimeout) {
            ttsQueue.offer(currentText.trim())
            sentenceBuffer.clear()
            startTTSIfNeeded()
        }
    }
    
    /**
     * 强制朗读当前缓冲区中的所有文本（例如在流结束时）
     */
    fun flushBuffer() {
        synchronized(sentenceBuffer) {
            if (sentenceBuffer.isNotEmpty()) {
                ttsQueue.offer(sentenceBuffer.toString().trim())
                sentenceBuffer.clear()
                startTTSIfNeeded()
            }
        }
    }
    
    private fun startTTSIfNeeded() {
        if (ttsThread?.isAlive != true) {
            shouldStopTTS = false
            ttsThread = thread(isDaemon = true) {
                processTTSQueue()
            }
        }
    }
    
    private fun processTTSQueue() {
        isSpeaking = true
        try {
            while (!shouldStopTTS) {
                val text = ttsQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (text != null && text.isNotEmpty()) {
                    speakInternal(text)
                    waitForSpeechCompletion()
                } else if (ttsQueue.isEmpty()) {
                    // 队列为空，退出循环
                    break
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            isSpeaking = false
        }
    }
    
    private fun speakInternal(text: String) {
        textToSpeech?.let { tts ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
            } else {
                @Suppress("DEPRECATION")
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }
    
    private fun waitForSpeechCompletion() {
        while (textToSpeech?.isSpeaking == true && !shouldStopTTS) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    fun stop() {
        synchronized(sentenceBuffer) {
            sentenceBuffer.clear()
        }
        ttsQueue.clear()
        textToSpeech?.stop()
        shouldStopTTS = true
    }
    
    private fun flush() {
        synchronized(sentenceBuffer) {
            sentenceBuffer.clear()
        }
        ttsQueue.clear()
        textToSpeech?.stop()
    }

    fun isSpeaking(): Boolean {
        return isSpeaking || (textToSpeech?.isSpeaking ?: false)
    }
    
    fun queueSize(): Int {
        return ttsQueue.size
    }

    fun setPitch(pitch: Float) {
        textToSpeech?.setPitch(pitch)
    }

    fun setSpeechRate(rate: Float) {
        textToSpeech?.setSpeechRate(rate)
    }

    fun shutdown() {
        stop()
        textToSpeech?.apply {
            shutdown()
        }
        isInitialized = false
    }

    companion object {
        const val QUEUE_ADD = TextToSpeech.QUEUE_ADD
        const val QUEUE_FLUSH = TextToSpeech.QUEUE_FLUSH
    }
}