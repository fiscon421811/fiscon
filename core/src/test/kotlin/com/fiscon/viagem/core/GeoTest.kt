package com.fiscon.viagem.core

import com.fiscon.viagem.core.geo.Geo
import com.fiscon.viagem.core.geo.LineSimplifier
import com.fiscon.viagem.core.geo.PolylineCodec
import com.fiscon.viagem.core.geo.RouteGeometry
import com.fiscon.viagem.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun `distancia entre Sao Paulo e Rio`() {
        val sp = GeoPoint(-23.5505, -46.6333)
        val rj = GeoPoint(-22.9068, -43.1729)
        assertEquals(360_700.0, Geo.distance(sp, rj), 2_000.0)
    }

    @Test
    fun `decodifica polyline do exemplo do Google`() {
        val points = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-6)
        assertEquals(-120.2, points[0].lon, 1e-6)
        assertEquals(43.252, points[2].lat, 1e-6)
        assertEquals(-126.453, points[2].lon, 1e-6)
    }

    @Test
    fun `encode e decode sao inversos`() {
        val pts = listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.5612, -46.6401), GeoPoint(-22.9, -43.17))
        val decoded = PolylineCodec.decode(PolylineCodec.encode(pts, 6), 6)
        pts.zip(decoded).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-6)
            assertEquals(a.lon, b.lon, 1e-6)
        }
    }

    @Test
    fun `projeta ponto ao lado da rota`() {
        val geometry = RouteGeometry(TestData.straightLine(10))
        assertEquals(10_000.0, geometry.lengthM, 20.0)
        // 5 km adiante, ~100 m ao norte da pista.
        val proj = geometry.project(GeoPoint(0.0009, 5 * 0.0089932))
        assertEquals(5_000.0, proj.distanceAlongM, 10.0)
        assertEquals(100.0, proj.offsetM, 2.0)
        assertEquals(90.0, geometry.bearingAt(2_000.0), 0.5)
    }

    @Test
    fun `simplificacao remove pontos colineares`() {
        val simplified = LineSimplifier.simplify(TestData.straightLine(50), toleranceM = 10.0)
        assertEquals(2, simplified.size)
    }
}
