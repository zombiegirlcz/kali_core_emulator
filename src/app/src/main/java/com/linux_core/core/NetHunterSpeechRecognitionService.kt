package com.linux_core.core

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

class NetHunterSpeechRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "NetHunterSpeechRecService"
    }

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback?) {
        Log.d(TAG, "onStartListening")
    }

    override fun onCancel(callback: Callback?) {
        Log.d(TAG, "onCancel")
    }

    override fun onStopListening(callback: Callback?) {
        Log.d(TAG, "onStopListening")
    }
}
