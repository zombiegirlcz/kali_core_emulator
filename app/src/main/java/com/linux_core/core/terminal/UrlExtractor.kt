package com.linux_core.core.terminal

/**
 * Detekce http/https odkazů v textu terminálu (scrollback i viditelná obrazovka).
 *
 * Používá se ze kontextového menu terminálu („copy | more“ → „Kopírovat URL“):
 * zdrojem je `TerminalBuffer.getTranscriptTextWithFullLinesJoined()`, které už
 * spojuje zalomené řádky — URL rozlámaná šířkou terminálu tedy přijde v jednom
 * kusu a nepotřebuje tu vlastní „unwrap“ heuristiku.
 *
 * Čistá funkce bez Android závislostí → testovatelná v JVM
 * (`app/src/test/java/com/linux_core/core/terminal/UrlExtractorTest.kt`).
 */
object UrlExtractor {
    /** Strop seznamu, aby dlouhý scrollback nevyrobil nepoužitelný dialog. */
    const val MAX_URLS = 100

    // Konec URL: whitespace a znaky, které se v URL nepoužívají bez escapování.
    // Uvozovky/zpětné lomítko/závorky řeší trimTrailing() níž (kvůli párování).
    private val URL_REGEX =
        Regex("""https?://[^\s<>"'`\\^{}|]+""", RegexOption.IGNORE_CASE)

    // Interpunkce, kterou terminál typicky lepí za odkaz ve větě/logu.
    private const val TRAILING = ".,;:!?…"

    /**
     * Vrátí unikátní odkazy, **nejnovější první** (tj. od konce bufferu —
     * poslední výpis uživatele je ten, který obvykle chce).
     */
    fun extract(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        val hits = URL_REGEX.findAll(text).map { trimTrailing(it.value) }.filter { it.length > 8 }.toList()
        for (url in hits.asReversed()) {
            seen.add(url)
            if (seen.size >= MAX_URLS) break
        }
        return seen.toList()
    }

    /**
     * Ořeže koncovou interpunkci a nepárovou uzavírací závorku/hranatou závorku.
     * `(https://a/b)` → `https://a/b`, ale `https://a/b_(c)` zůstane celé.
     */
    internal fun trimTrailing(raw: String): String {
        var s = raw
        while (s.isNotEmpty()) {
            val c = s.last()
            when {
                TRAILING.indexOf(c) >= 0 -> s = s.dropLast(1)
                c == ')' && s.count { it == '(' } < s.count { it == ')' } -> s = s.dropLast(1)
                c == ']' && s.count { it == '[' } < s.count { it == ']' } -> s = s.dropLast(1)
                else -> return s
            }
        }
        return s
    }
}
