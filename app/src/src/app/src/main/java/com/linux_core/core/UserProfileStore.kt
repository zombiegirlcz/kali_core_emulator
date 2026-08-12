package com.linux_core.core

import android.content.Context
import android.util.Log
import com.linux_core.security.CertificateManager
import org.json.JSONObject
import java.io.File

/**
 * Ukládá "otisky" běžného chování uživatele.
 *
 * Když je k dispozici [com.linux_core.security.KeystoreManager], soubor na disku
 * je uložen v zašifrované podobě (AES-GCM-256 s klíčem v TEE); plaintext žije
 * pouze v paměti. Pokud KeyStoreManager není inicializovaný (např. v testech),
 * fallback na prostý JSON jako dříve.
 */
object UserProfileStore {
    private const val TAG = "UserProfileStore"
    private const val PROFILE_FILE = "user_behavior_profile.json"
    private const val ENC_MARKER = "enc:"

    // Mapa: Klíč (Protokol:Port) -> Průměrné hodnoty (Entropy, Size, Delta)
    private var profileData = JSONObject()

    fun load(context: Context) {
        val file = File(context.filesDir, PROFILE_FILE)
        if (!file.exists()) return
        val raw = file.readText()
        val ks = CertificateManager.keystore()
        profileData = if (ks != null && raw.startsWith(ENC_MARKER)) {
            val plain = ks.decryptString(raw.removePrefix(ENC_MARKER)).getOrNull()
            if (plain != null) JSONObject(plain) else JSONObject()
        } else if (raw.isNotEmpty()) {
            JSONObject(raw)
        } else {
            JSONObject()
        }
    }

    /**
     * Uloží nový vzorek chování a zaktualizuje průměr (Incremental Learning).
     */
    fun learnNormalPattern(proto: Int, port: Int, entropy: Float, size: Int) {
        val key = "$proto:$port"
        val pattern = profileData.optJSONObject(key) ?: JSONObject().apply {
            put("count", 0)
            put("avg_entropy", 0.0)
            put("avg_size", 0.0)
        }

        val count = pattern.getInt("count")
        val newCount = count + 1
        
        // Výpočet klouzavého průměru (Welford's algorithm zjednodušeně)
        val avgEntropy = (pattern.getDouble("avg_entropy") * count + entropy) / newCount
        val avgSize = (pattern.getDouble("avg_size") * count + size) / newCount

        pattern.put("count", newCount)
        pattern.put("avg_entropy", avgEntropy)
        pattern.put("avg_size", avgSize)
        
        profileData.put(key, pattern)
    }

    /**
     * Vrací skóre odchylky (0.0 = normální, 1.0 = úplně cizí).
     */
    fun getAnomalyScore(proto: Int, port: Int, entropy: Float, size: Int): Float {
        val key = "$proto:$port"
        val pattern = profileData.optJSONObject(key) ?: return 1.0f // Neznámý port = potenciální hrozba
        
        val dEntropy = Math.abs(pattern.getDouble("avg_entropy") - entropy).toFloat()
        val dSize = Math.abs(pattern.getDouble("avg_size") - size).toFloat() / 1500f
        
        return (dEntropy + dSize).coerceAtMost(1.0f)
    }

    fun save(context: Context) {
        val file = File(context.filesDir, PROFILE_FILE)
        val plain = profileData.toString()
        val ks = CertificateManager.keystore()
        if (ks != null) {
            val enc = ks.encryptString(plain).getOrNull()
            if (enc != null) {
                file.writeText(ENC_MARKER + enc)
                return
            }
        }
        file.writeText(plain)
    }
}
