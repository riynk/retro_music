/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics

import android.content.Context
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.lyrics.model.LyricDocument
import code.name.monkey.retromusic.lyrics.parser.LrcLyricsParser
import code.name.monkey.retromusic.lyrics.parser.TtmlLyricsParser
import code.name.monkey.retromusic.lyrics.translation.LyricsTranslationCache
import code.name.monkey.retromusic.lyrics.translation.MlKitLyricsTranslator
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.LyricUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LoadedLyrics(
    val document: LyricDocument,
    val translations: List<String>?,
    val translationRequested: Boolean,
    val translationFailed: Boolean
)

/**
 * Resolves local lyrics once for all lyric surfaces. Translation is performed
 * per complete lyric line and the result is paired back to the same timestamped
 * line index.
 */
object LyricsDocumentLoader {
    suspend fun load(context: Context, song: Song): LoadedLyrics? =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val ttmlDocument = runCatching {
                LyricUtil.getTtmlLyricsFile(song)
                    ?.takeIf { it.isFile }
                    ?.readText()
                    ?.let(TtmlLyricsParser::parse)
            }.getOrNull()

            val document = ttmlDocument ?: runCatching {
                val lrcText = LyricUtil.getSyncedLyricsFile(song)
                    ?.takeIf { it.isFile }
                    ?.readText()
                    ?: LyricUtil.getEmbeddedSyncedLyrics(song.data)
                lrcText?.let(LrcLyricsParser::parse)
            }.getOrNull() ?: return@withContext null

            val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
            val translationRequested = preferences.getBoolean("lyrics_show_translation", false)
            if (!translationRequested) {
                return@withContext LoadedLyrics(
                    document = document,
                    translations = null,
                    translationRequested = false,
                    translationFailed = false
                )
            }

            val targetLanguage = preferences.getString(
                "lyrics_translation_language",
                "id"
            ) ?: "id"
            val songKey = song.data.hashCode()
            val lyricsHash = document.lines.joinToString("|") { it.text }.hashCode()
            val cache = LyricsTranslationCache(appContext)

            val cached = cache.get(songKey, targetLanguage, lyricsHash)
            if (cached != null && cached.size == document.lines.size) {
                return@withContext LoadedLyrics(document, cached, true, false)
            }

            val translated = runCatching {
                MlKitLyricsTranslator.translate(
                    document.lines.map { it.text },
                    targetLanguage
                )
            }.getOrNull()?.takeIf { it.size == document.lines.size }

            if (translated != null) {
                cache.put(songKey, targetLanguage, lyricsHash, translated)
            }

            LoadedLyrics(
                document = document,
                translations = translated,
                translationRequested = true,
                translationFailed = translated == null
            )
        }
}
