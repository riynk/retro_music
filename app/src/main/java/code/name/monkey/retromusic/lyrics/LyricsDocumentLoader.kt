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
    val translations: List<String>?
)

/**
 * Resolves local lyrics once for all three lyric surfaces. TTML takes priority
 * when it parses successfully; malformed TTML falls back to LRC/embedded lyrics.
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
            val translations = if (
                preferences.getBoolean("lyrics_show_translation", false)
            ) {
                val targetLanguage = preferences.getString(
                    "lyrics_translation_language",
                    "id"
                ) ?: "id"
                val songKey = song.data.hashCode()
                val lyricsHash = document.lines.joinToString("|") { it.text }.hashCode()
                val cache = LyricsTranslationCache(appContext)

                cache.get(songKey, targetLanguage, lyricsHash)
                    ?: runCatching {
                        MlKitLyricsTranslator.translate(
                            document.lines.map { it.text },
                            targetLanguage
                        )
                    }.getOrNull()?.takeIf { it.size == document.lines.size }?.also {
                        cache.put(songKey, targetLanguage, lyricsHash, it)
                    }
            } else {
                null
            }

            LoadedLyrics(document, translations)
        }
}
