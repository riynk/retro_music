/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Offline lyric translation using ML Kit's dynamically downloaded language
 * models. The model is fetched once for a language pair and then used locally.
 *
 * To reduce latency, lyric lines are translated together in multi-line batches.
 * If ML Kit changes line breaks in its output, the affected batch falls back to
 * independent line requests so LRC/TTML line alignment is never guessed.
 */
object OnDeviceLyricsTranslator {
    private const val MAX_BATCH_CHARACTERS = 1_800
    private const val MAX_BATCH_LINES = 24
    private const val FALLBACK_PARALLELISM = 4

    suspend fun translate(
        lines: List<String>,
        targetLanguageTag: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()

        val sourceSample = lines.asSequence()
            .filter { it.isNotBlank() }
            .take(40)
            .joinToString(separator = " ")
            .take(2_000)
        if (sourceSample.isBlank()) return@withContext lines

        val sourceTag = identifySourceLanguage(sourceSample)
        val sourceLanguage = TranslateLanguage.fromLanguageTag(sourceTag)
            ?: throw IOException("The detected lyric language is not supported")

        val targetLanguage = TranslateLanguage.fromLanguageTag(
            targetLanguageTag.trim().replace('_', '-').substringBefore('-')
        ) ?: throw IOException("The selected translation language is not supported")

        if (sourceLanguage == targetLanguage) return@withContext lines

        val uniqueLines = lines.filter { it.isNotBlank() }.distinct()
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
        )

        try {
            // First use downloads the language model. Later translations run offline.
            Tasks.await(
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            )

            val translatedByLine = LinkedHashMap<String, String>()
            for (batch in createBatches(uniqueLines)) {
                currentCoroutineContext().ensureActive()

                val batchResult = try {
                    translateBatch(translator, batch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }

                if (batchResult != null) {
                    batch.forEachIndexed { index, source ->
                        translatedByLine[source] = batchResult[index]
                    }
                } else {
                    // Newlines are not preserved by every translation output.
                    // Fall back rather than assigning a translated line to the
                    // wrong timestamp in LRC/TTML.
                    translatedByLine.putAll(
                        translateIndividually(translator, batch)
                    )
                }
            }

            if (translatedByLine.size != uniqueLines.size) {
                throw IOException("The offline translator returned incomplete lyrics")
            }

            lines.map { line ->
                if (line.isBlank()) "" else translatedByLine[line]
                    ?: throw IOException("A lyric line was not translated")
            }
        } finally {
            translator.close()
        }
    }

    private fun identifySourceLanguage(text: String): String {
        val identifier = LanguageIdentification.getClient()
        return try {
            Tasks.await(identifier.identifyLanguage(text))
                .takeIf { it.isNotBlank() && it != "und" }
                ?: throw IOException("Could not identify the lyric language")
        } finally {
            identifier.close()
        }
    }

    private fun createBatches(lines: List<String>): List<List<String>> {
        if (lines.isEmpty()) return emptyList()

        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentCharacters = 0

        for (line in lines) {
            val requiredCharacters = line.length + if (current.isEmpty()) 0 else 1
            if (current.isNotEmpty() &&
                (current.size >= MAX_BATCH_LINES ||
                    currentCharacters + requiredCharacters > MAX_BATCH_CHARACTERS)
            ) {
                batches += current
                current = mutableListOf()
                currentCharacters = 0
            }

            current += line
            currentCharacters += line.length + if (current.size == 1) 0 else 1
        }

        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun translateBatch(
        translator: Translator,
        batch: List<String>
    ): List<String>? {
        if (batch.isEmpty()) return emptyList()

        // ML Kit may remove newline characters. Use code-like markers so each
        // translated segment can be mapped back to its original lyric line.
        // If a marker is changed or dropped, return null and use line fallback.
        val markers = batch.indices.map(::lineMarker)
        val markedText = batch.indices.joinToString(separator = " ") { index ->
            "${batch[index]} ${markers[index]}"
        }
        val translated = Tasks.await(translator.translate(markedText)).trim()

        val translatedLines = ArrayList<String>(batch.size)
        var cursor = 0
        markers.forEach { marker ->
            val markerPosition = translated.indexOf(marker, cursor)
            if (markerPosition < 0) return null

            val translatedLine = translated.substring(cursor, markerPosition).trim()
            if (translatedLine.isBlank()) return null

            translatedLines += translatedLine
            cursor = markerPosition + marker.length
        }

        val trailingText = translated.substring(cursor).trim()
        if (trailingText.any { it.isLetterOrDigit() }) return null
        return translatedLines
    }

    private fun lineMarker(index: Int): String =
        "ZXQ${index.toString().padStart(4, '0')}QXZ"

    private suspend fun translateIndividually(
        translator: Translator,
        batch: List<String>
    ): Map<String, String> = coroutineScope {
        batch.chunked(FALLBACK_PARALLELISM).flatMap { group ->
            group.map { source ->
                async(Dispatchers.IO) {
                    source to Tasks.await(translator.translate(source)).trim()
                }
            }.awaitAll()
        }.toMap()
    }
}
