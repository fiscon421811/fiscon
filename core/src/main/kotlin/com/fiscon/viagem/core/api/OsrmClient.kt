package com.fiscon.viagem.core.api

import com.fiscon.viagem.core.geo.PolylineCodec
import com.fiscon.viagem.core.geo.RouteGeometry
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.ManeuverModifier
import com.fiscon.viagem.core.model.ManeuverType
import com.fiscon.viagem.core.model.Route
import com.fiscon.viagem.core.model.RouteStep
import com.fiscon.viagem.core.navigation.Instructions
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cálculo de rota pelo OSRM (Open Source Routing Machine) com dados do OpenStreetMap.
 * O servidor de demonstração é gratuito, mas sem garantia de disponibilidade; para uso
 * intenso, suba uma instância própria (docker osrm/osrm-backend) e troque [baseUrl].
 */
class OsrmClient(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://router.project-osrm.org",
) {
    @Serializable
    private data class Response(val code: String, val message: String? = null, val routes: List<OsrmRoute> = emptyList())

    @Serializable
    private data class OsrmRoute(val geometry: String, val distance: Double, val duration: Double, val legs: List<Leg>)

    @Serializable
    private data class Leg(val steps: List<Step> = emptyList())

    @Serializable
    private data class Step(
        val distance: Double,
        val duration: Double,
        val name: String = "",
        val ref: String? = null,
        val maneuver: Maneuver,
    )

    @Serializable
    private data class Maneuver(
        val type: String,
        val modifier: String? = null,
        val location: List<Double>,
        val exit: Int? = null,
    )

    suspend fun route(from: GeoPoint, to: GeoPoint): Route {
        val coords = "${from.lon},${from.lat};${to.lon},${to.lat}"
        val url = "$baseUrl/route/v1/driving/$coords?overview=full&steps=true&geometries=polyline6"
        val body = client.fetchString(Request.Builder().url(url).build())
        return parse(body)
    }

    internal fun parse(body: String): Route {
        val response = json.decodeFromString(Response.serializer(), body)
        if (response.code != "Ok" || response.routes.isEmpty()) {
            throw ApiException("Rota não encontrada (${response.code}${response.message?.let { ": $it" } ?: ""})")
        }
        val r = response.routes.first()
        val points = PolylineCodec.decode(r.geometry, 6)
        val geometry = RouteGeometry(points)

        // Posiciona cada manobra sobre a geometria, avançando de forma monotônica.
        var segmentHint = 0
        val steps = r.legs.flatMap { it.steps }.map { s ->
            val location = GeoPoint(s.maneuver.location[1], s.maneuver.location[0])
            val proj = geometry.projectRange(location, segmentHint, segmentHint + 400)
                .takeIf { it.offsetM < 30 } ?: geometry.projectRange(location, segmentHint, points.size)
            segmentHint = proj.segmentIndex
            val type = parseType(s.maneuver.type)
            val modifier = parseModifier(s.maneuver.modifier)
            val ref = s.ref.orEmpty()
            RouteStep(
                type = type,
                modifier = modifier,
                roundaboutExit = s.maneuver.exit,
                roadName = s.name,
                roadRef = ref,
                location = location,
                distanceM = s.distance,
                durationS = s.duration,
                startDistanceM = proj.distanceAlongM,
                instruction = Instructions.format(type, modifier, s.maneuver.exit, s.name, ref),
            )
        }
        return Route(points, r.distance, r.duration, steps)
    }

    private fun parseType(t: String) = when (t) {
        "depart" -> ManeuverType.DEPART
        "arrive" -> ManeuverType.ARRIVE
        "turn" -> ManeuverType.TURN
        "new name" -> ManeuverType.NEW_NAME
        "continue" -> ManeuverType.CONTINUE
        "merge" -> ManeuverType.MERGE
        "on ramp" -> ManeuverType.ON_RAMP
        "off ramp" -> ManeuverType.OFF_RAMP
        "fork" -> ManeuverType.FORK
        "end of road" -> ManeuverType.END_OF_ROAD
        "roundabout" -> ManeuverType.ROUNDABOUT
        "rotary" -> ManeuverType.ROTARY
        "exit roundabout", "exit rotary", "roundabout turn" -> ManeuverType.EXIT_ROUNDABOUT
        "notification" -> ManeuverType.NOTIFICATION
        else -> ManeuverType.UNKNOWN
    }

    private fun parseModifier(m: String?) = when (m) {
        "uturn" -> ManeuverModifier.UTURN
        "sharp right" -> ManeuverModifier.SHARP_RIGHT
        "right" -> ManeuverModifier.RIGHT
        "slight right" -> ManeuverModifier.SLIGHT_RIGHT
        "straight" -> ManeuverModifier.STRAIGHT
        "slight left" -> ManeuverModifier.SLIGHT_LEFT
        "left" -> ManeuverModifier.LEFT
        "sharp left" -> ManeuverModifier.SHARP_LEFT
        else -> ManeuverModifier.NONE
    }
}
