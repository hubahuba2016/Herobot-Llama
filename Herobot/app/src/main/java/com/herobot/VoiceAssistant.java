package com.herobot;

import android.app.Activity;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class VoiceAssistant implements TextToSpeech.OnInitListener {

    private TextToSpeech tts = null;
    private boolean ttsReady = false;

    public VoiceAssistant(Activity activity) {
        try {
            tts = new TextToSpeech(activity.getApplicationContext(), this);
        } catch (Exception e) {
            Log.e("VoiceAssistant", "Failed to initialize TTS: " + e.getMessage());
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            try {
                tts.setLanguage(Locale.US);
                ttsReady = true;
            } catch (Exception e) {
                Log.e("VoiceAssistant", "Failed to set language on TTS: " + e.getMessage());
            }
        } else {
            Log.e("VoiceAssistant", "TTS Initialization failed!");
        }
    }

    public void speak(String text) {
        if (ttsReady && tts != null) {
            try {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
            } catch (Exception e) {
                Log.e("VoiceAssistant", "Failed to speak: " + e.getMessage());
            }
        }
    }

    public void startListening(Activity activity, int requestCode) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something...");
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Log.e("VoiceAssistant", "STT not supported on this device");
        }
    }

    public void shutdown() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception e) {
                Log.e("VoiceAssistant", "Failed to shutdown TTS: " + e.getMessage());
            }
        }
    }
}
