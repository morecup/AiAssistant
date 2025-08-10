package com.morecup

import ai.picovoice.porcupine.Porcupine
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
import android.media.ToneGenerator.TONE_CDMA_CALLDROP_LITE
import android.media.ToneGenerator.TONE_CDMA_HIGH_PBX_SLS
import android.media.ToneGenerator.TONE_CDMA_LOW_PBX_SSL
import android.os.*
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ToggleButton
import androidx.core.app.NotificationCompat
import com.morecup.sherpa.KeywordSpotterManager

class BackgroundAssistantService : Service() {
    private val defaultKeyword = Porcupine.BuiltInKeyword.COMPUTER
    private var currentState: AppState = AppState.STOPPED
    private var continuousDialogEnabled = true
    private var isContinuousDialogMode = false

    // Managers
    private lateinit var wakeWordManager: IWakeWordManager
    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    private lateinit var aiAnalysisManager: AiAnalysisManager
    private lateinit var ttsManager: TTSManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "BackgroundAssistantService"
        private const val CHANNEL_ID = "AssistantServiceChannel"
        private const val NOTIFICATION_ID = 345262
    }

    // Binder for communication with activity
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BackgroundAssistantService = this@BackgroundAssistantService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BackgroundAssistantService created")
        
        // 获取唤醒锁以保持CPU运行
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AssistantService::WakeLock")
        wakeLock?.setReferenceCounted(false)
        
        createNotificationChannel()
        initializeManagers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BackgroundAssistantService started")
        
        // 获取唤醒锁
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
            Log.d(TAG, "WakeLock acquired")
        }
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 开始唤醒词检测
        startWakeWordDetection()
        
        return START_STICKY // 服务被杀死后会重启
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音助手服务",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "保持语音助手在后台运行"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // 根据当前状态设置不同的通知文本
        val contentText = when (currentState) {
            AppState.STOPPED -> "服务已停止"
            AppState.WAKEWORD -> "正在等待唤醒词 \"$defaultKeyword\""
            AppState.LISTENING -> "正在聆听..."
            AppState.PROCESSING, AppState.AI_PROCESSING -> "正在处理请求..."
            AppState.AI_RESPONDING, AppState.TTS_SPEAKING -> "正在回答..."
            AppState.CONTINUOUS_DIALOG -> "连续对话模式"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音助手")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun initializeManagers() {
        // Initialize speech recognizer manager
        speechRecognizerManager = SpeechRecognizerManager(this)
        speechRecognizerManager.setSpeechCallback(object : SpeechRecognizerManager.SpeechCallback {
            override fun onResults(results: String) {
                processSpeechResults(results)
            }

            override fun onPartialResults(partialResults: String) {
                // 处理部分识别结果
            }

            override fun onError(error: Int) {
                handleSpeechError(error)
            }
        })

        // Initialize AI analysis manager
        aiAnalysisManager = AiAnalysisManager(this)

        // Initialize TTS manager
        ttsManager = TTSManager(this)
        ttsManager.setOnInitListener { success ->
            if (!success) {
                Log.e(TAG, "TTS initialization failed")
            }
        }
        ttsManager.setCompletionCallback {
            // TTS播放完成后，切换到下一阶段
            mainHandler.postDelayed({
                playBeepSound(TONE_CDMA_CALLDROP_LITE)
                playback(300) // 短暂延迟后进入下一阶段
            }, 0)
        }

        // Initialize wake word manager
        wakeWordManager = KeywordSpotterManager(applicationContext)
//        wakeWordManager = WakeWordManager()
        wakeWordManager.init(applicationContext) {
            onWakeWordDetected()
        }
    }

    private fun processSpeechResults(results: String) {
        if (results.contains("退出") && results.length < 5) {
            isContinuousDialogMode = false
            continuousDialogEnabled = false
            aiAnalysisManager.stop()
            ttsManager.stop()
            playBeepSound(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE)
            ttsManager.speak("已退出")
            playback(1000)
            return
        }

        playBeepSound(TONE_CDMA_ALERT_CALL_GUARD)
        
        queryAI(results)
    }

    private fun handleSpeechError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> Log.e(TAG, "Error recording audio.")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Log.e(TAG, "Insufficient permissions.")
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NO_MATCH -> {
                if (isContinuousDialogMode) {
                    // 连续对话模式下，出错后继续等待语音输入
                    startContinuousListening()
                } else {
                    playback(0)
                }
                return
            }
            SpeechRecognizer.ERROR_CLIENT -> return
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                Log.e(TAG, "Recognition service is busy.")
                return
            }
            SpeechRecognizer.ERROR_SERVER -> Log.e(TAG, "Server Error.")
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (isContinuousDialogMode) {
                    // 连续对话模式下，超时后继续等待语音输入
                    startContinuousListening()
                }
                return
            }
            else -> Log.e(TAG, "Something wrong occurred.")
        }
        stopService()
    }

    private fun startWakeWordDetection() {
        try {
            wakeWordManager.start()
            updateState(AppState.WAKEWORD)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word detection", e)
        }
    }
    
    private fun startContinuousListening() {
        // 在连续对话模式下直接启动语音识别
        if (continuousDialogEnabled && isContinuousDialogMode) {
            try {
                playBeepSound(TONE_CDMA_LOW_PBX_SSL)
                ttsManager.speak("您请说")
                Thread.sleep(150)
                speechRecognizerManager.startListening()
                updateState(AppState.LISTENING)
            } catch (e: Exception) {
                Log.e(TAG, "启动连续语音识别失败", e)
                // 出错时回退到正常模式
                isContinuousDialogMode = false
                playback(1000)
            }
        }
    }

    private fun playback(milliSeconds: Int) {
        mainHandler.postDelayed({
            speechRecognizerManager.stopListening()
        }, 0)

        // 如果启用了连续对话，则进入连续对话模式
        if (continuousDialogEnabled && currentState != AppState.STOPPED) {
            isContinuousDialogMode = true
            updateState(AppState.CONTINUOUS_DIALOG)
            
            mainHandler.postDelayed({
                if (currentState == AppState.CONTINUOUS_DIALOG) {
                    startContinuousListening()
                }
            }, milliSeconds.toLong())
        } else {
            // 否则回到唤醒词检测模式
            isContinuousDialogMode = false
            updateState(AppState.WAKEWORD)
            
            mainHandler.postDelayed({
                if (currentState == AppState.WAKEWORD) {
                    try {
                        startWakeWordDetection()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start wake word detection", e)
                    }
                }
            }, milliSeconds.toLong())
        }
    }

    private fun stopService() {
        ttsManager.stop()
        speechRecognizerManager.stopListening()
        wakeWordManager.stop()
        aiAnalysisManager.stop()
        
        // 重置连续对话模式
        isContinuousDialogMode = false
        updateState(AppState.STOPPED)
    }

    private fun queryAI(query: String) {
        updateState(AppState.AI_PROCESSING)
        
        aiAnalysisManager.analyzeText(query, object : AiAnalysisManager.AiAnalysisCallback {
            override fun onStreamText(text: String) {
                // 将文本片段传递给TTS进行流式朗读
                ttsManager.speakStreamText(text)
                updateState(AppState.AI_RESPONDING)
            }

            override fun onSuccess(response: String) {
                // 整个响应完成
            }

            override fun onStreamComplete() {
                // 流式响应完成，根据模式决定下一步操作
                updateState(AppState.TTS_SPEAKING)
            }

            override fun onError(error: String) {
                Log.e(TAG, "AI 请求异常: $error")
                // 出错时重置连续对话模式
                isContinuousDialogMode = false
                playback(1000)
            }
        })
    }

    private fun playBeepSound( toneType: Int = ToneGenerator.TONE_CDMA_ABBR_ALERT) {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGenerator.startTone(toneType, 150)
            Handler(Looper.getMainLooper()).postDelayed({ toneGenerator.release() }, 200)
        } catch (e: Exception) {
            Log.e("WakeWordManager", "播放提示音失败", e)
        }
    }
    
    private fun updateState(newState: AppState) {
        Log.d("BackgroundAssistantService", "State changed from $currentState to $newState")
        currentState = newState
        
        // 更新通知以反映新的状态
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
    
    // 处理唤醒词检测事件
    private fun onWakeWordDetected() {
        ttsManager.stop()
        speechRecognizerManager.stopListening()
        aiAnalysisManager.stop()
        
        // 重置状态并进入下一轮对话
        isContinuousDialogMode = false
        updateState(AppState.WAKEWORD)
        
        // 立即开始下一轮对话
        try {
            playBeepSound(TONE_CDMA_LOW_PBX_SSL)
            ttsManager.speak("您请说")
            Thread.sleep(150)
            speechRecognizerManager.startListening()
            updateState(AppState.LISTENING)
        } catch (e: Exception) {
            Log.e(TAG, "启动语音识别失败", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "BackgroundAssistantService destroyed")
        
        // 释放唤醒锁
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }

        ttsManager.shutdown()
        speechRecognizerManager.destroy()
        wakeWordManager.destroy()
        
        super.onDestroy()
    }
}
