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

    /**
     * Výsledek ONNX inference včetně míry jistoty.
     * @param label 0=Safe, 1=Recon, 2=Exploit, 3=Spoof, 4=Counter, 5=Retreat
     * @param confidence 0.0..1.0 (vyšší = jistější)
     */
    data class BrainResult(val label: Int, val confidence: Float)

    init {
        // Load ONNX session asynchronously to prevent blocking the main thread
        java.lang.Thread {
            try {
                val modelBytes = context.assets.open("vpn_brain_v7.onnx").readBytes()
                ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
                Log.i(TAG, "VPN Brain NN v7 loaded asynchronously — 18->256->128->64->6 (47K params)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load VPN Brain: ${e.message}")
            }
        }.start()
    }

    /**
     * Klasifikace síťového provozu do 6 kategorií s mírou jistoty.
     *
     * confidence = 1 - (top1 - top2) → vyšší hodnota = jistější rozhodnutí.
     * Pokud je rozdíl mezi nejlepší a druhou nejlepší třídou malý,
     * model si není jistý a confidence je nízká.
     *
     * @param features 18 prvků (raw, žádný scaler): size,proto,delta,srcPort,dstPort,entropy,b0..b7,cumKB,packetCount,flood,payloadLen
     * @return BrainResult(label=0..5, confidence=0.0..1.0)
     */
    fun classifyWithConfidence(features: FloatArray): BrainResult {
        val dstPort = if (features.size > 4) features[4].toInt() else 0
        val totalSize = features[0].toInt()

        // Heuristika pro známé C2 porty a DNS tunely (fallback před ONNX)
        if (dstPort in C2_PORTS) return BrainResult(4, 0.95f)
        if (totalSize > 1400 && dstPort == 53) return BrainResult(3, 0.90f)

        if (ortSession == null) return BrainResult(0, 0.5f)

        try {
            val inputName = ortSession?.inputNames?.iterator()?.next() ?: "float_input"
            val shape = longArrayOf(1, 18)

            return OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(features), shape).use { tensor ->
                ortSession?.run(Collections.singletonMap(inputName, tensor))?.use { result ->
                    val outTensor = result.get(0) as? OnnxTensor ?: return@use BrainResult(0, 0.5f)
                    val buffer = outTensor.floatBuffer

                    // Najdi top-2 softmax hodnoty
                    var maxIdx = 0
                    var maxVal = buffer.get(0)
                    var secondMax = -Float.MAX_VALUE

                    for (i in 1 until 6) {
                        val v = buffer.get(i)
                        if (v > maxVal) {
                            secondMax = maxVal
                            maxVal = v
                            maxIdx = i
                        } else if (v > secondMax) {
                            secondMax = v
                        }
                    }

                    // margin = 1 - (top1 - top2) → vyšší = nejistější
                    // confidence = 1 - margin → vyšší = jistější
                    val diff = maxVal - secondMax
                    val margin = 1f - diff.coerceIn(0f, 1f)
                    val confidence = 1f - margin

                    BrainResult(maxIdx, confidence.coerceIn(0f, 1f))
                } ?: BrainResult(0, 0.5f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            return BrainResult(0, 0.5f)
        }
    }

    /**
     * Zpětně kompatibilní wrapper — vrací jen label.
     * Volá [classifyWithConfidence] interně.
     */
    fun classify(features: FloatArray): Int {
        return classifyWithConfidence(features).label
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
    }
}
