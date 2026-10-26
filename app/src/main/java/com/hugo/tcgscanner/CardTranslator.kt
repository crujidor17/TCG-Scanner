package com.hugo.tcgscanner

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Alfabeto con el que se ha leído el texto de la carta y su idioma de origen para traducir. */
enum class OcrScript(val lang: String) {
    LATIN(TranslateLanguage.FRENCH),
    CHINESE(TranslateLanguage.CHINESE),
    KOREAN(TranslateLanguage.KOREAN)
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** Traductor sin conexión (ML Kit) de francés / chino / coreano a inglés. */
class CardTranslator {
    private val clients = HashMap<String, Translator>()
    private val ready = HashSet<String>()

    fun isReady(lang: String): Boolean = lang in ready

    private fun client(lang: String): Translator = clients.getOrPut(lang) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(lang)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
    }

    /** Descarga el modelo del idioma si hace falta (unos 30 MB la primera vez). */
    suspend fun prepare(lang: String) {
        if (lang in ready) return
        client(lang).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        ready.add(lang)
    }

    suspend fun toEnglish(lang: String, text: String): String = client(lang).translate(text).await()

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
        ready.clear()
    }
}
