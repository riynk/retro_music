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
 * Optional on-device translation for user-provided lyrics. No lyric text is
 * sent to a third-party lyrics catalogue.
 */
object MlKitLyricsTranslator {
    suspend fun translate(lines: List<String>, targetLanguageTag: String): List<String> =
        withContext(Dispatchers.IO) {
            if (lines.isEmpty()) return@withContext emptyList()

            val joinedText = lines.joinToString(separator = " ").take(2_000)
            val identifier = LanguageIdentification.getClient()
            val sourceTag = try {
                Tasks.await(identifier.identifyLanguage(joinedText))
            } finally {
                identifier.close()
            }

            val sourceLanguage = TranslateLanguage.fromLanguageTag(sourceTag)
                ?: TranslateLanguage.ENGLISH
            val targetLanguage = TranslateLanguage.fromLanguageTag(targetLanguageTag)
                ?: TranslateLanguage.INDONESIAN

            if (sourceLanguage == targetLanguage) return@withContext lines

            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build()
            )

            try {
                Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
                lines.map { text ->
                    if (text.isBlank()) "" else Tasks.await(translator.translate(text))
                }
            } finally {
                translator.close()
            }
        }
}
