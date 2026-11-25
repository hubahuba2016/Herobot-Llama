package com.herobot.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class VoiceAssistant(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            try {
                tts?.language = Locale.US
                ttsReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set language on TTS: ${e.message}")
            }
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    fun speak(text: String) {
        if (ttsReady && tts != null && text.isNotBlank()) {
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "herobot_${System.currentTimeMillis()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to speak: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop TTS: ${e.message}")
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to shutdown TTS: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VoiceAssistant"

        fun createSpeechInputIntent(): Intent {
            return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something...")
            }
        }
    }
}
