package com.hugo.tcgscanner

import org.json.JSONObject
import java.util.Locale

data class Blueprint(
    val id: Int,
    val name: String,
    val version: String,
    val expansion: String,
    val number: String,
    val imageUrl: String?
) {
    val displayName: String get() = if (version.isBlank()) name else "$name ($version)"
}

data class PriceInfo(val cents: Int, val currency: String)

data class CartItem(val blueprint: Blueprint, val price: PriceInfo?, val qty: Int = 1)

data class CollectionCard(
    val uid: String,
    val blueprint: Blueprint,
    val qty: Int,
    val scanPrice: PriceInfo?,
    val scanDate: Long,
    val currentPrice: PriceInfo?,
    val updatedDate: Long?
)

fun JSONObject.str(key: String): String {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

fun Blueprint.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("version", version)
    .put("exp", expansion)
    .put("num", number)
    .put("img", imageUrl ?: JSONObject.NULL)

fun blueprintFrom(o: JSONObject): Blueprint = Blueprint(
    id = o.getInt("id"),
    name = o.str("name"),
    version = o.str("version"),
    expansion = o.str("exp"),
    number = o.str("num"),
    imageUrl = o.str("img").ifBlank { null }
)

private fun PriceInfo?.toJsonValue(): Any =
    if (this == null) JSONObject.NULL else JSONObject().put("cents", cents).put("cur", currency)

private fun priceFrom(o: JSONObject, key: String): PriceInfo? =
    o.optJSONObject(key)?.let { PriceInfo(it.getInt("cents"), it.str("cur").ifBlank { "EUR" }) }

fun CollectionCard.toJson(): JSONObject = JSONObject()
    .put("uid", uid)
    .put("bp", blueprint.toJson())
    .put("qty", qty)
    .put("scanPrice", scanPrice.toJsonValue())
    .put("scanDate", scanDate)
    .put("currentPrice", currentPrice.toJsonValue())
    .put("updatedDate", updatedDate ?: JSONObject.NULL)

fun collectionCardFrom(o: JSONObject): CollectionCard = CollectionCard(
    uid = o.getString("uid"),
    blueprint = blueprintFrom(o.getJSONObject("bp")),
    qty = o.optInt("qty", 1),
    scanPrice = priceFrom(o, "scanPrice"),
    scanDate = o.optLong("scanDate", 0L),
    currentPrice = priceFrom(o, "currentPrice"),
    updatedDate = if (o.isNull("updatedDate")) null else o.optLong("updatedDate")
)

/** Suma unidades a una entrada existente y se queda con el precio actual más reciente. */
fun CollectionCard.absorb(extraQty: Int, price: PriceInfo?, date: Long?): CollectionCard {
    val newer = price != null && (date ?: 0L) >= (updatedDate ?: 0L)
    return copy(
        qty = (qty + extraQty).coerceAtMost(MAX_QTY),
        currentPrice = if (newer) price else currentPrice,
        updatedDate = if (newer) date else updatedDate
    )
}

/** Une entradas repetidas de la misma carta (mismo blueprint) sumando sus cantidades. */
fun mergeDuplicates(cards: List<CollectionCard>): List<CollectionCard> {
    val byBlueprint = LinkedHashMap<Int, CollectionCard>()
    for (c in cards) {
        val prev = byBlueprint[c.blueprint.id]
        byBlueprint[c.blueprint.id] = prev?.absorb(c.qty, c.currentPrice, c.updatedDate) ?: c
    }
    return byBlueprint.values.toList()
}

const val MAX_QTY = 9999

fun fmt(p: PriceInfo?): String =
    if (p == null) "—" else String.format(Locale.getDefault(), "%.2f %s", p.cents / 100.0, p.currency)

fun total(items: List<CartItem>): PriceInfo? {
    val priced = items.filter { it.price != null }
    if (priced.isEmpty()) return null
    val cur = priced.first().price!!.currency
    return PriceInfo(priced.filter { it.price!!.currency == cur }.sumOf { it.price!!.cents * it.qty }, cur)
}

fun totalCollection(cards: List<CollectionCard>, current: Boolean): PriceInfo? {
    val priced = cards.mapNotNull { c -> (if (current) c.currentPrice ?: c.scanPrice else c.scanPrice)?.let { it to c.qty } }
    if (priced.isEmpty()) return null
    val cur = priced.first().first.currency
    return PriceInfo(priced.filter { it.first.currency == cur }.sumOf { it.first.cents * it.second }, cur)
}
