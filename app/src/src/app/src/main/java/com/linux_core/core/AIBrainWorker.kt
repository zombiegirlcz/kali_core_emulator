package com.linux_core.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asynchronní worker pro AI inferenci pomocí Kotlin Coroutines.
 *
 * Odděluje AI inference z packet-processing cesty do samostatného korutinového
 * dispatcheru. Pakety jsou frontovány a zpracovávány dávkově, což snižuje
 * latenci a umožňuje lepší využití CPU při ONNX inferenci.
 */
class AIBrainWorker(private val context: Context) {

    companion object {
        private const val TAG = "AIBrainWorker"
        private const val QUEUE_CAPACITY = 256
        private const val BATCH_SIZE = 4
        private const val HIGH_PRIORITY_THRESHOLD_BYTES = 100
        private const val CACHE_TTL_MS = 10_000L
    }

    data class InferenceRequest(
        val protocol: Int,
        val srcPort: Int,
        val dstIpStr: String,
        val dstPort: Int,
        val payload: ByteArray?,
        val totalSize: Int,
        val sessionKey: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val isHighPriority: Boolean get() = totalSize < HIGH_PRIORITY_THRESHOLD_BYTES
    }

    data class InferenceResult(
        val category: VpnLogManager.AuditCategory,
        val decision: Int
    )

    private val brain: AIBrain = AIBrain(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(true)

    // Fronta pro inference requesty — kapacita 256, suspend if full
    private val inferenceChannel = Channel<InferenceRequest>(QUEUE_CAPACITY)

    // Cache výsledků inferencí pro opakované stejné flow (sessionKey)
    private val inferenceCache = ConcurrentHashMap<String, CachedResult>()

    private data class CachedResult(
        val result: InferenceResult,
        val expiresAt: Long
    )

    // Job pro zpracování fronty
    private var processorJob: Job? = null

    // Historie pro stavovou analýzu (přesunuto z VpnNatEngine)
    internal val sessionByteCounts = ConcurrentHashMap<String, Long>()
    internal val sessionPacketCounts = ConcurrentHashMap<String, Int>()
    internal val lastPacketTimes = ConcurrentHashMap<String, Long>()

    init {
        startProcessor()
    }

    private fun startProcessor() {
        processorJob = scope.launch {
            Log.i(TAG, "AI Brain Worker processor started on ${Thread.currentThread().name}")
            val pendingBatch = mutableListOf<InferenceRequest>()
            while (isRunning.get()) {
                try {
                    // Vyčkej na první request
                    val first = inferenceChannel.receive()
                    pendingBatch.add(first)

                    // Sesbírej až BATCH_SIZE requestů (s timeoutem 2ms pro dávkování)
                    val deadline = System.nanoTime() + 2_000_000L // 2ms
                    while (pendingBatch.size < BATCH_SIZE &&
                        System.nanoTime() < deadline
                    ) {
                        val next = inferenceChannel.tryReceive().getOrNull() ?: break
                        pendingBatch.add(next)
                    }

                    // Zpracuj dávku
                    for (request in pendingBatch) {
                        if (!isRunning.get()) break
                        processRequest(request)
                    }
                    pendingBatch.clear()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Processor error: ${e.message}")
                }
            }
            Log.i(TAG, "AI Brain Worker processor terminated")
        }
    }

    private suspend fun processRequest(request: InferenceRequest) {
        val now = System.currentTimeMillis()
        val lastTime = lastPacketTimes[request.sessionKey] ?: now
        val delta = (now - lastTime) / 1000.0f
        lastPacketTimes[request.sessionKey] = now

        val cumulativeBytes = (sessionByteCounts[request.sessionKey] ?: 0L) + request.totalSize
        val packetCount = (sessionPacketCounts[request.sessionKey] ?: 0) + 1
        sessionByteCounts[request.sessionKey] = cumulativeBytes
        sessionPacketCounts[request.sessionKey] = packetCount

        val currentEntropy = request.payload?.let { calculateEntropy(it) } ?: 0.0f

        val features = buildFeatures(request, delta, cumulativeBytes, packetCount, currentEntropy)

        // 1. GLOBÁLNÍ ZNALOST (ONNX Model)
        val strategyIndex = withContext(Dispatchers.IO) {
            brain.classify(features)
        }

        // 2. OSOBNÍ PAMĚŤ (Behavioral Profile)
        val anomalyScore = UserProfileStore.getAnomalyScore(
            request.protocol, request.dstPort, currentEntropy, request.totalSize
        )

        if (strategyIndex == 0 && anomalyScore < 0.3f) {
            UserProfileStore.learnNormalPattern(
                request.protocol, request.dstPort, currentEntropy, request.totalSize
            )
        }

        val finalDecision = if (strategyIndex == 0 && anomalyScore > 0.8f && packetCount > 10) {
            Log.w(TAG, "PERSONAL MEMORY ALERT: Unusual behavior for port ${request.dstPort}")
            4
        } else strategyIndex

        // Async zápis do historie (fire-and-forget)
        scope.launch {
            try {
                TrafficHistoryStore(context).logSession(
                    "App_Session", request.dstIpStr, request.dstPort, null,
                    request.totalSize.toLong(), currentEntropy, finalDecision
                )
            } catch (e: Exception) {
                Log.e(TAG, "History log failed: ${e.message}")
            }
        }

        if (finalDecision > 0) {
            val strategy = when (finalDecision) {
                1 -> OffensiveEngine.AttackStrategy.RECON
                2 -> OffensiveEngine.AttackStrategy.EXPLOIT
                3 -> OffensiveEngine.AttackStrategy.SPOOF
                4 -> OffensiveEngine.AttackStrategy.COUNTER
                else -> OffensiveEngine.AttackStrategy.RETREAT
            }

            val shouldExecute = when {
                finalDecision == 5 -> true
                finalDecision == 4 -> packetCount > 3
                else -> true
            }
            if (shouldExecute) {
                OffensiveEngine.execute(context, strategy, request.dstIpStr, request.dstPort)
            }
            saveTrainingSample(features, finalDecision)
        }

        // Ulož do cache
        val category = when (finalDecision) {
            1, 2, 3, 4 -> VpnLogManager.AuditCategory.CRITICAL
            else -> VpnLogManager.AuditCategory.ALLOWED
        }
        inferenceCache[request.sessionKey] = CachedResult(
            InferenceResult(category, finalDecision),
            System.currentTimeMillis() + CACHE_TTL_MS
        )
    }

    /**
     * Zařadí packet k AI inferenci. Voláno z packet-processing vlákna.
     * Vrací se okamžitě — výsledek je zpracován asynchronně.
     */
    fun submitForInference(request: InferenceRequest): Boolean {
        if (!isRunning.get()) return false

        // Zkusíme cache — pokud máme čerstvý výsledek pro tuto session, nefrontujeme
        val cached = getCachedResult(request.sessionKey)
        if (cached != null) return true

        return try {
            inferenceChannel.trySend(request).isSuccess
        } catch (e: Exception) {
            Log.w(TAG, "Failed to queue inference: ${e.message}")
            false
        }
    }

    /**
     * Získání výsledku inferenční analýzy. Pokud není k dispozici,
     * vrátí výchozí ALLOWED — packet není blokován.
     */
    fun getCachedResult(sessionKey: String): InferenceResult? {
        val cached = inferenceCache[sessionKey] ?: return null
        if (System.currentTimeMillis() > cached.expiresAt) {
            inferenceCache.remove(sessionKey)
            return null
        }
        return cached.result
    }

    private fun buildFeatures(
        request: InferenceRequest,
        delta: Float,
        cumulativeBytes: Long,
        packetCount: Int,
        currentEntropy: Float
    ): FloatArray {
        val features = FloatArray(18)
        features[0] = request.totalSize.toFloat()
        features[1] = request.protocol.toFloat()
        features[2] = delta
        features[3] = request.srcPort.toFloat()
        features[4] = request.dstPort.toFloat()
        features[5] = currentEntropy

        // b0-b7
        val payload = request.payload
        if (payload != null) {
            val limit = minOf(payload.size, 8)
            for (i in 0 until limit) {
                features[6 + i] = (payload[i].toInt() and 0xFF).toFloat()
            }
        }

        features[14] = (cumulativeBytes / 1024.0).toFloat()
        features[15] = packetCount.toFloat()
        features[16] = if (request.totalSize < 100 && packetCount > 50) 1.0f else 0.0f
        features[17] = (payload?.size ?: 0).toFloat()
        return features
    }

    private fun calculateEntropy(data: ByteArray): Float {
        if (data.isEmpty()) return 0.0f
        val counts = IntArray(256)
        for (b in data) {
            counts[b.toInt() and 0xFF]++
        }
        var entropy = 0.0
        for (count in counts) {
            if (count > 0) {
                val p = count.toDouble() / data.size
                entropy -= p * (Math.log(p) / Math.log(2.0))
            }
        }
        return entropy.toFloat()
    }

    private fun saveTrainingSample(features: FloatArray, label: Int) {
        scope.launch {
            try {
                val logFile = java.io.File(context.filesDir, "offensive_learning_data.csv")
                val exists = logFile.exists()
                val writer = java.io.FileWriter(logFile, true)
                if (!exists) {
                    writer.write("size,proto,delta,src,dst,entropy,b0,b1,b2,b3,b4,b5,b6,b7,mss,nop,flood,p_len,label\n")
                }
                val line = features.joinToString(",") + ",$label\n"
                writer.write(line)
                writer.close()
            } catch (e: Exception) {
                Log.e(TAG, "Training data collection failed: ${e.message}")
            }
        }
    }

    fun close() {
        isRunning.set(false)
        processorJob?.cancel()
        inferenceChannel.close()
        brain.close()
        scope.cancel()
        inferenceCache.clear()
        sessionByteCounts.clear()
        sessionPacketCounts.clear()
        lastPacketTimes.clear()
        Log.i(TAG, "AI Brain Worker shut down")
    }
}
