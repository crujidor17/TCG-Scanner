package com.hugo.tcgscanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val api = CardTraderApi { store.token }

    private val _token = MutableStateFlow(store.token)
    val token: StateFlow<String> = _token

    private val _catalog = MutableStateFlow(store.loadCatalog())
    val catalog: StateFlow<List<Blueprint>> = _catalog

    private val _cart = MutableStateFlow<List<CartItem>>(emptyList())
    val cart: StateFlow<List<CartItem>> = _cart

    // Al cargar, une entradas repetidas que pudieran existir de versiones anteriores.
    private val _collection = MutableStateFlow(store.loadCollection().let { raw ->
        mergeDuplicates(raw).also { if (it.size != raw.size) store.saveCollection(it) }
    })
    val collection: StateFlow<List<CollectionCard>> = _collection

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _lastScan = MutableStateFlow<CartItem?>(null)
    val lastScan: StateFlow<CartItem?> = _lastScan

    private val _importReport = MutableStateFlow<ImportReport?>(null)
    val importReport: StateFlow<ImportReport?> = _importReport

    private val _translateOn = MutableStateFlow(store.translateOn)
    val translateOn: StateFlow<Boolean> = _translateOn

    @Volatile
    private var index = CardIndex(_catalog.value)

    // Traducción (francés / chino / coreano -> inglés) cuando el nombre no se reconoce.
    private val translator = CardTranslator()
    private val translating = AtomicBoolean(false)

    @Volatile
    private var translateRetryAt = 0L
    private var lastTranslated = ""
    private var latinMisses = 0

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

    fun setTranslateOn(v: Boolean) {
        store.translateOn = v
        _translateOn.value = v
    }

    fun fallbackEnabled(): Boolean = _translateOn.value

    fun dismissImportReport() {
        _importReport.value = null
    }

    /**
     * Lo llama el analizador de cámara (hilo de fondo) con el texto reconocido en cada fotograma.
     * Orden: nombre en el catálogo -> número impreso (no depende del idioma) -> traducción.
     * Devuelve true si el texto ya ha identificado una carta.
     */
    fun onFrameText(text: String, script: OcrScript = OcrScript.LATIN): Boolean {
        val bp = (if (script == OcrScript.LATIN) index.find(text) else null) ?: index.findByNumber(text)
        if (bp != null) {
            latinMisses = 0
            viewModelScope.launch { handleMatch(bp) }
            return true
        }
        viewModelScope.launch { handleMatch(null) }
        if (script == OcrScript.LATIN) latinMisses++
        if (_translateOn.value && text.isNotBlank() && (script != OcrScript.LATIN || latinMisses >= 2)) {
            translateAndMatch(text, script)
        }
        return false
    }

    private fun candidateLines(text: String): List<String> =
        text.lines().map { it.trim() }
            .filter { it.length in 2..40 && it.count { ch -> ch.isLetter() } >= 2 }
            .take(6)

    private fun translateAndMatch(text: String, script: OcrScript) {
        if (System.currentTimeMillis() < translateRetryAt) return
        val lines = candidateLines(text)
        if (lines.isEmpty()) return
        val key = script.name + "|" + lines.joinToString("|")
        if (key == lastTranslated) return
        if (!translating.compareAndSet(false, true)) return
        lastTranslated = key
        viewModelScope.launch {
            try {
                val lang = script.lang
                if (!translator.isReady(lang)) {
                    _message.value = "Preparando el traductor (la primera vez descarga unos 30 MB)…"
                    translator.prepare(lang)
                    _message.value = ""
                }
                val translated = lines.map { translator.toEnglish(lang, it) }
                handleMatch(index.find(translated.joinToString("\n")))
            } catch (e: Exception) {
                translateRetryAt = System.currentTimeMillis() + 30_000
                _message.value = "Traductor no disponible: ${e.message}"
            } finally {
                translating.set(false)
            }
        }
    }

    override fun onCleared() {
        translator.close()
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

    /** Añade cartas a la colección: si ya existe la carta, suma unidades; si no, crea la entrada. */
    private fun mergeIntoCollection(items: List<CartItem>): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        val updated = _collection.value.toMutableList()
        var created = 0
        var merged = 0
        for (item in items) {
            val idx = updated.indexOfFirst { it.blueprint.id == item.blueprint.id }
            if (idx >= 0) {
                // Ya la tenías: se suman unidades en la misma entrada (conserva el precio del primer escaneo).
                updated[idx] = updated[idx].absorb(item.qty, item.price, now)
                merged++
            } else {
                updated.add(
                    CollectionCard(UUID.randomUUID().toString(), item.blueprint, item.qty, item.price, now, item.price, now)
                )
                created++
            }
        }
        _collection.value = updated
        store.saveCollection(updated)
        return created to merged
    }

    fun commitCart() {
        val items = _cart.value
        if (items.isEmpty()) return
        val (created, merged) = mergeIntoCollection(items)
        clearCart()
        _message.value = when {
            merged == 0 -> "$created cartas añadidas a la colección"
            created == 0 -> "$merged cartas ya estaban: cantidad sumada"
            else -> "$created nuevas y $merged ya existentes (cantidad sumada)"
        }
    }

    /** Escribe la colección en el archivo .txt elegido por el usuario. */
    fun exportTo(uri: Uri, withPrice: Boolean) {
        val cards = _collection.value
        if (cards.isEmpty()) return
        val text = buildExportText(cards, withPrice)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("No se pudo abrir el archivo")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                _message.value = "Exportadas ${cards.size} cartas (${cards.sumOf { it.qty }} copias)"
            } catch (e: Exception) {
                _message.value = "Error al exportar: ${e.message}"
            }
        }
    }

    /** Importa un .txt con líneas "carta X copias": busca el precio de cada carta y la añade a la colección. */
    fun importFrom(uri: Uri) {
        if (_busy.value) return
        if (store.token.isBlank()) {
            _message.value = "Falta el token de CardTrader (Ajustes)"
            return
        }
        if (_catalog.value.isEmpty()) {
            _message.value = "Primero descarga el catálogo en Ajustes"
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
                        ?: throw IOException("No se pudo abrir el archivo")
                }
                val (lines, invalid) = parseImportLines(raw)
                if (lines.isEmpty()) {
                    _message.value = "El archivo no tiene líneas con el formato «carta X copias»"
                    return@launch
                }
                val notFound = ArrayList<String>(invalid)
                val prices = HashMap<Int, PriceInfo?>()
                var ambiguous = 0
                var noPrice = 0
                var cards = 0
                var copies = 0
                for ((i, line) in lines.withIndex()) {
                    val match = index.findByLabel(line.name)
                    if (match == null) {
                        notFound.add(line.raw)
                        continue
                    }
                    if (match.ambiguous) ambiguous++
                    val bp = match.blueprint
                    _message.value = "Importando ${i + 1}/${lines.size}: ${bp.displayName}"
                    val price = if (prices.containsKey(bp.id)) {
                        prices[bp.id]
                    } else {
                        val p = try {
                            api.fetchPrice(bp.id)
                        } catch (e: Exception) {
                            null
                        }
                        prices[bp.id] = p
                        p
                    }
                    if (price == null) noPrice++
                    mergeIntoCollection(listOf(CartItem(bp, price, line.qty)))
                    cards++
                    copies += line.qty
                }
                _message.value = ""
                _importReport.value = ImportReport(cards, copies, notFound, ambiguous, noPrice)
            } catch (e: Exception) {
                _message.value = "Error al importar: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Suma o resta unidades a una carta de la colección (mínimo 1; para quitarla usa removeFromCollection). */
    fun changeCollectionQty(uid: String, delta: Int) {
        val updated = _collection.value.map {
            if (it.uid == uid) it.copy(qty = (it.qty + delta).coerceIn(1, MAX_QTY)) else it
        }
        _collection.value = updated
        store.saveCollection(updated)
    }

    /** Fija la cantidad exacta de una carta de la colección. */
    fun setCollectionQty(uid: String, qty: Int) {
        if (qty < 1) return
        val updated = _collection.value.map {
            if (it.uid == uid) it.copy(qty = qty.coerceAtMost(MAX_QTY)) else it
        }
        _collection.value = updated
        store.saveCollection(updated)
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
