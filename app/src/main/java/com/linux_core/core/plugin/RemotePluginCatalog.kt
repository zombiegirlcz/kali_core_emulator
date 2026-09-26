package com.linux_core.core.plugin

import android.content.Context
import android.util.Log
import com.linux_core.sdk.plugin.PluginKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Jedna verze balíčku v `fdroid/index.json` (viz docs/plans/2026-09-26-plugin-system-design.md). */
data class PluginPackageVersion(
    /** app/root pluginy — Android `versionCode`. Null pro asset pluginy. */
    val versionCode: Int?,
    /** asset pluginy — volná verzovací řetězec (např. "1.2.0"). Null pro app/root. */
    val version: String?,
    val sha256: String,
    /** jarsigner podpis, base64. Null = index ještě nepodepsán (WIP repo). */
    val sig: String?,
    val url: String,
    val minCore: Int,
    val bridgeApi: Int?,
    val deps: List<String>,
) {
    /** Sjednocený label pro UI/log: `versionCode` u app/root, jinak `version`. */
    val displayVersion: String
        get() = versionCode?.toString() ?: version ?: "?"
}

/** Jeden balíček (app/asset/root plugin) v katalogu, identifikovaný klíčem z `packages`. */
data class PluginPackage(
    val id: String,
    val kind: PluginKind,
    val versions: List<PluginPackageVersion>,
) {
    /** Nejnovější verze podle `versionCode` (app/root); u asset pluginů poslední v poli. */
    val latest: PluginPackageVersion?
        get() = versions.maxByOrNull { it.versionCode ?: 0 } ?: versions.lastOrNull()
}

data class PluginRepoInfo(val name: String, val address: String)

data class PluginIndex(
    val repo: PluginRepoInfo,
    val packages: List<PluginPackage>,
) {
    fun find(id: String): PluginPackage? = packages.firstOrNull { it.id == id }

    companion object {
        fun fromJson(json: String): PluginIndex {
            val root = JSONObject(json)
            val repoObj = root.getJSONObject("repo")
            val repo = PluginRepoInfo(
                name = repoObj.optString("name", ""),
                address = repoObj.optString("address", ""),
            )

            val packagesObj = root.getJSONObject("packages")
            val packages = mutableListOf<PluginPackage>()
            for (id in packagesObj.keys()) {
                val pkgObj = packagesObj.getJSONObject(id)
                val kind = PluginKind.fromWire(pkgObj.optString("kind", "")) ?: continue
                val versionsArr: JSONArray = pkgObj.optJSONArray("versions") ?: JSONArray()
                val versions = mutableListOf<PluginPackageVersion>()
                for (i in 0 until versionsArr.length()) {
                    val v = versionsArr.getJSONObject(i)
                    val depsArr = v.optJSONArray("deps")
                    val deps = mutableListOf<String>()
                    if (depsArr != null) {
                        for (j in 0 until depsArr.length()) deps.add(depsArr.getString(j))
                    }
                    versions.add(
                        PluginPackageVersion(
                            versionCode = if (v.has("versionCode") && !v.isNull("versionCode")) v.getInt("versionCode") else null,
                            version = if (v.has("version") && !v.isNull("version")) v.getString("version") else null,
                            sha256 = v.optString("sha256", ""),
                            sig = if (v.has("sig") && !v.isNull("sig")) v.getString("sig") else null,
                            url = v.optString("url", ""),
                            minCore = v.optInt("minCore", 0),
                            bridgeApi = if (v.has("bridgeApi") && !v.isNull("bridgeApi")) v.getInt("bridgeApi") else null,
                            deps = deps,
                        ),
                    )
                }
                packages.add(PluginPackage(id = id, kind = kind, versions = versions))
            }

            return PluginIndex(repo = repo, packages = packages)
        }
    }
}

/**
 * Klient pro `fdroid/index.json` — plugin marketplace katalog v tomto monorepu
 * (git = zdroj pravdy, payloady na GitHub Releases). Vzor: [com.linux_core.core.rootfs.RemoteRootfsCatalog].
 *
 * TODO(Fáze 9, docs/plans/2026-09-26-plugin-system-design.md): index bude podepsán
 * jarsignerem stejným klíčem jako `release.jks` — před použitím výsledku k instalaci
 * (`nh plugin install`) MUSÍ klient ověřit `sig` proti tomuto katalogu. Dokud CI
 * podepisování neexistuje, tento klient je jen pro READ-ONLY zobrazení katalogu
 * (`nh plugin search`/`info`), NEPOUŽÍVAT k instalaci bez podpisu.
 */
object RemotePluginCatalog {
    private const val TAG = "RemotePluginCatalog"
    private const val GITHUB_REPO = "zombiegirlcz/kali_core_emulator"
    private const val INDEX_BRANCH = "master"
    private const val INDEX_URL =
        "https://raw.githubusercontent.com/$GITHUB_REPO/$INDEX_BRANCH/fdroid/index.json"
    private const val PREFS_NAME = "remote_plugin_catalog"
    private const val KEY_LAST_FETCH_MS = "last_fetch_ms"
    private const val CACHE_VALID_MS = 30 * 60 * 1000L // 30 minut

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Volatile private var cachedIndex: PluginIndex? = null

    fun getCachedIndex(): PluginIndex? = cachedIndex

    fun fetchIndex(context: Context, forceRefresh: Boolean = false): Flow<PluginIndex?> =
        flow {
            emit(fetchIndexSync(context, forceRefresh))
        }.flowOn(Dispatchers.IO)

    fun fetchIndexSync(context: Context, forceRefresh: Boolean = false): PluginIndex? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!forceRefresh) {
            val lastFetch = prefs.getLong(KEY_LAST_FETCH_MS, 0L)
            val cached = cachedIndex
            if (cached != null && System.currentTimeMillis() - lastFetch < CACHE_VALID_MS) {
                return cached
            }
        }

        return try {
            val request = Request.Builder().url(INDEX_URL).build()
            val json = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("fdroid index HTTP ${response.code}")
                response.body?.string() ?: throw IOException("Empty fdroid index response")
            }
            val index = PluginIndex.fromJson(json)
            cachedIndex = index
            prefs.edit().putLong(KEY_LAST_FETCH_MS, System.currentTimeMillis()).apply()
            index
        } catch (e: Exception) {
            Log.e(TAG, "fetchIndexSync failed: ${e.message}")
            cachedIndex
        }
    }
}
