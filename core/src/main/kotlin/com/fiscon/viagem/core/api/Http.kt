package com.fiscon.viagem.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

object Http {
    /**
     * Os serviços gratuitos do OpenStreetMap exigem um User-Agent identificando a aplicação.
     * https://operations.osmfoundation.org/policies/nominatim/
     */
    const val USER_AGENT = "PlanejadorViagem-AndroidAuto/1.0 (https://github.com/fiscon421811/fiscon)"

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()
}

/** Executa a requisição com novas tentativas para erros temporários (429/5xx). */
internal suspend fun OkHttpClient.fetchString(request: Request, retries: Int = 2): String =
    withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            try {
                newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) return@withContext body
                    val retryable = response.code == 429 || response.code >= 500
                    if (!retryable || attempt >= retries) {
                        throw ApiException("HTTP ${response.code} em ${request.url.host}")
                    }
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= retries) throw ApiException("Falha de rede em ${request.url.host}: ${e.message}", e)
            }
            attempt++
            delay(1500L * attempt)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
