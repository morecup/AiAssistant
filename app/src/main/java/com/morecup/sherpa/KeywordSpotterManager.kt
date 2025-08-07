// Copyright (c)  2024  Xiaomi Corporation
package com.morecup.sherpa

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import com.morecup.IWakeWordManager
import kotlin.concurrent.thread

class KeywordSpotterManager(private val context: Activity): IWakeWordManager {
    private lateinit var kws: KeywordSpotter
    private lateinit var stream: OnlineStream
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    private var isRecording: Boolean = false
    private var keywordListener: ((String) -> Unit)? = null

    private val permissions = arrayOf(android.Manifest.permission.RECORD_AUDIO)

    private lateinit var wakeWordCallback: (() -> Unit)

    private var keywords: List<String> = arrayListOf("h uān h uān @欢欢")

    companion object {
        private const val TAG = "KeywordSpotterManager"
    }

    /**
     * 初始化KeywordSpotter
     * @param modelType 模型类型 0 - 中文模型, 1 - 英文模型
     */
    override fun init(applicationContext: Context, callBack: () -> Unit) {
        try {
            val modelType: Int = 0
            val config = KeywordSpotterConfig(
                featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
                modelConfig = getKwsModelConfig(type = modelType)!!,
                keywordsFile = getKeywordsFile(type = modelType),
            )

            kws = KeywordSpotter(
                assetManager = context.assets,
                config = config,
            )
            stream = kws.createStream()

            wakeWordCallback = callBack
        } catch (e: Exception) {
            Log.e(TAG, "初始化模型失败", e)
            throw e
        }
    }

    /**
     * 开始关键词检测
     * @param keywords 要检测的关键词，多个关键词用"/"分隔
     */
    override fun start() {
        if (isRecording) return

        try {
            // 更新关键词
            val processedKeywords = keywords.joinToString("/").trim()
            stream.release()
            stream = kws.createStream(processedKeywords)

            if (stream.ptr == 0L) {
                Log.e(TAG, "创建关键词流失败: $processedKeywords")
                return
            }

            // 初始化麦克风
            if (!initMicrophone()) {
                Log.e(TAG, "初始化麦克风失败")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            // 启动录音线程
            recordingThread = thread(true) {
                processSamples()
            }

            Log.i(TAG, "开始关键词检测")
        } catch (e: Exception) {
            Log.e(TAG, "启动关键词检测失败", e)
        }
    }

    /**
     * 停止关键词检测
     */
    override fun stop() {
        if (!isRecording) return

        try {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.i(TAG, "停止关键词检测")
        } catch (e: Exception) {
            Log.e(TAG, "停止关键词检测失败", e)
        }
    }

    /**
     * 释放资源
     */
    override fun destroy() {
        stop()
        try {
            stream.release()
            kws.release()
            Log.i(TAG, "释放资源完成")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        }
    }

    private fun initMicrophone(): Boolean {
        try {
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(context, permissions, 200)
                return false
            }

            val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
            Log.i(TAG, "缓冲区大小: $numBytes 字节")

            audioRecord = AudioRecord(
                audioSource,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                numBytes * 2
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "初始化麦克风失败", e)
            return false
        }
    }

    private fun processSamples() {
        Log.i(TAG, "开始处理音频数据")

        val interval = 0.1 // 100 ms
        val bufferSize = (interval * sampleRateInHz).toInt()
        val buffer = ShortArray(bufferSize)

        while (isRecording) {
            try {
                val ret = audioRecord?.read(buffer, 0, buffer.size)
                if (ret != null && ret > 0) {
                    val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, sampleRate = sampleRateInHz)

                    while (kws.isReady(stream)) {
                        kws.decode(stream)
                        val result = kws.getResult(stream)
                        val keyword = result.keyword

                        if (keyword.isNotBlank()) {
                            // 检测到关键词后重置流
                            kws.reset(stream)

                            playBeepSound()

                            // 在主线程回调
                            Handler(Looper.getMainLooper()).post {
                                wakeWordCallback.invoke()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理音频数据出错", e)
            }
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
}
