package com.fiscon.viagem.core.planning

import com.fiscon.viagem.core.api.NominatimClient
import com.fiscon.viagem.core.api.OsmPoi
import com.fiscon.viagem.core.api.OsrmClient
import com.fiscon.viagem.core.api.OverpassClient
import com.fiscon.viagem.core.api.Http
import com.fiscon.viagem.core.geo.RouteGeometry
import com.fiscon.viagem.core.model.FuelStation
import com.fiscon.viagem.core.model.Place
import com.fiscon.viagem.core.model.Radar
import com.fiscon.viagem.core.model.TripPlan
import com.fiscon.viagem.core.model.VehicleProfile
import okhttp3.OkHttpClient

sealed interface PlanningProgress {
    data object Routing : PlanningProgress
    data class SearchingPois(val done: Int, val total: Int) : PlanningProgress
    data object PlanningFuel : PlanningProgress
}

/** Orquestra rota (OSRM) + radares e postos (Overpass) + plano de abastecimento. */
class TripPlanner(
    val osrm: OsrmClient,
    val overpass: OverpassClient,
    val nominatim: NominatimClient,
    private val radarRadiusM: Int = 80,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        fun create(client: OkHttpClient = Http.defaultClient()) =
            TripPlanner(OsrmClient(client), OverpassClient(client), NominatimClient(client))
    }

    suspend fun plan(
        origin: Place,
        destination: Place,
        vehicle: VehicleProfile,
        onProgress: (PlanningProgress) -> Unit = {},
    ): TripPlan {
        onProgress(PlanningProgress.Routing)
        val route = osrm.route(origin.location, destination.location)
        val geometry = RouteGeometry(route.points)

        val pois = overpass.poisAlongRoute(
            route.points,
            radarRadiusM = radarRadiusM,
            fuelRadiusM = vehicle.maxDetourM.toInt(),
        ) { done, total -> onProgress(PlanningProgress.SearchingPois(done, total)) }

        val radars = PoiMapper.radars(pois.speedCameras, geometry, radarRadiusM.toDouble())
        val stations = PoiMapper.fuelStations(pois.fuelStations, geometry, vehicle.maxDetourM)

        onProgress(PlanningProgress.PlanningFuel)
        val fuelPlan = FuelPlanner.plan(route.distanceM, stations, vehicle)
        return TripPlan(origin, destination, route, radars, stations, fuelPlan, vehicle, clock())
    }

    /**
     * Recalcula a rota a partir da posição atual (saída da rota). Radares e postos já
     * conhecidos são reaproveitados e reprojetados, evitando uma nova consulta pesada.
     */
    suspend fun reroute(previous: TripPlan, from: Place, estimatedFuelL: Double): TripPlan {
        val route = osrm.route(from.location, previous.destination.location)
        val geometry = RouteGeometry(route.points)
        val radars = previous.radars.mapNotNull { r ->
            val p = geometry.project(r.location)
            if (p.offsetM <= radarRadiusM) r.copy(distanceAlongM = p.distanceAlongM, offsetM = p.offsetM) else null
        }.sortedBy { it.distanceAlongM }
        val stations = previous.fuelStations.mapNotNull { s ->
            val p = geometry.project(s.location)
            if (p.offsetM <= previous.vehicle.maxDetourM) s.copy(distanceAlongM = p.distanceAlongM, offsetM = p.offsetM) else null
        }.sortedBy { it.distanceAlongM }
        val vehicle = previous.vehicle.copy(currentFuelL = estimatedFuelL)
        val fuelPlan = FuelPlanner.plan(route.distanceM, stations, vehicle)
        return previous.copy(
            origin = from, route = route, radars = radars, fuelStations = stations,
            fuelPlan = fuelPlan, vehicle = vehicle, createdAtMillis = clock(),
        )
    }
}

internal object PoiMapper {
    fun radars(pois: List<OsmPoi>, geometry: RouteGeometry, maxOffsetM: Double): List<Radar> =
        pois.mapNotNull { poi ->
            val p = geometry.project(poi.location)
            if (p.offsetM > maxOffsetM) return@mapNotNull null
            Radar(poi.id, poi.location, parseMaxSpeed(poi.tags["maxspeed"]), p.distanceAlongM, p.offsetM)
        }.sortedBy { it.distanceAlongM }

    fun fuelStations(pois: List<OsmPoi>, geometry: RouteGeometry, maxOffsetM: Double): List<FuelStation> =
        pois.mapNotNull { poi ->
            val p = geometry.project(poi.location)
            if (p.offsetM > maxOffsetM) return@mapNotNull null
            val fuels = poi.tags.filter { (k, v) -> k.startsWith("fuel:") && v == "yes" }
                .keys.map { it.removePrefix("fuel:") }.sorted()
            FuelStation(
                id = poi.id,
                name = poi.tags["name"].orEmpty(),
                brand = poi.tags["brand"] ?: poi.tags["operator"],
                location = poi.location,
                open24h = poi.tags["opening_hours"] == "24/7",
                fuels = fuels,
                distanceAlongM = p.distanceAlongM,
                offsetM = p.offsetM,
            )
        }.sortedBy { it.distanceAlongM }

    /** "80", "80 km/h", "50 mph", "BR:urban" (desconhecido → null). */
    fun parseMaxSpeed(value: String?): Int? {
        if (value == null) return null
        val number = Regex("""\d+""").find(value)?.value?.toIntOrNull() ?: return null
        return if (value.contains("mph")) Math.round(number * 1.609344).toInt() else number
    }
}
