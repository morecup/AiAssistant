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
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class AppState {
    STOPPED,           // 应用停止状态
    WAKEWORD,          // 唤醒词检测状态
    LISTENING,         // 语音识别监听状态
    PROCESSING,        // 语音处理中状态
    AI_PROCESSING,     // AI请求处理中状态
    AI_RESPONDING,     // AI响应播放状态
    TTS_SPEAKING       // TTS朗读状态
}

class MainActivity : AppCompatActivity() {
    private val ACCESS_KEY = "Vo9ii0CIafLGsSI3C7LEIbuLdKhxzr+IJrosP6lTNi4EDef4hX17/g=="
    private val defaultKeyword = Porcupine.BuiltInKeyword.COMPUTER

    private lateinit var intentTextView: TextView
    private lateinit var recordButton: ToggleButton

    private var currentState: AppState = AppState.STOPPED

    // Managers
    private lateinit var wakeWordManager: WakeWordManager
    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    private lateinit var aiAnalysisManager: AiAnalysisManager
    private lateinit var ttsManager: TTSManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun displayError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        intentTextView = findViewById(R.id.intentView)
        recordButton = findViewById(R.id.record_button)

        // Initialize managers
        initializeManagers()
    }

    private fun initializeManagers() {
        // Initialize wake word manager
        wakeWordManager = WakeWordManager()

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
    }

    private fun processSpeechResults(results: String) {
        runOnUiThread {
            intentTextView.setTextColor(Color.WHITE)
            intentTextView.text = "用户: $results"
        }
        queryAI(results)
    }

    private fun handleSpeechError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> displayError("Error recording audio.")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> displayError("Insufficient permissions.")
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> displayError("Network Error.")
            SpeechRecognizer.ERROR_NO_MATCH -> {
                if (recordButton.isChecked) {
                    displayError("No recognition result matched.")
                    playback(1000)
                }
                return
            }
            SpeechRecognizer.ERROR_CLIENT -> return
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> displayError("Recognition service is busy.")
            SpeechRecognizer.ERROR_SERVER -> displayError("Server Error.")
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> displayError("No speech input.")
            else -> displayError("Something wrong occurred.")
        }
        stopService()
        recordButton.toggle()
    }

    private fun startWakeWordDetection() {
        try {
            wakeWordManager.init(applicationContext) {
                runOnUiThread {
                    intentTextView.text = ""
                }
                
                // 延迟启动语音识别
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        speechRecognizerManager.startListening()
                        updateState(AppState.LISTENING)
                    } catch (e: Exception) {
                        displayError("启动语音识别失败: ${e.message}")
                        playback(1000)
                    }
                }, 200)
            }
        } catch (e: Exception) {
            displayError("Failed to initialize wake word detection: ${e.message}")
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

    private fun stopService() {
        ttsManager.stop()
        speechRecognizerManager.stopListening()
        speechRecognizerManager.destroy()

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
        super.onDestroy()
    }

    private fun queryAI(query: String) {
        updateState(AppState.AI_PROCESSING)
        
        aiAnalysisManager.analyzeText(query, object : AiAnalysisManager.AiAnalysisCallback {
            override fun onStreamText(text: String) {
                // 更新UI显示AI响应
                runOnUiThread {
                    intentTextView.setTextColor(Color.WHITE)
                    intentTextView.text = "AI: $text"
                }
                
                // 将文本片段传递给TTS进行流式朗读
                ttsManager.speakStreamText(text)
                updateState(AppState.AI_RESPONDING)
            }

            override fun onSuccess(response: String) {
                // 整个响应完成，刷新TTS缓冲区
                ttsManager.flushBuffer()
            }

            override fun onStreamComplete() {
                // 流式响应完成，恢复唤醒词检测
                updateState(AppState.TTS_SPEAKING)
                mainHandler.postDelayed({
                    playback(3000)
                }, 3000)
            }

            override fun onError(error: String) {
                runOnUiThread {
                    displayError("AI 请求异常: $error")
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
            }
        }
    }
}