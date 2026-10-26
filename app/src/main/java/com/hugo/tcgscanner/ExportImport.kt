package com.hugo.tcgscanner

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Una línea válida del archivo a importar: "Nombre de la carta X copias". */
data class ImportLine(val raw: String, val name: String, val qty: Int)

/** Resumen que se muestra al terminar una importación. */
data class ImportReport(
    val cards: Int,
    val copies: Int,
    val notFound: List<String>,
    val ambiguous: Int,
    val noPrice: Int
)

/** Export_SoloCartas_ddMMyyyy.txt  /  Export_CartasPrecio_ddMMyyyy.txt */
fun exportFileName(withPrice: Boolean, date: Date = Date()): String {
    val day = SimpleDateFormat("ddMMyyyy", Locale.US).format(date)
    return (if (withPrice) "Export_CartasPrecio_" else "Export_SoloCartas_") + day + ".txt"
}

private fun priceText(p: PriceInfo?): String {
    if (p == null) return "sin precio"
    val amount = String.format(Locale.getDefault(), "%.2f", p.cents / 100.0)
    return if (p.currency.equals("EUR", ignoreCase = true)) "${amount}€" else "$amount ${p.currency}"
}

/**
 * Una carta por línea.
 *  - Solo cartas:     "Nombre (Versión) 3 copias"
 *  - Cartas y precio: "Nombre (Versión) 3 copias 12,50€"  (precio por unidad, el actual)
 */
fun buildExportText(cards: List<CollectionCard>, withPrice: Boolean): String =
    cards.joinToString(separator = "\n", postfix = "\n") { c ->
        val base = "${c.blueprint.displayName} ${c.qty} copias"
        if (withPrice) base + " " + priceText(c.currentPrice ?: c.scanPrice) else base
    }

// "<nombre> <número> copia(s)" y, opcionalmente, cualquier cosa detrás (p. ej. el precio).
private val LINE_RE = Regex("^(.+?)\\s+(\\d{1,4})\\s*copias?\\b.*$", RegexOption.IGNORE_CASE)

/** Devuelve (líneas válidas, líneas que no cumplen el formato). */
fun parseImportLines(lines: List<String>): Pair<List<ImportLine>, List<String>> {
    val ok = ArrayList<ImportLine>()
    val bad = ArrayList<String>()
    for (raw in lines) {
        val line = raw.replace("\uFEFF", "").trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val m = LINE_RE.find(line)
        val qty = m?.groupValues?.get(2)?.toIntOrNull()
        if (m == null || qty == null || qty < 1) {
            bad.add(line)
            continue
        }
        ok.add(ImportLine(line, m.groupValues[1].trim(), qty))
    }
    return ok to bad
}
