package com.fiscon.viagem.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fiscon.viagem.R
import com.fiscon.viagem.data.TripRepository

/** Paradas de abastecimento planejadas (e o mapa centraliza no posto escolhido). */
/** Ao sair da lista, o mapa volta a acompanhar o veículo/rota. */
private fun Screen.recenterOnExit(session: TravelSession) {
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = session.renderer.recenter()
    })
}

class FuelStopsScreen(carContext: CarContext, private val session: TravelSession) : Screen(carContext) {
    init {
        recenterOnExit(session)
    }

    override fun onGetTemplate(): Template {
        val plan = TripRepository.trip.value
        val items = ItemList.Builder()
        val stops = plan?.fuelPlan?.stops.orEmpty()
        if (stops.isEmpty()) {
            items.setNoItemsMessage(
                plan?.fuelPlan?.warnings?.firstOrNull() ?: "Não é preciso abastecer nesta viagem",
            )
        }
        stops.take(carContext.placeListLimit()).forEach { stop ->
            val s = stop.station
            items.addItem(
                Row.Builder()
                    .setTitle(s.displayName)
                    .addText(
                        String.format(
                            ptBR, "km %.0f · chega com %.1f L · abastecer %.1f L",
                            s.distanceAlongM / 1000, stop.arrivalFuelL, stop.litersToFill,
                        ),
                    )
                    .addText(
                        buildString {
                            append(String.format(ptBR, "R$ %.2f", stop.estimatedCost))
                            if (s.offsetM > 150) append(String.format(ptBR, " · desvio %.1f km", s.offsetM / 1000))
                            if (s.open24h) append(" · 24h")
                        },
                    )
                    .setImage(carContext.icon(R.drawable.ic_fuel))
                    .setOnClickListener { session.renderer.showPoint(s.location) }
                    .build(),
            )
        }
        return PlaceListNavigationTemplate.Builder()
            .setTitle("Paradas para abastecer")
            .setHeaderAction(Action.BACK)
            .setItemList(items.build())
            .build()
    }
}

/** Próximos radares a partir de [progressM] (distância já percorrida). */
class RadarListScreen(
    carContext: CarContext,
    private val session: TravelSession,
    private val progressM: Double,
) : Screen(carContext) {
    init {
        recenterOnExit(session)
    }

    override fun onGetTemplate(): Template {
        val radars = TripRepository.trip.value?.radars.orEmpty().filter { it.distanceAlongM >= progressM }
        val items = ItemList.Builder()
        if (radars.isEmpty()) items.setNoItemsMessage("Nenhum radar conhecido à frente")
        radars.take(carContext.placeListLimit()).forEach { radar ->
            val ahead = (radar.distanceAlongM - progressM) / 1000
            items.addItem(
                Row.Builder()
                    .setTitle(radar.maxSpeedKmh?.let { "Radar · $it km/h" } ?: "Radar (limite não informado)")
                    .addText(String.format(ptBR, "km %.1f · daqui a %.1f km", radar.distanceAlongM / 1000, ahead))
                    .setImage(carContext.icon(R.drawable.ic_radar))
                    .setOnClickListener { session.renderer.showPoint(radar.location) }
                    .build(),
            )
        }
        return PlaceListNavigationTemplate.Builder()
            .setTitle("Radares à frente")
            .setHeaderAction(Action.BACK)
            .setItemList(items.build())
            .build()
    }
}
