package com.fiscon.viagem.core

import com.fiscon.viagem.core.api.Http
import com.fiscon.viagem.core.api.OsrmClient
import com.fiscon.viagem.core.api.OverpassClient
import com.fiscon.viagem.core.model.ManeuverModifier
import com.fiscon.viagem.core.model.ManeuverType
import com.fiscon.viagem.core.planning.PoiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiParsingTest {
    private val client = Http.defaultClient()

    @Test
    fun `interpreta resposta do OSRM`() {
        val route = OsrmClient(client).parse(TestData.osrmResponse(TestData.straightLine(20)))
        assertEquals(21, route.points.size)
        assertEquals(3, route.steps.size)
        val turn = route.steps[1]
        assertEquals(ManeuverType.TURN, turn.type)
        assertEquals(ManeuverModifier.RIGHT, turn.modifier)
        assertEquals(10_000.0, turn.startDistanceM, 20.0)
        assertEquals("Vire à direita para Avenida B", turn.instruction)
        assertEquals("Siga pela Rodovia A (BR-101)", route.steps[0].instruction)
    }

    @Test
    fun `interpreta resposta do Overpass`() {
        val body = """
        {"elements":[
          {"type":"node","id":1,"lat":-23.1,"lon":-46.1,"tags":{"highway":"speed_camera","maxspeed":"80"}},
          {"type":"way","id":2,"center":{"lat":-23.2,"lon":-46.2},"tags":{"amenity":"fuel","name":"Posto X","brand":"Ipiranga"}}
        ]}
        """.trimIndent()
        val pois = OverpassClient(client).parse(body)
        assertEquals(2, pois.size)
        assertEquals(-2L, pois[1].id)
        assertEquals(-23.2, pois[1].location.lat, 1e-9)
    }

    @Test
    fun `divide rotas longas em trechos continuos`() {
        val overpass = OverpassClient(client, pointsPerChunk = 10)
        val chunks = overpass.chunk(TestData.straightLine(25))
        assertEquals(3, chunks.size)
        assertEquals(chunks[0].last(), chunks[1].first())
        assertEquals(TestData.straightLine(25).last(), chunks.last().last())
        val query = overpass.buildQuery(chunks[0], 80, 1500)
        assertTrue(query.contains("""node["highway"="speed_camera"](around:80,0.000000,0.000000,"""))
    }

    @Test
    fun `interpreta limite de velocidade`() {
        assertEquals(80, PoiMapper.parseMaxSpeed("80"))
        assertEquals(60, PoiMapper.parseMaxSpeed("60 km/h"))
        assertEquals(80, PoiMapper.parseMaxSpeed("50 mph"))
        assertEquals(null, PoiMapper.parseMaxSpeed("BR:urban"))
    }
}
