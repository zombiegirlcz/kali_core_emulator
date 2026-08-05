package com.linux_core.core

import android.content.Context
import android.util.Log
import com.linux_core.security.CertificateManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VerdictEngine — LLM arbiter pro rozhodování o síťových flow.
 *
 * Voláno z [TrafficAggregator.flushPendingToLLM] na HandlerThread.
 * Posílá pending flows do OpenAI-compatible API s function-calling
 * a mapuje tool-call response zpět na Kotlin metody.
 *
 * Životní cyklus řídí [TrafficAggregator] — vytvoří instanci při prvním
 * flush, znovupoužívá ji. Pokud [llmEndpoint] není nakonfigurován,
 * engine nedělá nic.
 */
class VerdictEngine(
    private val context: Context,
    private val trafficAggregator: TrafficAggregator
) {
    companion object {
        private const val TAG = "VerdictEngine"
        private const val TIMEOUT_SEC = 10L
        private const val RETRY_COUNT = 1
    }

    private val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)

    /** URL OpenAI-compatible API (např. https://api.openai.com/v1) */
    private val llmEndpoint: String?
        get() = prefs.getString("llm_endpoint", null)?.trim()?.takeIf { it.isNotBlank() }

    /** API klíč — dešifrovaný přes KeystoreManager */
    private val llmApiKey: String?
        get() {
            val ks = CertificateManager.keystore()
            val stored = prefs.getString("llm_api_key", null)
            if (ks != null && stored != null && stored.startsWith("enc:")) {
                return ks.decryptString(stored.removePrefix("enc:"))
                    .getOrNull()
            }
            // Fallback: plaintext (migrace ze starší verze)
            return stored?.takeIf { it.isNotBlank() }
        }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // ─────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────

    /**
     * Pošle dávku pending flows do LLM a zpracuje tool-call response.
     * Voláno z HandlerThread — může blokovat až 10s.
     */
    fun flushPendingFlows() {
        val endpoint = llmEndpoint ?: return
        val apiKey = llmApiKey ?: run {
            Log.w(TAG, "LLM API key not configured")
            return
        }
        val pending = trafficAggregator.getPendingFlows()
        if (pending.isEmpty()) return

        Log.i(TAG, "Flushing ${pending.size} pending flows to LLM ($endpoint)")

        val systemPrompt = buildSystemPrompt()
        val userMessage = buildUserMessage(pending)
        val functions = buildFunctionDefinitions()

        var lastError: String? = null

        for (attempt in 0..RETRY_COUNT) {
            if (attempt > 0) {
                Log.w(TAG, "Retry attempt $attempt for LLM call")
                Thread.sleep(500L)
            }

            try {
                val response = callLlm(endpoint, apiKey, systemPrompt, userMessage, functions)
                if (response != null) {
                    if (processResponse(response, pending)) return
                    // Pokud response neobsahuje platný tool-call, pokračujeme
                    lastError = "No valid tool_call in response"
                } else {
                    lastError = "Null response"
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.e(TAG, "LLM call failed (attempt ${attempt+1}): ${e.message}")
            }
        }

        // Fallback — pokud LLM selhal, označíme jako pending_user
        Log.w(TAG, "LLM failed after ${RETRY_COUNT+1} attempts: $lastError")
        for (flow in pending) {
            trafficAggregator.setVerdict(
                address = flow.address,
                verdict = "pending_user",
                source = "llm_fallback",
                confidence = 0.0,
                note = "LLM unavailable: $lastError"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  LLM COMMUNICATION
    // ─────────────────────────────────────────────────────────────

    private fun callLlm(
        endpoint: String,
        apiKey: String,
        systemPrompt: String,
        userMessage: JSONObject,
        functions: JSONArray
    ): JSONObject? {
        val body = JSONObject().apply {
            put("model", "gpt-4o-mini") // nebo jiný model dle endpointu
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage.toString())
                })
            })
            put("functions", functions)
            put("function_call", "auto")
            put("temperature", 0.1) // nízká teplota = konzistentní verdikty
        }

        val request = Request.Builder()
            .url("${endpoint.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "LLM returned HTTP ${response.code}: ${response.body?.string()}")
                return null
            }
            val json = JSONObject(response.body?.string() ?: return null)
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
            choice
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  RESPONSE PROCESSING
    // ─────────────────────────────────────────────────────────────

    /**
     * Zpracuje LLM response. Pokud obsahuje function_call, dispatchneme ho.
     * Vrací true pokud bylo vše zpracováno, false pokud response neobsahuje tool-call.
     */
    private fun processResponse(choice: JSONObject, pending: List<TrafficHistoryStore.PendingFlow>): Boolean {
        val message = choice.optJSONObject("message") ?: return false
        val finishReason = choice.optString("finish_reason", "")

        if (finishReason == "function_call" || message.has("function_call")) {
            val fc = message.getJSONObject("function_call")
            val name = fc.optString("name", "")
            val args = try {
                JSONObject(fc.optString("arguments", "{}"))
            } catch (e: Exception) {
                JSONObject()
            }
            dispatchToolCall(name, args)
            return true
        }

        // Fallback: LLM vrátilo text místo function_call — zkusíme parsovat
        val content = message.optString("content", "")
        if (content.isNotBlank()) {
            Log.w(TAG, "LLM returned text instead of function_call: $content")
            // Pokud LLM napsalo "blocked 185.220.101.4", zkusíme pattern
            tryParseTextVerdict(content, pending)
        }

        return false
    }

    /**
     * Fallback parser pro případ, že LLM nevrátí function_call.
     * Hledá patterny jako "blocked 1.2.3.4" v textu.
     */
    private fun tryParseTextVerdict(content: String, pending: List<TrafficHistoryStore.PendingFlow>) {
        for (flow in pending) {
            val addr = flow.address
            val ipPattern = addr.replace(".", "\\.")
            // Hledá "allowed 1.2.3.4" nebo "blocked 1.2.3.4"
            val blockedMatch = Regex("blocked\\s+$ipPattern", RegexOption.IGNORE_CASE).find(content)
            val allowedMatch = Regex("allowed\\s+$ipPattern", RegexOption.IGNORE_CASE).find(content)

            val verdict = when {
                blockedMatch != null -> "blocked"
                allowedMatch != null -> "allowed"
                else -> "pending_user"
            }
            trafficAggregator.setVerdict(
                address = addr,
                verdict = verdict,
                source = "llm_text_fallback",
                confidence = 0.5,
                note = "Parsed from LLM text response"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  TOOL-CALL DISPATCH
    // ─────────────────────────────────────────────────────────────

    private fun dispatchToolCall(name: String, args: JSONObject) {
        Log.i(TAG, "LLM tool_call: $name(${args.toString()})")
        when (name) {
            "get_pending_flows" -> {
                // Informational — LLM si jen vyžádala data
                // TrafficAggregator už má pending flows k dispozici
            }
            "get_address_history" -> {
                val address = args.optString("address", "")
                if (address.isNotBlank()) {
                    trafficAggregator.getAddressHistory(address)
                }
            }
            "enable_mitm_for_flow" -> {
                val address = args.optString("address", "")
                val duration = args.optInt("duration_sec", 30).coerceIn(1, 60)
                if (address.isNotBlank()) {
                    TlsMitmEngine.startCaptureOnlyForAddress(address, duration * 1000L)
                    Log.i(TAG, "Selective MITM activated for $address (${duration}s)")
                }
            }
            "set_verdict" -> {
                val address = args.optString("address", "")
                val verdict = args.optString("verdict", "pending_user")
                val confidence = args.optDouble("confidence", 0.5)
                val note = args.optString("note", "")
                if (address.isNotBlank()) {
                    trafficAggregator.setVerdict(
                        address = address,
                        verdict = verdict,
                        source = "llm",
                        confidence = confidence,
                        note = note
                    )
                }
            }
            "notify_user" -> {
                val address = args.optString("address", "")
                val question = args.optString("question", "Allow this connection?")
                if (address.isNotBlank()) {
                    VerdictNotifier.notify(
                        address = address,
                        question = question,
                        confidence = args.optDouble("confidence", 0.5),
                        context = context
                    )
                    Log.i(TAG, "User notification sent for $address: $question")
                }
            }
            "summarize_24h" -> {
                trafficAggregator.getDailyStat()
                // Informational — LLM dostane data v next prompt
            }
            else -> {
                Log.w(TAG, "Unknown tool_call: $name")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  PROMPT & SCHEMA BUILDERS
    // ─────────────────────────────────────────────────────────────

    private fun buildSystemPrompt(): String = """
        You are a network traffic analysis AI for NetHunter VPN.
        Your job is to classify IP addresses based on their network behavior.
        
        Classification categories:
        0 = Safe — normal web browsing, CDN, cloud services
        1 = Recon — port scanning, directory brute-force, probing
        2 = Exploit — known exploit payloads, C2 beaconing
        3 = Spoof — DNS tunneling, IP spoofing, fake SSL
        4 = Counter — C2 ports, DDoS, reverse shell
        5 = Retreat — emergency shutdown signal
        
        For each pending address, use the available tools to:
        1. Get address history
        2. Analyze entropy and pattern
        3. Set verdict (blocked/allowed/pending_user)
        
        Always use set_verdict tool to make a decision for each address.
        If uncertain, use notify_user to ask the user.
    """.trimIndent()

    private fun buildUserMessage(pending: List<TrafficHistoryStore.PendingFlow>): JSONObject {
        val flows = JSONArray()
        for (flow in pending) {
            val history = trafficAggregator.getAddressHistory(flow.address)
            flows.put(JSONObject().apply {
                put("a", flow.address)
                put("n", flow.occurrenceCount)      // occurrence count
                put("iv", history?.avgIntervalSec ?.toFloat() ?: 0.0)  // avg interval
                put("ent", history?.avgEntropy ?.toFloat() ?: 0.0)     // avg entropy
                put("p", history?.typicalPort ?: 0) // typical port
                put("sni", flow.sni ?: "")
                put("b_conf", flow.brainConfidence?.toFloat() ?: 0.0)  // brain confidence
            })
        }
        return JSONObject().apply {
            put("pending_flows", flows)
            put("count", pending.size)
        }
    }

    private fun buildFunctionDefinitions(): JSONArray {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("name", "get_pending_flows")
                put("description", "Get list of addresses waiting for verdict")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
            put(JSONObject().apply {
                put("name", "get_address_history")
                put("description", "Get full history for a specific address")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("address", JSONObject().apply {
                            put("type", "string")
                            put("description", "IP address to query")
                        })
                    })
                    put("required", JSONArray().put("address"))
                })
            })
            put(JSONObject().apply {
                put("name", "enable_mitm_for_flow")
                put("description", "Enable TLS MITM capture for a specific address for N seconds (max 60)")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("address", JSONObject().apply {
                            put("type", "string")
                            put("description", "Target IP address")
                        })
                        put("duration_sec", JSONObject().apply {
                            put("type", "integer")
                            put("description", "Capture duration in seconds (max 60)")
                            put("minimum", 1)
                            put("maximum", 60)
                        })
                    })
                    put("required", JSONArray().apply {
                        put("address")
                        put("duration_sec")
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "set_verdict")
                put("description", "Set final verdict for an address (allowed/blocked/pending_user)")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("address", JSONObject().apply {
                            put("type", "string")
                            put("description", "IP address")
                        })
                        put("verdict", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray().apply {
                                put("allowed")
                                put("blocked")
                                put("pending_user")
                            })
                        })
                        put("confidence", JSONObject().apply {
                            put("type", "number")
                            put("description", "Confidence score 0.0-1.0")
                            put("minimum", 0.0)
                            put("maximum", 1.0)
                        })
                        put("note", JSONObject().apply {
                            put("type", "string")
                            put("description", "Optional reasoning note")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("address")
                        put("verdict")
                        put("confidence")
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "notify_user")
                put("description", "Ask user to make a decision via notification (Allow/Deny)")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("address", JSONObject().apply {
                            put("type", "string")
                            put("description", "IP address")
                        })
                        put("question", JSONObject().apply {
                            put("type", "string")
                            put("description", "Question to show in notification")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("address")
                        put("question")
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "summarize_24h")
                put("description", "Get 24-hour traffic summary statistics")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        }
    }
}
