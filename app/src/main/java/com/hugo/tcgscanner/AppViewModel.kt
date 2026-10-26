package com.hugo.tcgscanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val api = CardTraderApi { store.token }

    private val _token = MutableStateFlow(store.token)
    val token: StateFlow<String> = _token

    private val _catalog = MutableStateFlow(store.loadCatalog())
    val catalog: StateFlow<List<Blueprint>> = _catalog

    private val _cart = MutableStateFlow<List<CartItem>>(emptyList())
    val cart: StateFlow<List<CartItem>> = _cart

    private val _collection = MutableStateFlow(store.loadCollection())
    val collection: StateFlow<List<CollectionCard>> = _collection

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _lastScan = MutableStateFlow<CartItem?>(null)
    val lastScan: StateFlow<CartItem?> = _lastScan

    @Volatile
    private var index = CardIndex(_catalog.value)

    // Estado del detector (siempre se toca desde el hilo principal).
    private var candidateId = -1
    private var candidateCount = 0
    private var lastAddedId = -1
    private var noMatchSince = 0L
    private var lookupBusy = false

    fun saveToken(t: String) {
        store.token = t
        _token.value = store.token
        _message.value = "Token guardado"
    }

    fun downloadCatalog() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val list = api.downloadRiftboundCatalog { _message.value = it }
                store.saveCatalog(list)
                _catalog.value = list
                index = CardIndex(list)
                _message.value = "Catálogo descargado: ${list.size} cartas"
            } catch (e: Exception) {
                _message.value = "Error al descargar el catálogo: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Lo llama el analizador de cámara (hilo de fondo) con el texto reconocido en cada fotograma. */
    fun onFrameText(text: String) {
        val bp = index.find(text)
        viewModelScope.launch { handleMatch(bp) }
    }

    private fun handleMatch(bp: Blueprint?) {
        val now = System.currentTimeMillis()
        if (bp == null) {
            if (noMatchSince == 0L) noMatchSince = now
            if (now - noMatchSince > 1500) {
                lastAddedId = -1
                candidateId = -1
                candidateCount = 0
            }
            return
        }
        noMatchSince = 0L
        if (lookupBusy || bp.id == lastAddedId) return
        if (bp.id != candidateId) {
            candidateId = bp.id
            candidateCount = 1
            return
        }
        candidateCount++
        if (candidateCount < 2) return
        addByLookup(bp)
    }

    fun addManual(bp: Blueprint) {
        if (lookupBusy) return
        addByLookup(bp)
    }

    private fun addByLookup(bp: Blueprint) {
        lookupBusy = true
        lastAddedId = bp.id
        _message.value = "Consultando precio de ${bp.displayName}…"
        viewModelScope.launch {
            try {
                val price = api.fetchPrice(bp.id)
                addToCart(bp, price)
                _message.value = if (price == null) "${bp.displayName}: sin ofertas en CardTrader" else ""
            } catch (e: Exception) {
                _message.value = "Error de precio: ${e.message}"
            } finally {
                lookupBusy = false
            }
        }
    }

    private fun addToCart(bp: Blueprint, price: PriceInfo?) {
        val current = _cart.value
        val existing = current.firstOrNull { it.blueprint.id == bp.id }
        val item = if (existing != null) existing.copy(qty = existing.qty + 1, price = price ?: existing.price)
        else CartItem(bp, price, 1)
        _cart.value = if (existing != null) current.map { if (it.blueprint.id == bp.id) item else it } else current + item
        _lastScan.value = item
    }

    fun changeQty(blueprintId: Int, delta: Int) {
        _cart.value = _cart.value.mapNotNull {
            if (it.blueprint.id != blueprintId) it
            else if (it.qty + delta <= 0) null
            else it.copy(qty = it.qty + delta)
        }
    }

    fun removeFromCart(blueprintId: Int) {
        _cart.value = _cart.value.filter { it.blueprint.id != blueprintId }
    }

    fun clearCart() {
        _cart.value = emptyList()
        _lastScan.value = null
    }

    fun commitCart() {
        val items = _cart.value
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val added = items.map {
            CollectionCard(UUID.randomUUID().toString(), it.blueprint, it.qty, it.price, now, it.price, now)
        }
        val updated = _collection.value + added
        _collection.value = updated
        store.saveCollection(updated)
        clearCart()
        _message.value = "${added.size} cartas añadidas a la colección"
    }

    fun removeFromCollection(uid: String) {
        val updated = _collection.value.filter { it.uid != uid }
        _collection.value = updated
        store.saveCollection(updated)
    }

    /** Vuelve a consultar el precio actual de todas las cartas de la colección. */
    fun refreshPrices() {
        if (_busy.value) return
        val cards = _collection.value
        if (cards.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            val cache = HashMap<Int, PriceInfo?>()
            var failed = 0
            var lastError = ""
            try {
                val ids = cards.map { it.blueprint.id }.distinct()
                ids.forEachIndexed { i, id ->
                    _message.value = "Actualizando precios ${i + 1}/${ids.size}…"
                    try {
                        cache[id] = api.fetchPrice(id)
                    } catch (e: Exception) {
                        failed++
                        lastError = e.message ?: ""
                    }
                }
                val now = System.currentTimeMillis()
                val updated = _collection.value.map { c ->
                    if (cache.containsKey(c.blueprint.id)) {
                        c.copy(currentPrice = cache[c.blueprint.id] ?: c.currentPrice, updatedDate = now)
                    } else c
                }
                _collection.value = updated
                store.saveCollection(updated)
                _message.value = if (failed == 0) "Precios actualizados" else "Actualizado con $failed errores: $lastError"
            } finally {
                _busy.value = false
            }
        }
    }

    fun search(query: String): List<Blueprint> = index.search(query)

    fun clearMessage() {
        _message.value = ""
    }
}
