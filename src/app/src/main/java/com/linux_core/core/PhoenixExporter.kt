package com.linux_core.core

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Phoenix OTLP telemetrický export.
 *
 * Volá se asynchronně (fire-and-forget) po každém [TrafficAggregator.setVerdict].
 * Posílá span do Phoenix OTLP endpointu.
 *
 * Všechny operace v try/catch — telemetrie nikdy nesmí shodit hlavní smyčku.
 * Konfigurace: `phoenix_endpoint` v SharedPreferences (default http://localhost:6006/v1/traces).
 */
object PhoenixExporter {
    private const val TAG = "PhoenixExporter"
    private const val DEFAULT_ENDPOINT = "http://localhost:6006/v1/traces"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * Exportuje jeden verdikt jako OTLP span.
     *
     * Voláno z [TrafficAggregator.setVerdict] na fire-and-forget thread:
     * ```kotlin
     * Thread { PhoenixExporter.exportVerdict(address, verdict, confidence, ...) }.start()
     * ```
     *
     * @param address Cílová IP adresa
     * @param verdict 'allowed', 'blocked', 'pending_user'
     * @param source 'llm', 'user_confirmed', 'ai_auto', 'timeout', 'drift'
     * @param confidence Confidence score 0.0-1.0
     * @param toolCalls Seznam tool-callů použitých při rozhodování (jen pro LLM source)
     * @param traceId Volitelný trace ID pro korelaci
     * @param context Android Context (pro čtení SharedPreferences)
     */
    fun exportVerdict(
        address: String,
        verdict: String,
        source: String,
        confidence: Double,
        toolCalls: List<String> = emptyList(),
        traceId: String? = null,
        context: Context
    ) {
        try {
            val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            val endpoint = prefs.getString("phoenix_endpoint", DEFAULT_ENDPOINT)
                ?: DEFAULT_ENDPOINT

            val now = System.currentTimeMillis()
            val spanId = traceId ?: "vpn-${address.hashCode().and(0x7FFFFFFF)}-${now}"

            val body = buildOtlpJson(spanId, now, address, verdict, source, confidence, toolCalls)
            if (body == null) {
                Log.w(TAG, "Skipping Phoenix export — no data")
                return
            }

            val request = Request.Builder()
                .url(endpoint.trimEnd('/'))
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Phoenix export HTTP ${response.code} for $address")
                } else {
                    Log.d(TAG, "Phoenix export OK for $address ($verdict, conf=$confidence)")
                }
            }
        } catch (e: Exception) {
            // Telemetrie nikdy nesmí shodit volajícího
            Log.w(TAG, "Phoenix export failed: ${e.message}")
        }
    }

    /**
     * Sestaví OTLP JSON podle specifikace:
     * https://opentelemetry.io/docs/specs/otlp/#json-protobuf-encoding
     *
     * Struktura:
     * ```json
     * {
     *   "resourceSpans": [{
     *     "resource": { "attributes": [{"key":"service.name","value":{"stringValue":"vpn_ai_brain"}}] },
     *     "scopeSpans": [{
     *       "scope": { "name": "vpn_ai_verdict" },
     *       "spans": [{
     *         "traceId": "...",
     *         "spanId": "...",
     *         "name": "verdict:1.2.3.4",
     *         "kind": 1,  // INTERNAL
     *         "startTimeUnixNano": ...,
     *         "endTimeUnixNano": ...,
     *         "attributes": [
     *           {"key":"vpn.address","value":{"stringValue":"1.2.3.4"}},
     *           {"key":"vpn.verdict","value":{"stringValue":"blocked"}},
     *           {"key":"vpn.confidence","value":{"doubleValue":0.82}},
     *           {"key":"vpn.source","value":{"stringValue":"llm"}},
     *           {"key":"tool_calls","value":{"stringValue":"set_verdict,get_address_history"}}
     *         ]
     *       }]
     *     }]
     *   }]
     * }
     * ```
     */
    private fun buildOtlpJson(
        traceId: String,
        timestamp: Long,
        address: String,
        verdict: String,
        source: String,
        confidence: Double,
        toolCalls: List<String>
    ): JSONObject? {
        if (address.isBlank()) return null

        val traceIdHex = traceId.take(32).padEnd(32, '0')
        val spanIdHex = traceIdHex.take(16)

        val nanoTime = timestamp * 1_000_000L // ms → ns

        return JSONObject().apply {
            put("resourceSpans", JSONArray().apply {
                put(JSONObject().apply {
                    put("resource", JSONObject().apply {
                        put("attributes", JSONArray().apply {
                            put(otlpAttr("service.name", "vpn_ai_brain"))
                            put(otlpAttr("telemetry.sdk.language", "kotlin"))
                        })
                    })
                    put("scopeSpans", JSONArray().apply {
                        put(JSONObject().apply {
                            put("scope", JSONObject().apply {
                                put("name", "vpn_ai_verdict")
                                put("version", "1.0.0")
                            })
                            put("spans", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("traceId", traceIdHex)
                                    put("spanId", spanIdHex)
                                    put("name", "verdict:$address")
                                    put("kind", 1) // SPAN_KIND_INTERNAL
                                    put("startTimeUnixNano", nanoTime)
                                    put("endTimeUnixNano", nanoTime)
                                    put("attributes", JSONArray().apply {
                                        put(otlpAttr("vpn.address", address))
                                        put(otlpAttr("vpn.verdict", verdict))
                                        put(otlpAttr("vpn.confidence", confidence))
                                        put(otlpAttr("vpn.source", source))
                                        if (toolCalls.isNotEmpty()) {
                                            put(otlpAttr("tool_calls", toolCalls.joinToString(",")))
                                        }
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }
    }

    private fun otlpAttr(key: String, value: String): JSONObject {
        return JSONObject().apply {
            put("key", key)
            put("value", JSONObject().apply {
                put("stringValue", value)
            })
        }
    }

    private fun otlpAttr(key: String, value: Double): JSONObject {
        return JSONObject().apply {
            put("key", key)
            put("value", JSONObject().apply {
                put("doubleValue", value)
            })
        }
    }
}
