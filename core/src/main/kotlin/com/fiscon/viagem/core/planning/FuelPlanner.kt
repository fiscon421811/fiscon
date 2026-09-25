package com.fiscon.viagem.core.planning

import com.fiscon.viagem.core.model.FuelPlan
import com.fiscon.viagem.core.model.FuelStation
import com.fiscon.viagem.core.model.FuelStop
import com.fiscon.viagem.core.model.VehicleProfile
import java.util.Locale

/**
 * Planeja as paradas para abastecimento.
 *
 * Estratégia gulosa: a partir da posição atual, entre os postos alcançáveis sem entrar
 * na reserva, escolhe o mais distante (penalizando o desvio até ele). Em cada parada o
 * tanque é completado. Se nenhum posto é alcançável sem usar a reserva, usa o primeiro
 * posto à frente e registra um aviso.
 */
object FuelPlanner {
    /** Peso do desvio: cada metro fora da rota "vale" este tanto de avanço na rota. */
    private const val DETOUR_PENALTY = 3.0

    fun plan(routeLengthM: Double, stations: List<FuelStation>, vehicle: VehicleProfile): FuelPlan {
        require(vehicle.tankCapacityL > 0 && vehicle.consumptionKmPerL > 0) { "Perfil do veículo inválido" }
        val lpm = vehicle.litersPerMeter
        val reserve = vehicle.reserveL
        val candidates = stations
            .filter { it.offsetM <= vehicle.maxDetourM && it.distanceAlongM in 0.0..routeLengthM }
            .sortedBy { it.distanceAlongM }

        val stops = mutableListOf<FuelStop>()
        val warnings = mutableListOf<String>()
        var feasible = true
        var pos = 0.0
        var fuel = vehicle.currentFuelL.coerceIn(0.0, vehicle.tankCapacityL)

        if (fuel < reserve) warnings += "Você já está na reserva: abasteça no primeiro posto."

        while (stops.size < 200) {
            val fuelAtDestination = fuel - (routeLengthM - pos) * lpm
            if (fuelAtDestination >= reserve) break

            fun arrivalFuel(s: FuelStation) = fuel - (s.distanceAlongM - pos + s.offsetM) * lpm
            val ahead = candidates.filter { it.distanceAlongM > pos + 1.0 }
            val comfortable = ahead.filter { arrivalFuel(it) >= reserve }

            val chosen = if (comfortable.isNotEmpty()) {
                comfortable.maxBy { it.distanceAlongM - DETOUR_PENALTY * it.offsetM }
            } else {
                val reachable = ahead.firstOrNull { arrivalFuel(it) > 0 }
                if (reachable == null) {
                    feasible = false
                    val km = pos / 1000.0
                    warnings += if (ahead.isEmpty()) {
                        "Não há postos conhecidos após o km %.0f; o combustível não chega ao destino.".format(Locale.US, km)
                    } else {
                        "Nenhum posto alcançável após o km %.0f com o combustível disponível.".format(Locale.US, km)
                    }
                    break
                }
                warnings += "Trecho longo sem postos: será preciso usar a reserva até ${reachable.displayName} (km %.0f)."
                    .format(Locale.US, reachable.distanceAlongM / 1000.0)
                reachable
            }

            val arrival = arrivalFuel(chosen)
            val liters = vehicle.tankCapacityL - arrival
            stops += FuelStop(chosen, arrival, liters, liters * vehicle.fuelPricePerL)
            fuel = vehicle.tankCapacityL - chosen.offsetM * lpm // volta para a rota
            pos = chosen.distanceAlongM
        }

        val detourM = stops.sumOf { it.station.offsetM * 2 }
        val consumed = (routeLengthM + detourM) * lpm
        val arrivalFuel = if (feasible) fuel - (routeLengthM - pos) * lpm else 0.0
        val purchased = stops.sumOf { it.litersToFill }
        return FuelPlan(
            stops = stops,
            fuelConsumedL = consumed,
            tripFuelCost = consumed * vehicle.fuelPricePerL,
            litersPurchased = purchased,
            purchaseCost = purchased * vehicle.fuelPricePerL,
            arrivalFuelL = arrivalFuel.coerceAtLeast(0.0),
            feasible = feasible,
            warnings = warnings,
        )
    }

    /** Combustível estimado no tanque ao passar pelo ponto [distanceM] da rota. */
    fun estimatedFuelAt(distanceM: Double, plan: FuelPlan, vehicle: VehicleProfile): Double {
        val lpm = vehicle.litersPerMeter
        var fuel = vehicle.currentFuelL
        var pos = 0.0
        for (stop in plan.stops) {
            if (stop.station.distanceAlongM > distanceM) break
            fuel = vehicle.tankCapacityL - stop.station.offsetM * lpm
            pos = stop.station.distanceAlongM
        }
        return (fuel - (distanceM - pos) * lpm).coerceIn(0.0, vehicle.tankCapacityL)
    }
}
