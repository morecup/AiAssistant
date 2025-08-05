package com.morecup

import ai.picovoice.porcupine.*
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class AppState {
    STOPPED,
    WAKEWORD,
    STT
}

class MainActivity : AppCompatActivity() {
    private var porcupineManager: PorcupineManager? = null

    private val ACCESS_KEY = "Vo9ii0CIafLGsSI3C7LEIbuLdKhxzr+IJrosP6lTNi4EDef4hX17/g=="
    private val defaultKeyword = Porcupine.BuiltInKeyword.COMPUTER

    private lateinit var intentTextView: TextView
    private lateinit var recordButton: ToggleButton

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechRecognizerIntent: Intent

    private var currentState: AppState = AppState.STOPPED

    private var textToSpeech: TextToSpeech? = null // TTS引擎

    private var networkExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()
    )
    private var ttsExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()
    )
    private val aiResponseBuilder = StringBuilder()
    @Volatile private var isProcessingAIResponse = false
    @Volatile private var isTTSPlaying = false
    private val ttsQueue = LinkedBlockingQueue<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sentenceBuffer = StringBuilder() // 用于累积句子
    private var lastTextReceivedTime: Long = 0 // 记录上次收到文本的时间
    private val SENTENCE_TIMEOUT = 500 // 句子合并超时时间(ms)
    private val MAX_SENTENCE_LENGTH = 40 // 最大句子长度(字符)

    private fun displayError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private val porcupineManagerCallback = object : PorcupineManagerCallback {
        override fun invoke(keywordIndex: Int) {
            runOnUiThread {
                intentTextView.text = ""
                try {
                    porcupineManager?.stop()
                } catch (e: PorcupineException) {
                    displayError("Failed to stop Porcupine.")
                    return@runOnUiThread
                }

                playBeepSound()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        speechRecognizer?.startListening(speechRecognizerIntent)
                        currentState = AppState.STT
                    } catch (e: Exception) {
                        displayError("启动语音识别失败: ${e.message}")
                        playback(1000)
                    }
                }, 200)
            }
        }
    }

    private fun playBeepSound() {
        if (isProcessingAIResponse || isTTSPlaying) return
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 150)
            Handler(Looper.getMainLooper()).postDelayed({ toneGenerator.release() }, 200)
        } catch (e: Exception) {
            Log.e("MainActivity", "播放提示音失败", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        intentTextView = findViewById(R.id.intentView)
        recordButton = findViewById(R.id.record_button)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            displayError("Speech Recognition not available.")
        }

        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(ACCESS_KEY)
                .setKeyword(defaultKeyword)
                .setSensitivity(0.7f)
                .build(applicationContext, porcupineManagerCallback)
        } catch (e: PorcupineInvalidArgumentException) {
            onPorcupineInitError(e.message ?: "Invalid argument")
        } catch (e: PorcupineActivationException) {
            onPorcupineInitError("AccessKey activation error")
        } catch (e: PorcupineActivationLimitException) {
            onPorcupineInitError("AccessKey reached its device limit")
        } catch (e: PorcupineActivationRefusedException) {
            onPorcupineInitError("AccessKey refused")
        } catch (e: PorcupineActivationThrottledException) {
            onPorcupineInitError("AccessKey has been throttled")
        } catch (e: PorcupineException) {
            onPorcupineInitError("Failed to initialize Porcupine ${e.message}")
        }

        currentState = AppState.STOPPED

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Language not supported")
                }
            } else {
                Log.e("TTS", "Initialization failed")
            }
        }

        textToSpeech?.setPitch(1.0f)
        textToSpeech?.setSpeechRate(1.1f)

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

    override fun onStop() {
        if (recordButton.isChecked) {
            stopService()
            recordButton.toggle()
            speechRecognizer?.destroy()
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
        speechRecognizer?.stopListening()
        currentState = AppState.WAKEWORD

        mainHandler.postDelayed({
            if (currentState == AppState.WAKEWORD) {
                try {
                    porcupineManager?.start()
                } catch (e: PorcupineException) {
                    displayError("Failed to start porcupine.")
                }
                intentTextView.setTextColor(Color.WHITE)
                intentTextView.text = "Listening for $defaultKeyword ..."
            }
        }, milliSeconds.toLong())
    }

    private fun stopService() {
        porcupineManager?.let {
            try {
                it.stop()
            } catch (e: PorcupineException) {
                displayError("Failed to stop porcupine.")
            }
        }

        textToSpeech?.takeIf { it.isSpeaking }?.stop()

        networkExecutor.shutdownNow()
        ttsExecutor.shutdownNow()
        networkExecutor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
        ttsExecutor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue())

        intentTextView.text = ""
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        currentState = AppState.STOPPED
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
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(SpeechListener())
            }
            playback(0)
        }
    }

    fun process(view: View) {
        if (recordButton.isChecked) {
            if (hasRecordPermission()) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(SpeechListener())
                }
                playback(0)
            } else {
                requestRecordPermission()
            }
        } else {
            stopService()
        }
    }

    override fun onDestroy() {
        networkExecutor.shutdownNow()
        ttsExecutor.shutdownNow()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        super.onDestroy()
    }

    private fun speakWithTTS(text: String) {
        textToSpeech?.let { tts ->
            if (tts.isSpeaking) tts.stop()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, "tts_utterance")
            } else {
                @Suppress("DEPRECATION")
                tts.speak(text, TextToSpeech.QUEUE_ADD, null)
            }

            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun queryAI(query: String) {
        if (isProcessingAIResponse) return

        isProcessingAIResponse = true
        aiResponseBuilder.setLength(0)
        ttsQueue.clear()

        networkExecutor.execute {
            try {
                val requestBody = JSONObject().apply {
                    put("model", "gpt_175B_0404")
                    put("prompt", query)
                    put("plugin", "Adaptive")
                    put("displayPrompt", query)
                    put("displayPromptType", 1)

                    put("options", JSONObject().apply {
                        put("imageIntention", JSONObject().apply {
                            put("needIntentionModel", true)
                            put("backendUpdateFlag", 2)
                            put("intentionStatus", true)
                        })
                    })

                    put("multimedia", JSONArray())
                    put("agentId", "naQivTmsDa")
                    put("supportHint", 1)
                    put("extReportParams", JSONObject.NULL)
                    put("isAtomInput", false)
                    put("version", "v2")
                    put("chatModelId", "deep_seek_v3")

                    put("chatModelExtInfo",
                        "{\"modelId\":\"deep_seek_v3\",\"subModelId\":\"\",\"supportFunctions\":{\"internetSearch\":\"closeInternetSearch\"}}"
                    )

                    put("applicationIdList", JSONArray())
                    put("supportFunctions", JSONArray().apply {
                        put("closeInternetSearch")
                    })
                }

                val client = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://yuanbao.tencent.com/api/chat/1859758a-1c39-4d37-8ce5-92a38edf70a0")
                    .post(RequestBody.create(
                        "application/json".toMediaTypeOrNull(),
                        requestBody.toString()
                    ))
                    .header("Cookie", "sensorsdata2015jssdkcross=...") // 简化示例
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    mainHandler.post {
                        displayError("AI 请求失败: ${response.code}")
                        playback(1000)
                    }
                    isProcessingAIResponse = false
                    return@execute
                }

                processStreamResponse(response.body!!)
            } catch (e: Exception) {
                mainHandler.post {
                    displayError("AI 请求异常: ${e.message}")
                    playback(1000)
                }
                isProcessingAIResponse = false
            }
        }
    }

    @Throws(IOException::class)
    private fun processStreamResponse(body: ResponseBody) {
        val inputStream = body.byteStream()
        val reader = BufferedReader(InputStreamReader(inputStream))
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            if (line.isNullOrEmpty()) continue

            try {
                if (!line!!.contains("data:")) continue
                val json = JSONObject(line!!.replace("data:", ""))
                val type = json.optString("type", "")
                val msg = json.optString("msg", "")

                if (type == "text" && msg.isNotEmpty()) {
                    aiResponseBuilder.append(msg)

                    mainHandler.post {
                        intentTextView.setTextColor(Color.WHITE)
                        intentTextView.text = "AI: ${aiResponseBuilder}"
                    }

                    synchronized(sentenceBuffer) {
                        sentenceBuffer.append(msg)
                        lastTextReceivedTime = System.currentTimeMillis()
                        checkAndPlaySentence()
                    }
                }
            } catch (e: Exception) {
                Log.e("AI", "解析AI响应失败: $line", e)
            }
        }

        synchronized(sentenceBuffer) {
            if (sentenceBuffer.isNotEmpty()) {
                ttsQueue.offer(sentenceBuffer.toString())
                sentenceBuffer.setLength(0)
                if (!isTTSPlaying) startTTSPlayback()
            }
        }

        reader.close()
        inputStream.close()
        body.close()

        ttsExecutor.execute {
            while (!ttsQueue.isEmpty() || isTTSPlaying) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            mainHandler.post {
                isProcessingAIResponse = false
                playback(3000)
            }
        }
    }

    private fun checkAndPlaySentence() {
        val currentText = sentenceBuffer.toString()
        val isCompleteSentence = currentText.matches(".*[。.？！?!…]$".toRegex())
        val isTooLong = currentText.length > MAX_SENTENCE_LENGTH
        val isTimeout = (System.currentTimeMillis() - lastTextReceivedTime) > SENTENCE_TIMEOUT

        if (isCompleteSentence || isTooLong || isTimeout) {
            ttsQueue.offer(currentText)
            sentenceBuffer.setLength(0)
            if (!isTTSPlaying) startTTSPlayback()
        }
    }

    private fun startTTSPlayback() {
        ttsExecutor.execute {
            isTTSPlaying = true
            try {
                while (!ttsQueue.isEmpty()) {
                    val text = ttsQueue.poll() ?: continue
                    if (text.isNotEmpty()) {
                        speakWithTTS(text)
                        while (textToSpeech?.isSpeaking == true) {
                            Thread.sleep(50)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TTS", "TTS播放失败", e)
            } finally {
                isTTSPlaying = false
            }
        }
    }

    private inner class SpeechListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle) {}

        override fun onError(error: Int) {
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

        override fun onResults(results: Bundle) {
            val data = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!data.isNullOrEmpty()) {
                val recognizedText = data[0]
                mainHandler.post {
                    intentTextView.setTextColor(Color.WHITE)
                    intentTextView.text = "用户: $recognizedText"
                }
                queryAI(recognizedText)
            } else {
                playback(1000)
            }
        }

        override fun onPartialResults(partialResults: Bundle) {
            val data = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            intentTextView.setTextColor(Color.DKGRAY)
            intentTextView.text = data?.get(0) ?: ""
        }
    }
}