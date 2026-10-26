package com.hugo.tcgscanner

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor

/**
 * Lee cada fotograma con el reconocedor latino. Si dos lecturas seguidas no dan ninguna carta,
 * prueba además con el reconocedor chino y el coreano (alternándolos) sobre el mismo fotograma.
 * [onText] devuelve true si el texto ya ha identificado una carta.
 */
class OcrAnalyzer(
    private val executor: Executor,
    private val fallbackEnabled: () -> Boolean,
    private val onText: (String, OcrScript) -> Boolean
) : ImageAnalysis.Analyzer {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var chinese: TextRecognizer? = null
    private var korean: TextRecognizer? = null
    private var last = 0L
    private var misses = 0
    private var round = 0

    private fun chineseRec(): TextRecognizer =
        chinese ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also { chinese = it }

    private fun koreanRec(): TextRecognizer =
        korean ?: TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()).also { korean = it }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val now = SystemClock.elapsedRealtime()
        if (media == null || now - last < 700) {
            proxy.close()
            return
        }
        last = now
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        var closed = false
        fun done() {
            if (!closed) {
                closed = true
                proxy.close()
            }
        }

        latin.process(image)
            .addOnSuccessListener(executor) { result ->
                var chained = false
                try {
                    val text = result.text
                    if (onText(text, OcrScript.LATIN)) {
                        misses = 0
                    } else {
                        misses = if (text.isBlank()) 0 else misses + 1
                        if (misses >= 2 && fallbackEnabled()) {
                            val useChinese = round++ % 2 == 0
                            val rec = if (useChinese) chineseRec() else koreanRec()
                            val script = if (useChinese) OcrScript.CHINESE else OcrScript.KOREAN
                            chained = true
                            rec.process(image)
                                .addOnSuccessListener(executor) { r2 -> onText(r2.text, script) }
                                .addOnCompleteListener(executor) { done() }
                        }
                    }
                } catch (e: Exception) {
                    // se cierra el fotograma abajo
                }
                if (!chained) done()
            }
            .addOnFailureListener(executor) { done() }
    }

    fun close() {
        latin.close()
        chinese?.close()
        korean?.close()
    }
}
