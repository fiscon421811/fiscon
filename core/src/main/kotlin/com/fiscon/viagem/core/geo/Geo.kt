package com.fiscon.viagem.core.geo

import com.fiscon.viagem.core.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Rumo inicial de [a] para [b], em graus (0 = norte, sentido horário). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

/** Decodifica/codifica o formato "encoded polyline" do Google (usado também pelo OSRM). */
object PolylineCodec {
    fun decode(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val result = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var shift = 0
            var acc = 0L
            var b: Int
            do {
                b = encoded[index++].code - 63
                acc = acc or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (acc and 1L != 0L) (acc shr 1).inv() else acc shr 1

            shift = 0
            acc = 0L
            do {
                b = encoded[index++].code - 63
                acc = acc or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (acc and 1L != 0L) (acc shr 1).inv() else acc shr 1

            result += GeoPoint(lat / factor, lon / factor)
        }
        return result
    }

    fun encode(points: List<GeoPoint>, precision: Int = 6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = Math.round(p.lat * factor)
            val lon = Math.round(p.lon * factor)
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20L or (v and 0x1f)) + 63).toInt().toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }
}

data class Projection(
    /** Distância, desde o início da linha, do ponto projetado. */
    val distanceAlongM: Double,
    /** Distância entre o ponto original e a linha. */
    val offsetM: Double,
    val segmentIndex: Int,
    val point: GeoPoint,
)

/**
 * Geometria de uma rota com distâncias acumuladas, permitindo projetar
 * pontos (radares, postos, posição do veículo) sobre ela.
 */
class RouteGeometry(val points: List<GeoPoint>) {
    val cumulativeM: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val lengthM: Double get() = if (cumulativeM.isEmpty()) 0.0 else cumulativeM.last()

    init {
        require(points.isNotEmpty()) { "Rota sem pontos" }
    }

    fun project(p: GeoPoint): Projection = projectRange(p, 0, points.size - 2)

    /**
     * Projeção limitada a uma janela de segmentos (útil para acompanhar a posição do
     * veículo sem varrer a rota inteira a cada atualização de GPS).
     */
    fun projectRange(p: GeoPoint, fromSegment: Int, toSegment: Int): Projection {
        if (points.size == 1) return Projection(0.0, Geo.distance(p, points[0]), 0, points[0])
        val from = fromSegment.coerceIn(0, points.size - 2)
        val to = toSegment.coerceIn(from, points.size - 2)
        var best: Projection? = null
        for (i in from..to) {
            val candidate = projectOnSegment(p, i)
            if (best == null || candidate.offsetM < best.offsetM) best = candidate
        }
        return best!!
    }

    private fun projectOnSegment(p: GeoPoint, i: Int): Projection {
        val a = points[i]
        val b = points[i + 1]
        // Projeção equirretangular local centrada em p (precisa para segmentos curtos).
        val cosLat = cos(Math.toRadians(p.lat))
        val k = Math.toRadians(1.0) * Geo.EARTH_RADIUS_M
        val ax = (a.lon - p.lon) * cosLat * k
        val ay = (a.lat - p.lat) * k
        val bx = (b.lon - p.lon) * cosLat * k
        val by = (b.lat - p.lat) * k
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val segLen = cumulativeM[i + 1] - cumulativeM[i]
        val point = GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
        return Projection(cumulativeM[i] + t * segLen, sqrt(cx * cx + cy * cy), i, point)
    }

    /** Índice do segmento que contém a distância [distanceM]. */
    fun segmentAt(distanceM: Double): Int {
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulativeM[mid] <= distanceM) lo = mid else hi = mid - 1
        }
        return lo.coerceAtMost(maxOf(0, points.size - 2))
    }

    fun pointAt(distanceM: Double): GeoPoint {
        if (points.size == 1) return points[0]
        val i = segmentAt(distanceM)
        val segLen = cumulativeM[i + 1] - cumulativeM[i]
        val t = if (segLen == 0.0) 0.0 else ((distanceM - cumulativeM[i]) / segLen).coerceIn(0.0, 1.0)
        val a = points[i]
        val b = points[i + 1]
        return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    }

    fun bearingAt(distanceM: Double): Double {
        if (points.size == 1) return 0.0
        val i = segmentAt(distanceM)
        return Geo.bearing(points[i], points[i + 1])
    }
}

/** Simplificação Douglas-Peucker (tolerância em metros), iterativa. */
object LineSimplifier {
    fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end - start < 2) continue
            val segment = RouteGeometry(listOf(points[start], points[end]))
            var maxDist = -1.0
            var index = -1
            for (i in start + 1 until end) {
                val d = segment.project(points[i]).offsetM
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (maxDist > toleranceM) {
                keep[index] = true
                stack.addLast(start to index)
                stack.addLast(index to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
