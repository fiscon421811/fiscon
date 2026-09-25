package com.fiscon.viagem.core.api

import com.fiscon.viagem.core.geo.LineSimplifier
import com.fiscon.viagem.core.model.GeoPoint
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Elemento bruto do OpenStreetMap (antes de ser projetado sobre a rota). */
data class OsmPoi(
    val id: Long,
    val location: GeoPoint,
    val tags: Map<String, String>,
)

data class OsmPois(val speedCameras: List<OsmPoi>, val fuelStations: List<OsmPoi>)

/**
 * Busca radares (highway=speed_camera) e postos (amenity=fuel) ao longo da rota
 * usando a Overpass API (gratuita). A rota é simplificada e dividida em trechos
 * para não estourar os limites do servidor em viagens longas.
 */
class OverpassClient(
    private val client: OkHttpClient,
    private val endpoints: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    ),
    private val pointsPerChunk: Int = 120,
) {
    @Serializable
    private data class Response(val elements: List<Element> = emptyList())

    @Serializable
    private data class Center(val lat: Double, val lon: Double)

    @Serializable
    private data class Element(
        val type: String,
        val id: Long,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: Center? = null,
        val tags: Map<String, String> = emptyMap(),
    )

    suspend fun poisAlongRoute(
        route: List<GeoPoint>,
        radarRadiusM: Int = 80,
        fuelRadiusM: Int = 1500,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OsmPois {
        // Tolerância menor que o raio dos radares para não "perder" a pista.
        val simplified = LineSimplifier.simplify(route, toleranceM = radarRadiusM / 2.0)
        val chunks = chunk(simplified)
        val cameras = LinkedHashMap<Long, OsmPoi>()
        val stations = LinkedHashMap<Long, OsmPoi>()
        chunks.forEachIndexed { index, chunk ->
            onProgress(index, chunks.size)
            val query = buildQuery(chunk, radarRadiusM, fuelRadiusM)
            parse(fetch(query)).forEach { poi ->
                val isStation = poi.tags["amenity"] == "fuel"
                // Chave única entre nós e ways (ids podem colidir entre tipos).
                if (isStation) stations[poi.id] = poi else cameras[poi.id] = poi
            }
        }
        onProgress(chunks.size, chunks.size)
        return OsmPois(cameras.values.toList(), stations.values.toList())
    }

    internal fun chunk(points: List<GeoPoint>): List<List<GeoPoint>> {
        if (points.size <= pointsPerChunk) return listOf(points)
        val result = mutableListOf<List<GeoPoint>>()
        var start = 0
        while (start < points.size - 1) {
            val end = minOf(start + pointsPerChunk, points.size)
            result += points.subList(start, end)
            start = end - 1 // sobreposição de 1 ponto mantém a linha contínua
        }
        return result
    }

    internal fun buildQuery(points: List<GeoPoint>, radarRadiusM: Int, fuelRadiusM: Int): String {
        val line = if (points.size == 1) {
            val p = points[0]
            "%.6f,%.6f".format(java.util.Locale.US, p.lat, p.lon)
        } else {
            points.joinToString(",") { "%.6f,%.6f".format(java.util.Locale.US, it.lat, it.lon) }
        }
        return """
            [out:json][timeout:90];
            (
              node["highway"="speed_camera"](around:$radarRadiusM,$line);
              node["amenity"="fuel"](around:$fuelRadiusM,$line);
              way["amenity"="fuel"](around:$fuelRadiusM,$line);
            );
            out center tags;
        """.trimIndent()
    }

    private suspend fun fetch(query: String): String {
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(FormBody.Builder().add("data", query).build())
                    .build()
                return client.fetchString(request)
            } catch (e: ApiException) {
                lastError = e
            }
        }
        throw ApiException("Overpass indisponível: ${lastError?.message}", lastError)
    }

    internal fun parse(body: String): List<OsmPoi> =
        json.decodeFromString(Response.serializer(), body).elements.mapNotNull { e ->
            val lat = e.lat ?: e.center?.lat ?: return@mapNotNull null
            val lon = e.lon ?: e.center?.lon ?: return@mapNotNull null
            // ways recebem id negativo para não colidirem com nós.
            val id = if (e.type == "way") -e.id else e.id
            OsmPoi(id, GeoPoint(lat, lon), e.tags)
        }
}
