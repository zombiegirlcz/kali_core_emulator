package com.linux_core.core

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.FloatBuffer
import java.util.Collections

class AIBrain(private val context: Context) {
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    init {
        try {
            val modelBytes = context.assets.open("vpn_brain_v2.onnx").readBytes()
            ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            Log.i("AIBrain", "🧠 Tactical VPN Brain v2 loaded successfully!")
        } catch (e: Exception) {
            Log.e("AIBrain", "❌ Failed to load Tactical VPN Brain: ${e.message}")
        }
    }

    /**
     * Predikce kategorie provozu.
     * Vrací: 0=Safe, 1=Recon, 2=Exploit, 3=Spoof, 4=Counter, 5=Retreat
     */
    fun classify(features: FloatArray): Int {
        // --- Znalostní heuristiky (Offensive Knowledge) ---
        val dstPort = if (features.size > 4) features[4].toInt() else 0
        val totalSize = features[0].toInt()
        
        // Detekce známých útočných vzorů (Heuristika jako fallback)
        if (dstPort == 4444 || dstPort == 8888) return 4 // Counter
        if (totalSize > 1400 && dstPort == 53) return 3  // Spoof/Exfil

        if (ortSession == null) return 0
        
        try {
            val inputName = ortSession?.inputNames?.iterator()?.next() ?: "float_input"
            val shape = longArrayOf(1, 18) // Batch size 1, 18 features (NEW)
            
            var label = 0
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(features), shape).use { tensor ->
                ortSession?.run(Collections.singletonMap(inputName, tensor))?.use { result ->
                    val output = result.get(0).value as LongArray // LightGBM v ONNX vrací Long pro labely
                    label = output[0].toInt()
                }
            }
            return label
        } catch (e: Exception) {
            Log.e("AIBrain", "Inference error: ${e.message}")
            return 0
        }
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
    }
}
