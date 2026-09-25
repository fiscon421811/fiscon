package com.fiscon.viagem.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

/** Lugar retornado pela geocodificação (Nominatim). */
@Serializable
data class Place(
    val name: String,
    val address: String,
    val location: GeoPoint,
)

/** Tipos de manobra do OSRM (independentes do Android Auto). */
@Serializable
enum class ManeuverType {
    DEPART, ARRIVE, TURN, NEW_NAME, CONTINUE, MERGE, ON_RAMP, OFF_RAMP, FORK,
    END_OF_ROAD, ROUNDABOUT, ROTARY, EXIT_ROUNDABOUT, NOTIFICATION, UNKNOWN,
}

@Serializable
enum class ManeuverModifier {
    UTURN, SHARP_RIGHT, RIGHT, SLIGHT_RIGHT, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, NONE,
}

@Serializable
data class RouteStep(
    val type: ManeuverType,
    val modifier: ManeuverModifier,
    val roundaboutExit: Int? = null,
    val roadName: String,
    val roadRef: String,
    val location: GeoPoint,
    val distanceM: Double,
    val durationS: Double,
    /** Distância, a partir do início da rota, em que a manobra acontece. */
    val startDistanceM: Double,
    val instruction: String,
)

@Serializable
data class Route(
    val points: List<GeoPoint>,
    val distanceM: Double,
    val durationS: Double,
    val steps: List<RouteStep>,
)

@Serializable
data class Radar(
    val id: Long,
    val location: GeoPoint,
    /** Limite de velocidade fiscalizado, quando informado no OpenStreetMap. */
    val maxSpeedKmh: Int?,
    val distanceAlongM: Double,
    val offsetM: Double,
)

@Serializable
data class FuelStation(
    val id: Long,
    val name: String,
    val brand: String?,
    val location: GeoPoint,
    val open24h: Boolean,
    val fuels: List<String>,
    val distanceAlongM: Double,
    /** Distância aproximada (ida) entre a rota e o posto. */
    val offsetM: Double,
) {
    val displayName: String
        get() = when {
            name.isNotBlank() && brand != null && !name.contains(brand, ignoreCase = true) -> "$name ($brand)"
            name.isNotBlank() -> name
            brand != null -> brand
            else -> "Posto de combustível"
        }
}

@Serializable
data class VehicleProfile(
    val tankCapacityL: Double = 50.0,
    val consumptionKmPerL: Double = 12.0,
    /** Combustível no tanque no início da viagem, em litros. */
    val currentFuelL: Double = 40.0,
    /** Fração do tanque que nunca deve ser usada (reserva de segurança). */
    val reserveFraction: Double = 0.15,
    val fuelPricePerL: Double = 6.0,
    /** Desvio máximo aceito (ida) para chegar a um posto. */
    val maxDetourM: Double = 1500.0,
) {
    val reserveL: Double get() = tankCapacityL * reserveFraction
    val litersPerMeter: Double get() = 1.0 / (consumptionKmPerL * 1000.0)
}

@Serializable
data class FuelStop(
    val station: FuelStation,
    val arrivalFuelL: Double,
    val litersToFill: Double,
    val estimatedCost: Double,
)

@Serializable
data class FuelPlan(
    val stops: List<FuelStop>,
    val fuelConsumedL: Double,
    val tripFuelCost: Double,
    val litersPurchased: Double,
    val purchaseCost: Double,
    val arrivalFuelL: Double,
    val feasible: Boolean,
    val warnings: List<String>,
)

@Serializable
data class TripPlan(
    val origin: Place,
    val destination: Place,
    val route: Route,
    val radars: List<Radar>,
    val fuelStations: List<FuelStation>,
    val fuelPlan: FuelPlan,
    val vehicle: VehicleProfile,
    val createdAtMillis: Long,
)
