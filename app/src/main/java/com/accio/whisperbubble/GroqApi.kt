package com.accio.whisperbubble

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Groq (api.groq.com) - OpenAI compatible. Model is fixed to whisper-large-v3,
 * not the turbo variant.
 */
object GroqApi {

    private const val BASE = "https://api.groq.com/openai/v1"
    private const val TRANSCRIBE = "$BASE/audio/transcriptions"
    private const val MODELS = "$BASE/models"
    private const val MODEL = "whisper-large-v3"

    data class Outcome(
        val text: String? = null,
        val error: String? = null,
        val retryable: Boolean = false
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Resolves DNS and completes the TLS handshake ahead of the first upload so
     * the user does not pay for it after every release of the bubble.
     */
    fun warmup() {
        Thread {
            runCatching {
                val req = Request.Builder().url("https://api.groq.com/").head().build()
                client.newCall(req).execute().close()
            }
        }.apply { isDaemon = true }.start()
    }

    /** Checks a key against the models endpoint. error == null means usable. */
    suspend fun validate(key: String): Outcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(MODELS)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Outcome(text = "ok")
                } else {
                    Outcome(error = describe(response.code, response.body?.string().orEmpty()))
                }
            }
        } catch (t: Throwable) {
            Outcome(error = t.message ?: "Network error")
        }
    }

    suspend fun transcribe(wav: ByteArray): Outcome = withContext(Dispatchers.IO) {
        var last = Outcome(error = "No API key")
        var attempt = 0
        while (attempt < 3) {
            val key = KeyManager.current() ?: return@withContext Outcome(error = "No API key")
            val outcome = call(key, wav)
            if (outcome.text != null) {
                KeyManager.onSuccess()
                return@withContext outcome
            }
            last = outcome
            if (!outcome.retryable) return@withContext outcome
            KeyManager.onFailure()
            attempt++
            if (attempt < 3) runCatching { Thread.sleep(150) }
        }
        last
    }

    private fun call(key: String, wav: ByteArray): Outcome {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")

        val language = Prefs.language
        if (language.isNotBlank()) form.addFormDataPart("language", language)

        val request = Request.Builder()
            .url(TRANSCRIBE)
            .addHeader("Authorization", "Bearer $key")
            .post(form.build())
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val text = runCatching { JSONObject(body).optString("text", "") }
                        .getOrDefault("")
                        .trim()
                    if (text.isEmpty()) {
                        Outcome(error = "Nothing recognised", retryable = false)
                    } else {
                        Outcome(text = text)
                    }
                } else {
                    Outcome(error = describe(response.code, body), retryable = response.code in RETRYABLE)
                }
            }
        } catch (t: Throwable) {
            Outcome(error = t.message ?: "Network error", retryable = true)
        }
    }

    private val RETRYABLE = setOf(401, 403, 408, 429, 500, 502, 503, 504)

    private fun describe(code: Int, body: String): String {
        val message = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()

        return when {
            !message.isNullOrBlank() -> message.take(120)
            code == 401 || code == 403 -> "Key rejected"
            code == 429 -> "Rate limited"
            code == 413 -> "Audio too long"
            else -> "HTTP $code"
        }
    }
}
