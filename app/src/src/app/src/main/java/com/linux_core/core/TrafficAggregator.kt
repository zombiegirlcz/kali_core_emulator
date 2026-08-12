package com.linux_core.core

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TrafficAggregator — mozek pro sběr, klasifikaci a rozhodování o síťových flow.
 *
 * Inicializuje se v [VpnCaptureService.onCreate] a je dostupný přes [getInstance].
 * VpnNatEngine volá [ingestFlow] inline (rychlý DB zápis v try/catch).
 * Periodický timer (HandlerThread) obstarává:
 *   - cleanupExpiredPending — mazání prošlých pending_flows (každých 60s)
 *   - flushPendingToLLM — dávka nedávno detekovaných adres do VerdictEngine (každých 90s)
 *   - driftReverification — reverifikace baseline (1× / 30 dní)
 */
class TrafficAggregator(context: Context) {

    companion object {
        private const val TAG = "TrafficAggregator"

        @Volatile
        private var instance: TrafficAggregator? = null

        fun getInstance(): TrafficAggregator? = instance

        /** Voláno z VpnCaptureService.onCreate */
        fun init(ctx: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = TrafficAggregator(ctx.applicationContext)
                        Log.i(TAG, "TrafficAggregator initialized")
                    }
                }
            }
        }

        /** Voláno z VpnCaptureService při zastavení */
        fun shutdown() {
            instance?.let {
                it.stopPeriodicTasks()
                instance = null
                Log.i(TAG, "TrafficAggregator shut down")
            }
        }
    }

    private val historyStore = TrafficHistoryStore(context)
    private val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
    private val isEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled", false)

    private val appContext = context

    // HandlerThread pro periodické úlohy
    private val handlerThread = HandlerThread("TrafficAggregator").apply { start() }
    private val handler = Handler(handlerThread.looper)

    // VerdictEngine — vytvoří se při prvním flushPendingToLLM
    @Volatile
    private var verdictEngine: VerdictEngine? = null

    // AI brain enabled = decision loop běží
    private var periodicStarted = false

    init {
        startPeriodicTasks()
    }

    // ─────────────────────────────────────────────────────────────
    //  PUBLIC API — voláno z VpnNatEngine a LocalApiServer
    // ─────────────────────────────────────────────────────────────

    /**
     * Zpracuje jeden síťový flow z VPN smyčky.
     *
     * Voláno inline z VpnNatEngine.handleTcpPacket / handleUdpPacket.
     * DB write je lehký (SELECT + UPDATE/INSERT), v try/catch,
     * takže VPN smyčka není blokovaná.
     *
     * Pokud adresa už má platný verdikt (allowed/blocked), flow je potichu
     * přeskočen. Jinak se zapíše do known_addresses a přidá do pending_flows
     * (pokud confidence < prahu nebo adresa není známá).
     */
    fun ingestFlow(
        address: String,
        entropy: Double,
        port: Int,
        brainConfidence: Double?,
        sni: String?
    ) {
        if (!isEnabled) return

        try {
            val now = System.currentTimeMillis()

            // 1. Zkontrolovat existující verdikt — známá adresa s rozhodnutím
            val existingVerdict = historyStore.getAddressVerdict(address)
            if (existingVerdict != null) {
                // Už máme rozhodnutí — jen aktualizujeme last_seen a stat
                historyStore.upsertKnownAddress(address, entropy, port, now)
                return
            }

            // 2. Zapsat / aktualizovat adresu
            historyStore.upsertKnownAddress(address, entropy, port, now)

            // 3. Pokud je confidence nízká nebo adresa nová → pending flow
            val confidence = brainConfidence ?: 0.0
            val confidenceThreshold = prefs.getFloat("ai_sensitivity", 0.5f).toDouble()

            if (confidence < confidenceThreshold) {
                historyStore.upsertPendingFlow(
                    address = address,
                    confidence = confidence,
                    now = now,
                    reason = "low_confidence",
                    sni = sni
                )
            }

            // 4. Daily stat — inkrementujeme total_flows
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
            historyStore.getOrCreateDailyStat(today)
            historyStore.incrementDailyStat(today, "total_flows")

        } catch (e: Exception) {
            Log.e(TAG, "ingestFlow failed for $address: ${e.message}")
        }
    }

    /**
     * Všechny adresy čekající na verdikt (verdict='unknown' + platné pending_flows).
     */
    fun getPendingFlows(): List<TrafficHistoryStore.PendingFlow> {
        return try {
            historyStore.getPendingFlows()
        } catch (e: Exception) {
            Log.e(TAG, "getPendingFlows failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Plná historie adresy z known_addresses.
     */
    fun getAddressHistory(address: String): TrafficHistoryStore.KnownAddress? {
        return try {
            historyStore.getAddressHistory(address)
        } catch (e: Exception) {
            Log.e(TAG, "getAddressHistory failed: ${e.message}")
            null
        }
    }

    /**
     * Nastaví verdikt pro adresu.
     * Voláno z VerdictEngine (LLM), VerdictNotifier (uživatel), nebo LocalApiServer.
     */
    fun setVerdict(
        address: String,
        verdict: String,
        source: String,
        confidence: Double,
        note: String? = null,
        traceId: String? = null
    ) {
        try {
            historyStore.setVerdict(address, verdict, source, confidence, note, traceId)

            // Fire-and-forget Phoenix telemetrie
            val toolCallsForExport = if (source == "llm") listOf("set_verdict") else emptyList()
            Thread {
                PhoenixExporter.exportVerdict(
                    address = address,
                    verdict = verdict,
                    source = source,
                    confidence = confidence,
                    toolCalls = toolCallsForExport,
                    traceId = traceId,
                    context = appContext
                )
            }.start()

            Log.i(TAG, "Verdict set: $address → $verdict (source=$source, conf=$confidence)")

            // Daily stat
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            historyStore.getOrCreateDailyStat(today)
            when (verdict) {
                "blocked" -> historyStore.incrementDailyStat(today, "blocked_count")
                "allowed" -> historyStore.incrementDailyStat(today, "allowed_count")
                "pending_user" -> historyStore.incrementDailyStat(today, "pending_count")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setVerdict failed: ${e.message}")
        }
    }

    /**
     * Porovná aktuální metriky s baseline a vrátí true pokud došlo k driftu.
     */
    fun runDriftCheck(address: String): Boolean {
        return try {
            val history = historyStore.getAddressHistory(address) ?: return false
            val now = System.currentTimeMillis()

            // Throttle: max 1× / 24h
            if (history.lastReverifyAt != null &&
                (now - history.lastReverifyAt) < 24 * 60 * 60 * 1000L
            ) return false

            val baseEntropy = history.baselineEntropy ?: return false
            val currentEntropy = history.avgEntropy ?: return false
            val entropyDiff = kotlin.math.abs(currentEntropy - baseEntropy)

            // Drift = změna entropie > 50% baseline
            val driftThreshold = baseEntropy * 0.5
            val drifted = entropyDiff > driftThreshold

            if (drifted) {
                Log.w(TAG, "Drift detected for $address: baseline=$baseEntropy, current=$currentEntropy")
                // Přidat zpět do pending_flows pro re-escalaci
                historyStore.upsertPendingFlow(
                    address = address,
                    confidence = 0.0,
                    now = now,
                    reason = "drift",
                    sni = null
                )
                historyStore.setVerdict(address, "unknown", "drift", 0.0, "Drift re-verification", null)
            }

            historyStore.updateReverifyTimestamp(address)
            drifted
        } catch (e: Exception) {
            Log.e(TAG, "runDriftCheck failed for $address: ${e.message}")
            false
        }
    }

    /**
     * Zjednodušený sumář statistik za posledních 24h.
     */
    fun getDailyStat(date: String? = null): TrafficHistoryStore.DailyStat? {
        val target = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return try {
            historyStore.getOrCreateDailyStat(target)
        } catch (e: Exception) {
            Log.e(TAG, "getDailyStat failed: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  PERIODICKÉ ÚLOHY (HandlerThread)
    // ─────────────────────────────────────────────────────────────

    private fun startPeriodicTasks() {
        if (periodicStarted) return
        periodicStarted = true

        // Cleanup expired pending flows — každých 60s
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (isEnabled) historyStore.cleanupExpiredPending()
                } catch (e: Exception) {
                    Log.e(TAG, "periodicCleanup error: ${e.message}")
                }
                handler.postDelayed(this, 60_000L)
            }
        }, 60_000L)

        // Flush pending flows → LLM (VerdictEngine) — každých 90s
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (isEnabled) flushPendingToLLM()
                } catch (e: Exception) {
                    Log.e(TAG, "flushPendingToLLM error: ${e.message}")
                }
                handler.postDelayed(this, 90_000L)
            }
        }, 90_000L)

        // Drift re-verification — 1× / 30 minut (v praxi 1× / 30 dní díky throttlingu v DB)
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (isEnabled) driftReverification()
                } catch (e: Exception) {
                    Log.e(TAG, "driftReverification error: ${e.message}")
                }
                handler.postDelayed(this, 1_800_000L) // 30 min
            }
        }, 1_800_000L)

        Log.i(TAG, "Periodic tasks started (cleanup=60s, flushLLM=90s, drift=30min)")
    }

    /**
     * Pošle pending flows do VerdictEngine pro LLM klasifikaci.
     * VerdictEngine se přidá v Kroku 3 — prozatím jen loguje.
     */
    private fun flushPendingToLLM() {
        val pending = historyStore.getPendingFlows()
        if (pending.isEmpty()) return

        val endpoint = prefs.getString("llm_endpoint", null)
        if (endpoint.isNullOrBlank()) {
            // Bez LLM endpointu — pending flows zůstávají viset
            Log.d(TAG, "No LLM endpoint configured, ${pending.size} flows pending")
            return
        }

        // Lazy-init VerdictEngine
        if (verdictEngine == null) {
            synchronized(this) {
                if (verdictEngine == null) {
                    verdictEngine = VerdictEngine(appContext, this)
                }
            }
        }

        Log.i(TAG, "Flushing ${pending.size} flows to VerdictEngine")

        // Označit jako escalated před voláním LLM
        for (flow in pending) {
            if (!flow.escalatedToLlm) {
                historyStore.markEscalated(flow.id)
            }
        }

        verdictEngine?.flushPendingFlows()
    }

    /**
     * Zkontroluje známé adresy s vysokým occurrence_count na drift.
     * Throttling: max 50 adres na jeden běh.
     */
    private fun driftReverification() {
        val candidates = historyStore.getAddressesNeedingReverify(
            intervalDays = 30,
            minOccurrences = 10
        )
        if (candidates.isEmpty()) return

        Log.i(TAG, "Drift re-verification: ${candidates.size} candidates")
        for (addr in candidates) {
            runDriftCheck(addr.address)
        }
    }

    private fun stopPeriodicTasks() {
        periodicStarted = false
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
        Log.i(TAG, "Periodic tasks stopped")
    }
}
