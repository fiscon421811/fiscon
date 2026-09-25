package com.fiscon.viagem.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.Place
import com.fiscon.viagem.core.planning.PlanningProgress
import com.fiscon.viagem.data.TripRepository
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Planeja a viagem da posição atual até [destination] e volta à tela inicial. */
class PlanningScreen(
    carContext: CarContext,
    private val session: TravelSession,
    private val destination: Place,
) : Screen(carContext) {
    private var status = "Obtendo sua localização…"
    private var error: String? = null

    init {
        lifecycleScope.launch { plan() }
    }

    private suspend fun plan() {
        try {
            session.locationSource.start()
            val loc = withTimeoutOrNull(20_000) { session.locationSource.location.filterNotNull().first() }
                ?: throw IllegalStateException("Sem sinal de GPS. Verifique a permissão de localização.")
            val here = GeoPoint(loc.latitude, loc.longitude)
            update("Calculando rota…")
            val origin = Place("Minha localização", "", here)
            TripRepository.plan(origin, destination, TripRepository.settings.vehicle) { progress ->
                update(
                    when (progress) {
                        PlanningProgress.Routing -> "Calculando rota…"
                        // Sem contador: o host limita quantas vezes um template pode ser trocado.
                        is PlanningProgress.SearchingPois -> "Buscando radares e postos no trajeto…"
                        PlanningProgress.PlanningFuel -> "Planejando abastecimentos…"
                    },
                )
            }
            screenManager.popToRoot()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            invalidate()
        }
    }

    private fun update(text: String) {
        if (text == status) return
        status = text
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val err = error
        return if (err == null) {
            MessageTemplate.Builder(status)
                .setTitle(destination.name)
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        } else {
            MessageTemplate.Builder(err)
                .setTitle("Não foi possível planejar")
                .setHeaderAction(Action.BACK)
                .addAction(
                    Action.Builder()
                        .setTitle("Tentar de novo")
                        .setOnClickListener {
                            error = null
                            status = "Tentando novamente…"
                            invalidate()
                            lifecycleScope.launch { plan() }
                        }
                        .build(),
                )
                .build()
        }
    }
}
