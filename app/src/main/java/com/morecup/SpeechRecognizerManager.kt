package com.morecup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class SpeechRecognizerManager(private val context: Context) {
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechRecognizerIntent: Intent
    private var speechCallback: SpeechCallback? = null
    private var isListening = false

    // 定义简化的回调接口
    interface SpeechCallback {
        fun onReadyForSpeech() {}
        fun onBeginningOfSpeech() {}
        fun onRmsChanged(rmsdB: Float) {}
        fun onPartialResults(partialResults: String) {}
        fun onResults(results: String) {}
        fun onError(error: Int) {}
        fun onEndOfSpeech() {}
    }

    init {
        initializeSpeechRecognizer()
    }

    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw IllegalStateException("Speech recognition not available on this device")
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer.setRecognitionListener(SpeechListener())

        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
    }

    fun setSpeechCallback(callback: SpeechCallback) {
        this.speechCallback = callback
    }

    fun startListening() {
        if (isListening) return

        try {
            speechRecognizer.startListening(speechRecognizerIntent)
            isListening = true
        } catch (e: Exception) {
            Log.e("SpeechRecognizerManager", "Failed to start listening", e)
            isListening = false
            throw e
        }
    }

    fun stopListening() {
        if (!isListening) return

        try {
            speechRecognizer.stopListening()
            isListening = false
        } catch (e: Exception) {
            Log.e("SpeechRecognizerManager", "Failed to stop listening", e)
            throw e
        }
    }

    fun destroy() {
        try {
            speechRecognizer.destroy()
            isListening = false
        } catch (e: Exception) {
            Log.e("SpeechRecognizerManager", "Failed to destroy speech recognizer", e)
            throw e
        }
    }

    fun isListening(): Boolean {
        return isListening
    }

    // 内部的RecognitionListener实现
    private inner class SpeechListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            speechCallback?.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() {
            speechCallback?.onBeginningOfSpeech()
        }

        override fun onRmsChanged(rmsdB: Float) {
            speechCallback?.onRmsChanged(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            speechCallback?.onEndOfSpeech()
        }

        override fun onError(error: Int) {
            isListening = false
            speechCallback?.onError(error)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!data.isNullOrEmpty()) {
                speechCallback?.onResults(data[0])
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val data = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!data.isNullOrEmpty()) {
                speechCallback?.onPartialResults(data[0])
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}