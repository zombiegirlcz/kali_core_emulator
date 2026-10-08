package com.linux_core.core.assistant

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Placeholder RecognitionService — musí existovat kvůli `android:recognitionService`
 * v `res/xml/assistant_discovery.xml` (VoiceInteractionService), ale vlastní rozpoznávání
 * řeči neimplementuje. Klient proto hned dostane chybu, místo aby čekal donekonečna.
 */
class NetHunterSpeechRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "NetHunterSpeechRecService"
    }

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback?) {
        Log.d(TAG, "onStartListening → ERROR_CLIENT (rozpoznávání není implementované)")
        try {
            callback?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: RemoteException) {
            Log.w(TAG, "callback.error failed: ${e.message}")
        }
    }

    override fun onCancel(callback: Callback?) {
        Log.d(TAG, "onCancel")
    }

    override fun onStopListening(callback: Callback?) {
        Log.d(TAG, "onStopListening")
    }
}
