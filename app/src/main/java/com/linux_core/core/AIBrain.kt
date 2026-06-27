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

    companion object {
        const val TAG = "AIBrain"
        val C2_PORTS = setOf(4444, 8888, 1337, 31337, 5555, 6666, 7777, 9999, 12345, 23456, 34567, 45678)
    }

    init {
        try {
            val modelBytes = context.assets.open("vpn_brain_v2.onnx").readBytes()
            ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            Log.i(TAG, "VPN Brain NN v7 loaded — 18->256->128->64->6 (47K params)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load VPN Brain: ${e.message}")
        }
    }

    /**
     * Klasifikace sitoveho provozu do 6 kategorii.
     * @param features 18 prvku (raw, zadny scaler): size,proto,delta,srcPort,dstPort,entropy,b0..b7,cumKB,packetCount,flood,payloadLen
     * @return 0=Safe, 1=Recon, 2=Exploit, 3=Spoof, 4=Counter, 5=Retreat
     */
    fun classify(features: FloatArray): Int {
        val dstPort = if (features.size > 4) features[4].toInt() else 0
        val totalSize = features[0].toInt()

        // Heuristika pro zname C2 porty a DNS tunely (fallback)
        if (dstPort in C2_PORTS) return 4
        if (totalSize > 1400 && dstPort == 53) return 3

        if (ortSession == null) return 0

        try {
            val inputName = ortSession?.inputNames?.iterator()?.next() ?: "float_input"
            val shape = longArrayOf(1, 18)

            var label = 0
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(features), shape).use { tensor ->
                ortSession?.run(Collections.singletonMap(inputName, tensor))?.use { result ->
                    val outTensor = result.get(0) as? OnnxTensor ?: return 0
                    val buffer = outTensor.floatBuffer
                    var maxIdx = 0
                    var maxVal = buffer.get(0)
                    for (i in 1 until 6) {
                        val v = buffer.get(i)
                        if (v > maxVal) {
                            maxVal = v
                            maxIdx = i
                        }
                    }
                    label = maxIdx
                }
            }
            return label
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            return 0
        }
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
    }
}
