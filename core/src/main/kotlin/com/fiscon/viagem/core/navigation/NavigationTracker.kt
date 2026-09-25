package com.fiscon.viagem.core.navigation

import com.fiscon.viagem.core.geo.RouteGeometry
import com.fiscon.viagem.core.model.FuelStop
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.Radar
import com.fiscon.viagem.core.model.RouteStep
import com.fiscon.viagem.core.model.TripPlan
import com.fiscon.viagem.core.planning.FuelPlanner

sealed interface NavAlert {
    data class RadarAhead(val radar: Radar, val distanceM: Double) : NavAlert
    data class FuelStopAhead(val stop: FuelStop, val distanceM: Double) : NavAlert
    data object Arrived : NavAlert
}

data class NavState(
    val position: GeoPoint,
    /** Posição "presa" à rota (para desenhar o veículo sobre a pista). */
    val snappedPosition: GeoPoint,
    val bearing: Double,
    val progressM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val offRoute: Boolean,
    /** Próxima manobra a executar. */
    val nextStep: RouteStep?,
    /** Manobra seguinte à próxima (para exibir "depois, ..."). */
    val followingStep: RouteStep?,
    val distanceToNextStepM: Double,
    val nextRadar: Radar?,
    val distanceToRadarM: Double?,
    val nextFuelStop: FuelStop?,
    val distanceToFuelStopM: Double?,
    val estimatedFuelL: Double,
    /** Alertas novos gerados nesta atualização (cada um é emitido uma única vez). */
    val alerts: List<NavAlert>,
    val arrived: Boolean,
)

/**
 * Acompanha a posição do veículo ao longo de um [TripPlan]. Sem dependências do
 * Android: recebe coordenadas e devolve o estado da navegação.
 */
class NavigationTracker(
    val plan: TripPlan,
    private val radarAlertDistancesM: List<Double> = listOf(1000.0, 300.0),
    private val fuelAlertDistanceM: Double = 3000.0,
    private val offRouteThresholdM: Double = 60.0,
    private val offRouteConfirmations: Int = 3,
    private val arrivalDistanceM: Double = 30.0,
) {
    private val geometry = RouteGeometry(plan.route.points)
    private val steps = plan.route.steps
    private val stepEndDuration: DoubleArray
    private var lastSegment = 0
    private var progressM = 0.0
    private var offRouteCount = 0
    private val firedRadarAlerts = HashSet<Pair<Long, Double>>()
    private val firedFuelAlerts = HashSet<Long>()
    private var arrivedFired = false

    init {
        // Tempo restante a partir do início de cada passo.
        stepEndDuration = DoubleArray(steps.size + 1)
        for (i in steps.indices.reversed()) stepEndDuration[i] = stepEndDuration[i + 1] + steps[i].durationS
    }

    fun update(position: GeoPoint): NavState {
        // Procura primeiro perto da última posição conhecida; se longe, varre a rota toda.
        var proj = geometry.projectRange(position, lastSegment - 5, lastSegment + 300)
        if (proj.offsetM > offRouteThresholdM) {
            val global = geometry.project(position)
            if (global.offsetM < proj.offsetM) proj = global
        }

        offRouteCount = if (proj.offsetM > offRouteThresholdM) offRouteCount + 1 else 0
        val offRoute = offRouteCount >= offRouteConfirmations
        if (!offRoute) {
            lastSegment = proj.segmentIndex
            progressM = proj.distanceAlongM
        }

        val length = geometry.lengthM
        val remaining = (length - progressM).coerceAtLeast(0.0)
        val nextIndex = steps.indexOfFirst { it.startDistanceM > progressM + 5.0 }.let { if (it == -1) steps.lastIndex else it }
        val nextStep = steps.getOrNull(nextIndex)
        val distanceToNext = nextStep?.let { (it.startDistanceM - progressM).coerceAtLeast(0.0) } ?: remaining

        // Tempo restante: duração dos passos futuros + fração do passo atual.
        val remainingS = if (steps.isEmpty() || nextIndex <= 0) {
            plan.route.durationS * (remaining / length.coerceAtLeast(1.0))
        } else {
            val current = steps[nextIndex - 1]
            val stepLen = (nextStep!!.startDistanceM - current.startDistanceM).coerceAtLeast(1.0)
            stepEndDuration[nextIndex] + current.durationS * (distanceToNext / stepLen).coerceIn(0.0, 1.0)
        }

        val alerts = mutableListOf<NavAlert>()

        val nextRadar = plan.radars.firstOrNull { it.distanceAlongM >= progressM - 10.0 }
        val radarDistance = nextRadar?.let { (it.distanceAlongM - progressM).coerceAtLeast(0.0) }
        if (nextRadar != null && radarDistance != null && !offRoute) {
            // Dispara apenas o limiar mais próximo ainda não emitido.
            val threshold = radarAlertDistancesM.sorted().firstOrNull { radarDistance <= it }
            if (threshold != null && firedRadarAlerts.add(nextRadar.id to threshold)) {
                radarAlertDistancesM.filter { it > threshold }.forEach { firedRadarAlerts.add(nextRadar.id to it) }
                alerts += NavAlert.RadarAhead(nextRadar, radarDistance)
            }
        }

        val nextFuel = plan.fuelPlan.stops.firstOrNull { it.station.distanceAlongM >= progressM - 50.0 }
        val fuelDistance = nextFuel?.let { (it.station.distanceAlongM - progressM).coerceAtLeast(0.0) }
        if (nextFuel != null && fuelDistance != null && fuelDistance <= fuelAlertDistanceM &&
            !offRoute && firedFuelAlerts.add(nextFuel.station.id)
        ) {
            alerts += NavAlert.FuelStopAhead(nextFuel, fuelDistance)
        }

        val arrived = !offRoute && remaining <= arrivalDistanceM
        if (arrived && !arrivedFired) {
            arrivedFired = true
            alerts += NavAlert.Arrived
        }

        return NavState(
            position = position,
            snappedPosition = if (offRoute) position else proj.point,
            bearing = geometry.bearingAt(progressM),
            progressM = progressM,
            remainingM = remaining,
            remainingS = remainingS,
            offRoute = offRoute,
            nextStep = nextStep,
            followingStep = steps.getOrNull(nextIndex + 1),
            distanceToNextStepM = distanceToNext,
            nextRadar = nextRadar,
            distanceToRadarM = radarDistance,
            nextFuelStop = nextFuel,
            distanceToFuelStopM = fuelDistance,
            estimatedFuelL = FuelPlanner.estimatedFuelAt(progressM, plan.fuelPlan, plan.vehicle),
            alerts = alerts,
            arrived = arrived,
        )
    }
}
