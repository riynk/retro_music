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
import kotlinx.coroutines.withContext

/**
 * Google ML Kit on-device translation. Google downloads the language model
 * when required; the actual lyric lines are then translated locally.
 */
object MlKitLyricsTranslator {
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
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).let {
                    Tasks.await(it)
                }
                lines.map { line ->
                    if (line.isBlank()) "" else Tasks.await(translator.translate(line))
                }
            } finally {
                translator.close()
            }
        }
}
