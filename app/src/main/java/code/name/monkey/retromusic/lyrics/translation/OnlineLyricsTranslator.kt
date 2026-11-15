/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.translation

import androidx.core.text.HtmlCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Online lyric translation through the documented MyMemory REST API.
 *
 * Language identification remains local and lightweight; translated text is
 * sent over HTTPS. Requests run concurrently and are cached by the caller.
 */
object OnlineLyricsTranslator {
    private const val ENDPOINT = "https://api.mymemory.translated.net/get"
    private const val MAX_QUERY_BYTES = 450
    private const val MAX_PARALLEL_REQUESTS = 6

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun translate(
        lines: List<String>,
        targetLanguageTag: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()

        val sourceText = lines.asSequence()
            .filter { it.isNotBlank() }
            .take(40)
            .joinToString(separator = " ")
            .take(2_000)
        if (sourceText.isBlank()) return@withContext lines

        val sourceLanguage = identifySourceLanguage(sourceText)
        val targetLanguage = normalizeLanguageCode(targetLanguageTag)
        if (sourceLanguage == targetLanguage) return@withContext lines

        // Chorus repetitions are common in lyrics; translate each unique line
        // only once, then restore the original order and duplicate lines.
        val uniqueLines = lines.filter { it.isNotBlank() }.distinct()
        val semaphore = Semaphore(MAX_PARALLEL_REQUESTS)
        val results = coroutineScope {
            uniqueLines.map { line ->
                async {
                    semaphore.withPermit {
                        try {
                            line to translateLongLine(line, sourceLanguage, targetLanguage)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            line to null
                        }
                    }
                }
            }.awaitAll()
        }

        currentCoroutineContext().ensureActive()
        val translatedByLine = results.mapNotNull { (source, translated) ->
            translated?.let { source to it }
        }.toMap()

        if (translatedByLine.isEmpty()) {
            throw IOException("The online translation service returned no translated lines")
        }

        lines.map { line ->
            if (line.isBlank()) "" else translatedByLine[line] ?: line
        }
    }

    private fun identifySourceLanguage(text: String): String {
        val identifier = LanguageIdentification.getClient()
        val detected = try {
            Tasks.await(identifier.identifyLanguage(text))
        } finally {
            identifier.close()
        }
        return normalizeLanguageCode(detected)
    }

    private fun normalizeLanguageCode(languageTag: String): String {
        val normalized = languageTag.trim().replace('_', '-')
        val lower = normalized.lowercase()
        return when {
            lower == "und" || lower == "unknown" || lower.isBlank() ->
                throw IOException("Could not identify the lyric language")
            lower.startsWith("zh-hant") || lower.startsWith("zh-tw") -> "zh-TW"
            lower.startsWith("zh") -> "zh-CN"
            else -> lower.substringBefore('-')
        }
    }

    private suspend fun translateLongLine(
        line: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String {
        val chunks = splitByUtf8Bytes(line, MAX_QUERY_BYTES)
        if (chunks.size == 1) {
            return requestTranslation(chunks.first(), sourceLanguage, targetLanguage)
        }

        val translated = StringBuilder()
        chunks.forEach { chunk ->
            currentCoroutineContext().ensureActive()
            translated.append(requestTranslation(chunk, sourceLanguage, targetLanguage))
        }
        return translated.toString()
    }

    /**
     * MyMemory documents a 500-byte maximum per q parameter. Split only at
     * Unicode code-point boundaries so non-Latin lyrics are never corrupted.
     */
    private fun splitByUtf8Bytes(text: String, maxBytes: Int): List<String> {
        val chunks = mutableListOf<String>()
        var chunkStart = 0
        var index = 0
        var byteCount = 0

        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val nextIndex = index + Character.charCount(codePoint)
            val codePointBytes = text.substring(index, nextIndex)
                .toByteArray(Charsets.UTF_8).size

            if (byteCount + codePointBytes > maxBytes && index > chunkStart) {
                chunks += text.substring(chunkStart, index)
                chunkStart = index
                byteCount = 0
            }
            byteCount += codePointBytes
            index = nextIndex
        }

        if (chunkStart < text.length) chunks += text.substring(chunkStart)
        return chunks
    }

    private suspend fun requestTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String = suspendCancellableCoroutine { continuation ->
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("langpair", "$sourceLanguage|$targetLanguage")
            .addQueryParameter("mt", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "RetroMusic/7.0.3")
            .get()
            .build()
        val call = client.newCall(request)

        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Online translation HTTP error " + response.code)
                        }
                        val body = response.body?.string()
                            ?: throw IOException("Empty response from online translator")
                        val payload = JSONObject(body)
                        val status = payload.optInt("responseStatus", response.code)
                        val details = payload.optString("responseDetails")
                        if (status != 200) {
                            throw IOException(
                                details.takeIf { it.isNotBlank() }
                                    ?: "Online translation service rejected the request"
                            )
                        }

                        val rawText = payload.optJSONObject("responseData")
                            ?.optString("translatedText")
                            .orEmpty()
                        val translated = HtmlCompat.fromHtml(
                            rawText,
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        ).toString().trim()

                        val upper = translated.uppercase()
                        if (translated.isBlank() ||
                            upper.contains("INVALID SOURCE LANGUAGE") ||
                            upper.contains("PLEASE SELECT TWO DISTINCT LANGUAGES") ||
                            upper.contains("QUOTA EXCEEDED")
                        ) {
                            throw IOException(
                                details.takeIf { it.isNotBlank() }
                                    ?: "Invalid or unavailable translation result"
                            )
                        }
                        translated
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                    return
                }

                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(result))
                }
            }
        })
    }
}
