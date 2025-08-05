package com.morecup

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var initListener: ((Boolean) -> Unit)? = null
    private var isSpeaking = false
    private var completionCallback: (() -> Unit)? = null
    private val ttsQueue = LinkedBlockingQueue<String>()
    private var ttsThread: Thread? = null
    private var shouldStopTTS = false

    // 句子结束标点符号
    private val sentenceEndings = setOf('.', '。', '!', '！', '?', '？', ';', '；', ':', '：')

    // 句子最大长度
    private val maxSentenceLength = 1000

    // 句子超时时间（毫秒）
    private val sentenceTimeout = 1000L

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
                    // 获取系统中所有可用的语音
                    val availableVoices = textToSpeech?.voices
                    Log.d("TTSManager", "Available voices: $availableVoices")

                    // 查找中文语音，优先选择质量高的
                    val chineseVoice = availableVoices?.find {
                        (it.locale == Locale.CHINESE || it.locale == Locale.SIMPLIFIED_CHINESE) &&
                                it.quality == Voice.QUALITY_HIGH
                    } ?: availableVoices?.find {
                        it.locale == Locale.CHINESE || it.locale == Locale.SIMPLIFIED_CHINESE
                    }

                    if (chineseVoice != null) {
                        textToSpeech?.voice = chineseVoice
                        Log.d("TTSManager", "Using voice: ${chineseVoice.name}")
                    } else {
                        // 如果找不到合适的中文语音，使用默认设置
                        textToSpeech?.voice = Voice(
                            "zh-CN-language",
                            Locale.CHINESE,
                            Voice.QUALITY_HIGH,
                            Voice.LATENCY_NORMAL,
                            false,
                            null
                        )
                    }
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

    /**
     * 设置TTS完成所有内容播放后的回调
     */
    fun setCompletionCallback(callback: () -> Unit) {
        this.completionCallback = callback
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
        val currentText = sentenceBuffer.toString().trim()
        if (currentText.isEmpty()) return
        Log.d("TTSManager", "checkAndSpeakSentence:$currentText")
        // 判断是否应该朗读:
        // 1. 遇到句子结束符号
        // 2. 文本长度超过最大长度
        // 3. 超过超时时间且有文本
        
        // 查找句子结束符号的位置
        val lastEndIndex = currentText.indexOfLast { sentenceEndings.contains(it) }
        
        // 如果找到了句子结束符号
        if (lastEndIndex >= 0) {
            // 提取结束符号前的内容进行朗读
            val textToSpeak = currentText.substring(0, lastEndIndex + 1).trim()
            if (textToSpeak.isNotEmpty()) {
                ttsQueue.offer(textToSpeak)
                // 保留结束符号后的内容在缓冲区中
                val remainingText = currentText.substring(lastEndIndex + 1)
                sentenceBuffer.clear()
                sentenceBuffer.append(remainingText)
                startTTSIfNeeded()
            }
        } else {
            // 没有找到结束符号，检查其他条件
            val isTooLong = currentText.length >= maxSentenceLength
            val isTimeout = (System.currentTimeMillis() - lastTextReceivedTime) > sentenceTimeout
            
            if (isTooLong || isTimeout) {
                val textToSpeak = currentText.trim()
                if (textToSpeak.isNotEmpty()) {
                    ttsQueue.offer(textToSpeak)
                    sentenceBuffer.clear()
                    startTTSIfNeeded()
                }
            }
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
                val text = ttsQueue.poll()
                if (text != null && text.isNotEmpty()) {
                    speakInternal(text)
                    waitForSpeechCompletion()
                } else if (ttsQueue.isEmpty()) {
                    // 队列为空，退出循环
                    break
                }
            }
            
            // 所有内容播放完成，触发回调
            if (!shouldStopTTS) {
                onTTSCompleted()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            isSpeaking = false
        }
    }

    private fun speakInternal(text: String) {

        // 播放前停止任何正在播放的语音
        if (textToSpeech?.isSpeaking ?: false) {
            textToSpeech?.stop()
        }
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

    /**
     * 当TTS完成所有内容播放后调用
     */
    private fun onTTSCompleted() {
        completionCallback?.invoke()
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

    /**
     * 获取所有可用的语音
     */
    fun getAvailableVoices(): Set<Voice>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.voices
        } else {
            null
        }
    }

    /**
     * 设置特定语音
     */
    fun setVoice(voice: Voice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.voice = voice
        }
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