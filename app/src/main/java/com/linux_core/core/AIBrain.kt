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
            val modelBytes = context.assets.open("vpn_brain.onnx").readBytes()
            ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            Log.i("AIBrain", "🧠 VPN Brain loaded successfully!")
        } catch (e: Exception) {
            Log.e("AIBrain", "❌ Failed to load VPN Brain: ${e.message}")
        }
    }

    /**
     * Predikce kategorie provozu.
     * Vrací: 0=Normal, 1=DNS, 2=Critical
     */
    fun classify(features: FloatArray): Int {
        if (ortSession == null) return 0
        
        try {
            val inputName = ortSession?.inputNames?.iterator()?.next() ?: "float_input"
            val shape = longArrayOf(1, 14) // Batch size 1, 14 features
            val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(features), shape)
            
            val result = ortSession?.run(Collections.singletonMap(inputName, tensor))
            val output = result?.get(0)?.value as LongArray // LightGBM v ONNX vrací Long pro labely
            
            return output[0].toInt()
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
