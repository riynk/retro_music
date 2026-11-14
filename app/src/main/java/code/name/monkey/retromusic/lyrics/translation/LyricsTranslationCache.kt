/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.translation

import android.content.Context
import org.json.JSONArray

class LyricsTranslationCache(context: Context) {
    private val preferences = context.getSharedPreferences("lyrics_translation_cache", Context.MODE_PRIVATE)

    fun get(songKey: Int, targetLanguage: String, lyricsHash: Int): List<String>? {
        val raw = preferences.getString(cacheKey(songKey, targetLanguage, lyricsHash), null) ?: return null
        return try {
            val json = JSONArray(raw)
            List(json.length()) { index -> json.optString(index) }
        } catch (_: Exception) {
            null
        }
    }

    fun put(songKey: Int, targetLanguage: String, lyricsHash: Int, translations: List<String>) {
        val json = JSONArray()
        translations.forEach { json.put(it) }
        preferences.edit()
            .putString(cacheKey(songKey, targetLanguage, lyricsHash), json.toString())
            .apply()
    }

    private fun cacheKey(songKey: Int, targetLanguage: String, lyricsHash: Int): String =
        "song_" + songKey + "_" + targetLanguage + "_" + lyricsHash
}
