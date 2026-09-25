package com.fiscon.viagem.core.api

import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.Place
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Geocodificação gratuita pelo Nominatim (OpenStreetMap).
 * Política de uso: no máximo 1 requisição por segundo e User-Agent identificado.
 */
class NominatimClient(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
    private val countryCodes: String? = "br",
) {
    @Serializable
    private data class Result(
        val lat: String,
        val lon: String,
        val name: String? = null,
        val display_name: String = "",
    )

    suspend fun search(query: String, limit: Int = 6): List<Place> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("accept-language", "pt-BR")
            .apply { countryCodes?.let { addQueryParameter("countrycodes", it) } }
            .build()
        val body = client.fetchString(Request.Builder().url(url).build())
        return json.decodeFromString(ListSerializer(Result.serializer()), body).map { r ->
            val title = r.name?.takeIf { it.isNotBlank() } ?: r.display_name.substringBefore(',')
            Place(title, r.display_name, GeoPoint(r.lat.toDouble(), r.lon.toDouble()))
        }
    }

    suspend fun reverse(point: GeoPoint): Place {
        val url = "$baseUrl/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", point.lat.toString())
            .addQueryParameter("lon", point.lon.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("accept-language", "pt-BR")
            .build()
        return runCatching {
            val r = json.decodeFromString(Result.serializer(), client.fetchString(Request.Builder().url(url).build()))
            val title = r.name?.takeIf { it.isNotBlank() } ?: r.display_name.substringBefore(',')
            Place(title, r.display_name, point)
        }.getOrElse { Place("Minha localização", "", point) }
    }
}
