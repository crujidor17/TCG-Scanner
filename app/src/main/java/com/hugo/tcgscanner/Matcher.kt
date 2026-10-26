package com.hugo.tcgscanner

import kotlin.math.abs
import kotlin.math.max

fun normalize(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

private fun similarity(a: String, b: String): Double {
    val m = max(a.length, b.length)
    return if (m == 0) 0.0 else 1.0 - levenshtein(a, b).toDouble() / m
}

class CardIndex(catalog: List<Blueprint>) {
    private val byName: Map<String, List<Blueprint>> =
        catalog.groupBy { normalize(it.name) }.filterKeys { it.length >= 4 }
    private val names = byName.keys.toList()
    val isEmpty: Boolean get() = names.isEmpty()

    /** Busca en el texto del OCR la carta que mejor coincide con el catálogo. */
    fun find(text: String): Blueprint? {
        if (names.isEmpty()) return null
        val lines = text.lines().map { normalize(it) }.filter { it.length >= 4 }
        if (lines.isEmpty()) return null

        var bestName: String? = null
        var bestScore = 0.0
        for (line in lines) {
            for (name in names) {
                val s = when {
                    line == name -> 1.0
                    name.length >= 6 && line.contains(name) -> 0.95
                    abs(line.length - name.length) > max(3, name.length / 3) -> 0.0
                    else -> similarity(line, name)
                }
                if (s > bestScore || (s == bestScore && s > 0 && name.length > (bestName?.length ?: 0))) {
                    bestScore = s
                    bestName = name
                }
            }
        }
        if (bestName == null || bestScore < 0.86) return null

        val candidates = byName[bestName] ?: return null
        if (candidates.size == 1) return candidates[0]
        // Varias versiones con el mismo nombre: desempata con el número impreso (p. ej. 296/298).
        val m = Regex("(\\d{1,3})\\s*/\\s*(\\d{1,3})").find(text)
        val printed = m?.groupValues?.get(1)?.trimStart('0')
        if (printed != null) {
            candidates.firstOrNull { it.number.substringBefore('/').trimStart('0') == printed }?.let { return it }
        }
        return candidates.firstOrNull { it.version.isBlank() } ?: candidates[0]
    }

    fun search(query: String): List<Blueprint> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        return byName.entries
            .filter { it.key.contains(q) }
            .sortedBy { it.key.length }
            .flatMap { it.value }
            .take(25)
    }
}
