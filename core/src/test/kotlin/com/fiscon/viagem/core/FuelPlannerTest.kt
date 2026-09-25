package com.fiscon.viagem.core

import com.fiscon.viagem.core.model.FuelStation
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.VehicleProfile
import com.fiscon.viagem.core.planning.FuelPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelPlannerTest {
    private fun station(km: Double, offsetM: Double = 0.0, id: Long = km.toLong()) =
        FuelStation(id, "Posto $km", null, GeoPoint(0.0, 0.0), false, emptyList(), km * 1000, offsetM)

    // Tanque 50 L, 10 km/L, reserva 10% (5 L) -> 450 km úteis com o tanque cheio.
    private val car = VehicleProfile(
        tankCapacityL = 50.0, consumptionKmPerL = 10.0, currentFuelL = 50.0,
        reserveFraction = 0.1, fuelPricePerL = 6.0,
    )

    @Test
    fun `viagem curta nao precisa de parada`() {
        val plan = FuelPlanner.plan(300_000.0, listOf(station(100.0)), car)
        assertTrue(plan.stops.isEmpty())
        assertTrue(plan.feasible)
        assertEquals(30.0, plan.fuelConsumedL, 1e-6)
        assertEquals(20.0, plan.arrivalFuelL, 1e-6)
        assertEquals(180.0, plan.tripFuelCost, 1e-6)
    }

    @Test
    fun `escolhe o posto mais distante alcancavel`() {
        val stations = listOf(100.0, 250.0, 440.0, 460.0, 700.0).map { station(it) }
        val plan = FuelPlanner.plan(800_000.0, stations, car)
        assertTrue(plan.feasible)
        // 460 km exigiria entrar na reserva, então para no km 440 e dali chega ao destino.
        assertEquals(listOf(440_000.0), plan.stops.map { it.station.distanceAlongM })
        assertEquals(6.0, plan.stops[0].arrivalFuelL, 1e-6)
        assertEquals(44.0, plan.stops[0].litersToFill, 1e-6)
        assertEquals(14.0, plan.arrivalFuelL, 1e-6)
    }

    @Test
    fun `penaliza desvio grande`() {
        val far = station(432.0, offsetM = 1400.0, id = 1)
        val near = station(430.0, offsetM = 50.0, id = 2)
        val plan = FuelPlanner.plan(600_000.0, listOf(far, near), car.copy(maxDetourM = 1500.0))
        assertEquals(2L, plan.stops.single().station.id)
    }

    @Test
    fun `usa a reserva quando nao ha posto antes`() {
        val plan = FuelPlanner.plan(600_000.0, listOf(station(470.0)), car)
        assertTrue(plan.feasible)
        assertEquals(1, plan.stops.size)
        assertTrue(plan.warnings.single().contains("reserva"))
    }

    @Test
    fun `inviavel sem postos no caminho`() {
        val plan = FuelPlanner.plan(700_000.0, emptyList(), car)
        assertFalse(plan.feasible)
        assertEquals(1, plan.warnings.size)
    }

    @Test
    fun `combustivel estimado considera abastecimentos`() {
        val stations = listOf(station(440.0))
        val plan = FuelPlanner.plan(800_000.0, stations, car)
        assertEquals(40.0, FuelPlanner.estimatedFuelAt(100_000.0, plan, car), 1e-6)
        assertEquals(40.0, FuelPlanner.estimatedFuelAt(540_000.0, plan, car), 1e-6)
    }
}
