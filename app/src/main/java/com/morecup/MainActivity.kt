package com.morecup

import ai.picovoice.porcupine.Porcupine
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.morecup.sherpa.KeywordSpotterManager

enum class AppState {
    STOPPED,           // 应用停止状态
    WAKEWORD,          // 唤醒词检测状态
    LISTENING,         // 语音识别监听状态
    PROCESSING,        // 语音处理中状态
    AI_PROCESSING,     // AI请求处理中状态
    AI_RESPONDING,     // AI响应播放状态
    TTS_SPEAKING,      // TTS朗读状态
    CONTINUOUS_DIALOG  // 连续对话状态
}

class MainActivity : AppCompatActivity() {
    private val ACCESS_KEY = "Vo9ii0CIafLGsSI3C7LEIbuLdKhxzr+IJrosP6lTNi4EDef4hX17/g=="
    private val defaultKeyword = Porcupine.BuiltInKeyword.COMPUTER

    private lateinit var intentTextView: TextView
    private lateinit var intentScrollView: ScrollView
    private lateinit var recordButton: ToggleButton

    private var currentState: AppState = AppState.STOPPED

    // Managers
    private lateinit var wakeWordManager: IWakeWordManager
    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    private lateinit var aiAnalysisManager: AiAnalysisManager
    private lateinit var ttsManager: TTSManager

    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 连续对话相关
    private var continuousDialogEnabled = true // 是否启用连续对话
    private var isContinuousDialogMode = false // 是否处于连续对话模式

    private fun displayError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        intentTextView = findViewById(R.id.intentView)
        intentScrollView = findViewById(R.id.intentScrollView)
        recordButton = findViewById(R.id.record_button)

        // Initialize managers
        initializeManagers()
    }

    private fun initializeManagers() {

        // Initialize speech recognizer manager
        speechRecognizerManager = SpeechRecognizerManager(this)
        speechRecognizerManager.setSpeechCallback(object : SpeechRecognizerManager.SpeechCallback {
            override fun onResults(results: String) {
                processSpeechResults(results)
            }

            override fun onPartialResults(partialResults: String) {
                runOnUiThread {
                    intentTextView.setTextColor(Color.DKGRAY)
                    intentTextView.text = partialResults
                }
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
                displayError("TTS initialization failed")
            }
        }
        ttsManager.setCompletionCallback {
            // TTS播放完成后，切换到下一阶段
            runOnUiThread {
                playback(300) // 短暂延迟后进入下一阶段
            }
        }

        // Initialize wake word manager
//        wakeWordManager = WakeWordManager()
        wakeWordManager = KeywordSpotterManager(this)
        wakeWordManager.init(applicationContext) {
//            wakeWordManager.start()
            onWakeWordDetected()
        }
    }

    private fun processSpeechResults(results: String) {
        mainHandler.postDelayed({
            intentTextView.setTextColor(Color.WHITE)
            intentTextView.text = "用户: $results\n"
        }, 0)
//        runOnUiThread {
//            intentTextView.setTextColor(Color.WHITE)
//            intentTextView.text = "用户: $results"
//        }
        queryAI(results)
    }

    private fun handleSpeechError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> displayError("Error recording audio.")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> displayError("Insufficient permissions.")
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NO_MATCH -> {
                if (recordButton.isChecked) {
                    displayError("No recognition result matched.")
                    if (isContinuousDialogMode) {
                        // 连续对话模式下，出错后继续等待语音输入
                        startContinuousListening()
                    } else {
                        playback(0)
                    }
                }
                return
            }
            SpeechRecognizer.ERROR_CLIENT -> return
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> displayError("Recognition service is busy.")
            SpeechRecognizer.ERROR_SERVER -> displayError("Server Error.")
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                displayError("No speech input.")
                if (isContinuousDialogMode) {
                    // 连续对话模式下，超时后继续等待语音输入
                    startContinuousListening()
                }
                return
            }
            else -> displayError("Something wrong occurred.")
        }
        stopService()
        recordButton.toggle()
    }

    private fun startWakeWordDetection() {
        try {
            wakeWordManager.start()
            
//            // 延迟启动语音识别
//            Handler(Looper.getMainLooper()).postDelayed({
//                try {
//                    speechRecognizerManager.startListening()
//                    updateState(AppState.LISTENING)
//                } catch (e: Exception) {
//                    displayError("启动语音识别失败: ${e.message}")
//                    playback(1000)
//                }
//            }, 200)
        } catch (e: Exception) {
            displayError("Failed to start wake word detection: ${e.message}")
        }
    }
    
    private fun startContinuousListening() {
        // 在连续对话模式下直接启动语音识别
        if (continuousDialogEnabled && isContinuousDialogMode) {
            try {
                speechRecognizerManager.startListening()
                updateState(AppState.LISTENING)
            } catch (e: Exception) {
                displayError("启动连续语音识别失败: ${e.message}")
                // 出错时回退到正常模式
                isContinuousDialogMode = false
                playback(1000)
            }
        }
    }

    override fun onStop() {
        if (recordButton.isChecked) {
            stopService()
            recordButton.toggle()
        }
        super.onStop()
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
    }

    private fun playback(milliSeconds: Int) {
        speechRecognizerManager.stopListening()
        
        // 如果启用了连续对话，则进入连续对话模式
        if (continuousDialogEnabled && currentState != AppState.STOPPED) {
            isContinuousDialogMode = true
            updateState(AppState.CONTINUOUS_DIALOG)
            
            mainHandler.postDelayed({
                if (currentState == AppState.CONTINUOUS_DIALOG) {
                    startContinuousListening()
                    intentTextView.setTextColor(Color.WHITE)
                    intentTextView.text = "请说话...（连续对话模式）"
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
                        displayError("Failed to start wake word detection.")
                    }
                    intentTextView.setTextColor(Color.WHITE)
                    intentTextView.text = "Listening for $defaultKeyword ..."
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

        intentTextView.text = ""
        updateState(AppState.STOPPED)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] == PackageManager.PERMISSION_DENIED) {
            onPorcupineInitError("Microphone permission is required for this demo")
        } else {
            playback(0)
        }
    }

    fun process(view: View) {
        if (recordButton.isChecked) {
            if (hasRecordPermission()) {
                playback(0)
            } else {
                requestRecordPermission()
            }
        } else {
            stopService()
        }
    }

    override fun onDestroy() {
        ttsManager.shutdown()
        speechRecognizerManager.destroy()
        wakeWordManager.destroy()
        super.onDestroy()
    }

    private fun queryAI(query: String) {
        updateState(AppState.AI_PROCESSING)
        intentTextView.append("\n")
        intentTextView.append("AI: ")
        
        aiAnalysisManager.analyzeText(query, object : AiAnalysisManager.AiAnalysisCallback {
            override fun onStreamText(text: String) {
                mainHandler.postDelayed({
                    intentTextView.setTextColor(Color.WHITE)
                    intentTextView.append("AI:")
                    intentTextView.append(text)
                    intentTextView.append("\n")
                    scrollToBottom() // 自动滚动到底部
                }, 0)
                
                // 将文本片段传递给TTS进行流式朗读
                ttsManager.speakStreamText(text)
                updateState(AppState.AI_RESPONDING)
            }

            override fun onSuccess(response: String) {
                // 整个响应完成，刷新TTS缓冲区
//                ttsManager.flushBuffer()
            }

            override fun onStreamComplete() {
                // 流式响应完成，根据模式决定下一步操作
                updateState(AppState.TTS_SPEAKING)

//                // 在TTS播放完成后，根据是否启用连续对话来决定下一步
//                mainHandler.postDelayed({
//                    playback(1000) // 短暂延迟后进入下一阶段
//                }, 1000)
            }

            override fun onError(error: String) {
                runOnUiThread {
                    displayError("AI 请求异常: $error")
                    // 出错时重置连续对话模式
                    isContinuousDialogMode = false
                    playback(1000)
                }
            }
        })
    }

    private fun onPorcupineInitError(errorMessage: String) {
        runOnUiThread {
            val errorText = findViewById<TextView>(R.id.errorMessage)
            errorText.text = errorMessage
            errorText.visibility = View.VISIBLE

            recordButton.background = ContextCompat.getDrawable(
                applicationContext,
                R.drawable.disabled_button_background
            )
            recordButton.isChecked = false
            recordButton.isEnabled = false
        }
    }
    
    private fun updateState(newState: AppState) {
        Log.d("MainActivity", "State changed from $currentState to $newState")
        currentState = newState
        
        // 可以在这里添加UI更新逻辑，例如显示当前状态
        runOnUiThread {
            // 根据状态更新UI元素
            when (newState) {
                AppState.STOPPED -> {
                    // 停止状态UI更新
                }
                AppState.WAKEWORD -> {
                    // 唤醒词检测状态UI更新
                }
                AppState.LISTENING -> {
                    // 语音识别监听状态UI更新
                }
                AppState.PROCESSING -> {
                    // 语音处理中状态UI更新
                }
                AppState.AI_PROCESSING -> {
                    // AI请求处理中状态UI更新
                }
                AppState.AI_RESPONDING -> {
                    // AI响应播放状态UI更新
                }
                AppState.TTS_SPEAKING -> {
                    // TTS朗读状态UI更新
                }
                AppState.CONTINUOUS_DIALOG -> {
                    // 连续对话状态UI更新
                    intentTextView.text = "连续对话模式..."
                }
            }
        }
    }
    
    // 公共方法，允许外部控制是否启用连续对话
    fun setContinuousDialogEnabled(enabled: Boolean) {
        continuousDialogEnabled = enabled
    }
    
    // 公共方法，检查是否处于连续对话模式
    fun isContinuousDialogMode(): Boolean {
        return isContinuousDialogMode
    }
    
    private fun scrollToBottom() {
        intentScrollView.post {
            intentScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    // 处理唤醒词检测事件
    private fun onWakeWordDetected() {
        runOnUiThread {
            //也许还需要处理这些状态
//            LISTENING,         // 语音识别监听状态
//            PROCESSING,        // 语音处理中状态
            // 如果正在朗读TTS，则打断
            if (currentState == AppState.TTS_SPEAKING || currentState == AppState.AI_RESPONDING || currentState == AppState.AI_RESPONDING) {
                aiAnalysisManager.stop()
                ttsManager.stop()
                intentTextView.text = "已打断当前朗读"
            }
            
            // 清空当前界面文本
            intentTextView.text = ""
            
            // 重置状态并进入下一轮对话
            isContinuousDialogMode = false
            updateState(AppState.WAKEWORD)
            
            // 立即开始下一轮对话
            try {
                speechRecognizerManager.startListening()
                updateState(AppState.LISTENING)
            } catch (e: Exception) {
                displayError("启动语音识别失败: ${e.message}")
            }
        }
    }
}