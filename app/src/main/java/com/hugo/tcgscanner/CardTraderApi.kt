package com.hugo.tcgscanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CardTraderApi(private val token: () -> String) {
    private val base = "https://api.cardtrader.com/api/v2"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private var lastCall = 0L

    // CardTrader limita /marketplace/products a 1 llamada por segundo: se aplica a todas las peticiones.
    private suspend fun get(path: String): String = mutex.withLock {
        if (token().isBlank()) throw IOException("Falta el token de CardTrader (Ajustes)")
        val wait = 1100 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        for (attempt in 0..3) {
            val req = Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer " + token())
                .build()
            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
            }
            lastCall = System.currentTimeMillis()
            if (code in 200..299) return@withLock body
            if (code == 429 && attempt < 3) {
                delay(2000L * (attempt + 1))
                continue
            }
            throw IOException("HTTP $code: ${body.take(160)}")
        }
        throw IOException("Demasiadas peticiones (429)")
    }

    private fun parseList(body: String): JSONArray {
        val t = body.trim()
        if (t.startsWith("[")) return JSONArray(t)
        val o = JSONObject(t)
        o.optJSONArray("array")?.let { return it }
        val keys = o.keys()
        while (keys.hasNext()) {
            o.optJSONArray(keys.next())?.let { return it }
        }
        return JSONArray()
    }

    /** Descarga todas las cartas de Riftbound (juegos -> expansiones -> blueprints). */
    suspend fun downloadRiftboundCatalog(progress: (String) -> Unit): List<Blueprint> {
        progress("Buscando Riftbound en CardTrader…")
        val games = parseList(get("/games"))
        var gameId = -1
        for (i in 0 until games.length()) {
            val g = games.getJSONObject(i)
            val n = (g.str("display_name") + " " + g.str("name")).lowercase()
            if ("riftbound" in n) {
                gameId = g.getInt("id")
                break
            }
        }
        if (gameId < 0) throw IOException("No encuentro Riftbound en /games de CardTrader")

        progress("Descargando expansiones…")
        val exps = parseList(get("/expansions"))
        val expansions = mutableListOf<Pair<Int, String>>()
        for (i in 0 until exps.length()) {
            val e = exps.getJSONObject(i)
            if (e.optInt("game_id", -1) == gameId) expansions.add(e.getInt("id") to e.str("name"))
        }
        if (expansions.isEmpty()) throw IOException("Riftbound no tiene expansiones en /expansions")

        val result = mutableListOf<Blueprint>()
        expansions.forEachIndexed { i, (expId, expName) ->
            progress("Descargando ${expName} (${i + 1}/${expansions.size})…")
            val bps = parseList(get("/blueprints/export?expansion_id=$expId"))
            for (j in 0 until bps.length()) {
                toBlueprint(bps.getJSONObject(j), expName)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun toBlueprint(o: JSONObject, expName: String): Blueprint? {
        val id = o.optInt("id", -1)
        val name = o.str("name")
        if (id < 0 || name.isBlank()) return null
        val fixed = o.optJSONObject("fixed_properties")
        val number = (fixed?.str("collector_number") ?: "").ifBlank { fixed?.str("number") ?: "" }
        var img = o.str("image_url")
        if (img.isBlank()) img = o.optJSONObject("image")?.str("url") ?: ""
        if (img.startsWith("/")) img = "https://www.cardtrader.com$img"
        return Blueprint(id, name, o.str("version"), expName, number, img.ifBlank { null })
    }

    private data class Offer(val cents: Int, val currency: String, val nearMint: Boolean, val foil: Boolean)

    /**
     * Precio más bajo a la venta ahora mismo en CardTrader (no es "última venta" ni media de 30 días,
     * CardTrader no expone histórico de ventas). Prefiere no-foil y Near Mint/Mint si existen.
     */
    suspend fun fetchPrice(blueprintId: Int): PriceInfo? {
        val body = get("/marketplace/products?blueprint_id=$blueprintId").trim()
        if (!body.startsWith("{")) return null
        val root = JSONObject(body)
        val offers = mutableListOf<Offer>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val arr = root.optJSONArray(keys.next()) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optJSONObject("price")
                val cents = if (p != null) p.optInt("cents", -1) else o.optInt("price_cents", -1)
                if (cents < 0) continue
                val cur = (if (p != null) p.str("currency") else o.str("price_currency")).ifBlank { "EUR" }
                val props = o.optJSONObject("properties_hash")
                val cond = (props?.str("condition") ?: "").lowercase()
                var foil = false
                if (props != null) {
                    val pk = props.keys()
                    while (pk.hasNext()) {
                        val k = pk.next()
                        if ("foil" in k.lowercase() && props.str(k).lowercase() == "true") foil = true
                    }
                }
                offers.add(Offer(cents, cur, "mint" in cond, foil))
            }
        }
        var pool: List<Offer> = offers
        pool.filter { !it.foil }.let { if (it.isNotEmpty()) pool = it }
        pool.filter { it.nearMint }.let { if (it.isNotEmpty()) pool = it }
        return pool.minByOrNull { it.cents }?.let { PriceInfo(it.cents, it.currency) }
    }
}
