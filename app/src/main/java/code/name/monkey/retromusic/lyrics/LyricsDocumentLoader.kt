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
import java.security.MessageDigest

data class LoadedLyrics(
    val document: LyricDocument,
    val translations: List<String>?,
    val translationRequested: Boolean,
    val translationFailed: Boolean
)

/**
 * Resolves the selected local lyric source and translates only when the
 * current song's own translation switch is enabled.
 */
object LyricsDocumentLoader {
    suspend fun load(context: Context, song: Song): LoadedLyrics? =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val selectedSource = LyricsPreferences.source(appContext, song)

            fun loadTtml(): LyricDocument? = runCatching {
                LyricUtil.getTtmlLyricsFile(song)
                    ?.takeIf { it.isFile }
                    ?.readText()
                    ?.let(TtmlLyricsParser::parse)
            }.getOrNull()

            fun loadLrc(): LyricDocument? = runCatching {
                val lrcText = LyricUtil.getSyncedLyricsFile(song)
                    ?.takeIf { it.isFile }
                    ?.readText()
                    ?: LyricUtil.getEmbeddedSyncedLyrics(song.data)
                lrcText?.let(LrcLyricsParser::parse)
            }.getOrNull()

            val document = when (selectedSource) {
                LyricsSource.TTML -> loadTtml()
                LyricsSource.LRC -> loadLrc()
                LyricsSource.AUTOMATIC -> loadTtml() ?: loadLrc()
            } ?: return@withContext null

            val translationRequested =
                LyricsPreferences.isTranslationEnabled(appContext, song)
            if (!translationRequested) {
                return@withContext LoadedLyrics(
                    document = document,
                    translations = null,
                    translationRequested = false,
                    translationFailed = false
                )
            }

            val targetLanguage = PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString("lyrics_translation_language", "id") ?: "id"

            val songKey = LyricsPreferences.cacheSongKey(song)
            val lyricsHash = MessageDigest.getInstance("SHA-256")
                .digest(document.lines.joinToString("\u001F") { it.text }.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val cache = LyricsTranslationCache(appContext)

            val cached = cache.get(songKey, targetLanguage, lyricsHash)
            if (cached != null && cached.size == document.lines.size) {
                return@withContext LoadedLyrics(document, cached, true, false)
            }

            val translated = runCatching {
                MlKitLyricsTranslator.translate(document.lines.map { it.text }, targetLanguage)
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
