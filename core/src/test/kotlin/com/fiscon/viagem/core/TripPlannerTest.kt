package com.fiscon.viagem.core

import com.fiscon.viagem.core.api.Http
import com.fiscon.viagem.core.api.NominatimClient
import com.fiscon.viagem.core.api.OsrmClient
import com.fiscon.viagem.core.api.OverpassClient
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.Place
import com.fiscon.viagem.core.model.VehicleProfile
import com.fiscon.viagem.core.navigation.NavAlert
import com.fiscon.viagem.core.navigation.NavigationTracker
import com.fiscon.viagem.core.planning.TripPlanner
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TripPlannerTest {
    private val server = MockWebServer()
    private val points = TestData.straightLine(600) // 600 km para o leste
    private fun lonAtKm(km: Double) = km * 0.0089932

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/route/v1/driving/") ->
                    MockResponse().setBody(TestData.osrmResponse(points, durationS = 21_600.0))
                request.path!!.startsWith("/api/interpreter") -> {
                    assertTrue(request.getHeader("User-Agent")!!.startsWith("PlanejadorViagem"))
                    MockResponse().setBody(
                        """
                        {"elements":[
                          {"type":"node","id":10,"lat":0.0003,"lon":${lonAtKm(100.0)},"tags":{"highway":"speed_camera","maxspeed":"80"}},
                          {"type":"node","id":11,"lat":0.01,"lon":${lonAtKm(150.0)},"tags":{"highway":"speed_camera"}},
                          {"type":"node","id":20,"lat":0.002,"lon":${lonAtKm(300.0)},"tags":{"amenity":"fuel","name":"Posto Km 300","opening_hours":"24/7","fuel:diesel":"yes"}},
                          {"type":"node","id":21,"lat":0.0,"lon":${lonAtKm(420.0)},"tags":{"amenity":"fuel","brand":"Shell"}}
                        ]}
                        """.trimIndent(),
                    )
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun planner(): TripPlanner {
        val client = Http.defaultClient()
        val base = server.url("/").toString().trimEnd('/')
        return TripPlanner(
            OsrmClient(client, base),
            OverpassClient(client, listOf("$base/api/interpreter")),
            NominatimClient(client, base),
        )
    }

    @Test
    fun `planeja viagem completa e acompanha navegacao`() = runTest {
        val vehicle = VehicleProfile(
            tankCapacityL = 40.0, consumptionKmPerL = 12.0, currentFuelL = 30.0,
            reserveFraction = 0.15, fuelPricePerL = 6.0,
        )
        val plan = planner().plan(
            Place("A", "", points.first()), Place("B", "", points.last()), vehicle,
        )

        // Radar a ~1,1 km da pista é descartado; o que está a 33 m é mantido.
        assertEquals(listOf(10L), plan.radars.map { it.id })
        assertEquals(80, plan.radars[0].maxSpeedKmh)
        assertEquals(2, plan.fuelStations.size)
        assertTrue(plan.fuelStations[0].open24h)

        // Com 30 L e 12 km/L (reserva 6 L) alcança 288 km: o posto do km 300 exige reserva.
        // Depois, 40 L cheios levam até ~708 km, então é a única parada.
        assertTrue(plan.fuelPlan.feasible)
        assertEquals(listOf(20L), plan.fuelPlan.stops.map { it.station.id })

        val tracker = NavigationTracker(plan)
        val start = tracker.update(GeoPoint(0.0, lonAtKm(0.5)))
        assertFalse(start.offRoute)
        assertEquals(299_500.0, start.distanceToNextStepM, 300.0)

        val nearRadar = tracker.update(GeoPoint(0.0, lonAtKm(99.2)))
        val radarAlert = nearRadar.alerts.filterIsInstance<NavAlert.RadarAhead>().single()
        assertEquals(800.0, radarAlert.distanceM, 30.0)
        // O mesmo limiar não é repetido.
        assertTrue(tracker.update(GeoPoint(0.0, lonAtKm(99.3))).alerts.isEmpty())
        assertEquals(1, tracker.update(GeoPoint(0.0, lonAtKm(99.8))).alerts.size)

        val nearFuel = tracker.update(GeoPoint(0.0, lonAtKm(298.0)))
        assertTrue(nearFuel.alerts.any { it is NavAlert.FuelStopAhead })

        // Três leituras seguidas a ~5 km da pista indicam saída da rota.
        repeat(2) { assertFalse(tracker.update(GeoPoint(0.045, lonAtKm(310.0))).offRoute) }
        assertTrue(tracker.update(GeoPoint(0.045, lonAtKm(310.0))).offRoute)

        val end = tracker.update(points.last())
        assertTrue(end.arrived)
        assertTrue(end.alerts.contains(NavAlert.Arrived))
    }

    @Test
    fun `recalcula rota reaproveitando pontos conhecidos`() = runTest {
        val planner = planner()
        val plan = planner.plan(Place("A", "", points.first()), Place("B", "", points.last()), VehicleProfile())
        val rerouted = planner.reroute(plan, Place("Aqui", "", points[50]), estimatedFuelL = 20.0)
        assertEquals(20.0, rerouted.vehicle.currentFuelL, 1e-9)
        assertEquals(plan.radars.size, rerouted.radars.size)
    }
}
