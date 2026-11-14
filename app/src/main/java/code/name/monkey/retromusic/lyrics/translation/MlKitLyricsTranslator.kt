/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Google ML Kit on-device translation. The model is downloaded when required;
 * lyric lines are then translated in small concurrent batches on this device.
 */
object MlKitLyricsTranslator {
    private const val TRANSLATION_BATCH_SIZE = 4

    suspend fun translate(lines: List<String>, targetLanguageTag: String): List<String> =
        withContext(Dispatchers.IO) {
            if (lines.isEmpty()) return@withContext emptyList()

            val joinedText = lines.asSequence()
                .filter { it.isNotBlank() }
                .take(40)
                .joinToString(separator = " ")
                .take(2_000)
            if (joinedText.isBlank()) return@withContext lines

            val identifier = LanguageIdentification.getClient()
            val sourceTag = try {
                Tasks.await(identifier.identifyLanguage(joinedText))
            } finally {
                identifier.close()
            }

            val sourceLanguage = TranslateLanguage.fromLanguageTag(sourceTag)
                ?: throw IllegalStateException("Could not identify the lyric language")
            val targetLanguage = TranslateLanguage.fromLanguageTag(targetLanguageTag)
                ?: throw IllegalArgumentException("The selected translation language is not supported")

            if (sourceLanguage == targetLanguage) return@withContext lines

            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build()
            )

            try {
                Tasks.await(
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                )

                val translated = ArrayList<String>(lines.size)
                var completedAtLeastOne = false

                lines.chunked(TRANSLATION_BATCH_SIZE).forEach { batch ->
                    currentCoroutineContext().ensureActive()

                    // Start a few independent requests together instead of awaiting
                    // every lyric line serially. Preserve failed lines as their source text.
                    val tasks = batch.map { line ->
                        if (line.isBlank()) null else translator.translate(line)
                    }
                    val pending = tasks.filterNotNull()
                    if (pending.isNotEmpty()) {
                        Tasks.await(Tasks.whenAllComplete(pending))
                    }

                    tasks.forEachIndexed { index, task ->
                        if (task == null) {
                            translated += ""
                        } else if (task.isSuccessful) {
                            completedAtLeastOne = true
                            translated += runCatching { Tasks.await(task) }
                                .getOrDefault(batch[index])
                        } else {
                            translated += batch[index]
                        }
                    }
                }

                if (!completedAtLeastOne) {
                    throw IllegalStateException("No lyric line could be translated")
                }
                translated
            } finally {
                translator.close()
            }
        }
}
