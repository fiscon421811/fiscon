package com.fiscon.viagem.car

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fiscon.viagem.R
import com.fiscon.viagem.core.navigation.Instructions
import com.fiscon.viagem.data.TripRepository
import kotlinx.coroutines.launch
import java.util.Locale

internal val ptBR = Locale("pt", "BR")

internal fun CarContext.placeListLimit(): Int =
    if (carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
        getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
    } else {
        6
    }

/** Tela inicial no carro: resumo da viagem planejada e atalhos. */
class TripOverviewScreen(carContext: CarContext, private val session: TravelSession) : Screen(carContext) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TripRepository.trip.collect { invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val trip = TripRepository.trip.value
        val items = ItemList.Builder()

        if (!session.locationSource.hasPermission) {
            items.addItem(
                Row.Builder()
                    .setTitle("Permitir acesso à localização")
                    .addText("Necessário para navegar e alertar radares")
                    .setOnClickListener(::requestLocation)
                    .build(),
            )
        }

        if (trip == null) {
            items.addItem(
                Row.Builder()
                    .setTitle("Nenhuma viagem planejada")
                    .addText("Planeje no celular ou busque um destino")
                    .build(),
            )
        } else {
            val fuel = trip.fuelPlan
            items.addItem(
                Row.Builder()
                    .setTitle("Iniciar navegação")
                    .addText("${trip.origin.name} → ${trip.destination.name}")
                    .addText(
                        "${Instructions.formatDistance(trip.route.distanceM)} · " +
                            Instructions.formatDuration(trip.route.durationS),
                    )
                    .setImage(carContext.icon(R.drawable.ic_navigation))
                    .setOnClickListener(::startNavigation)
                    .build(),
            )
            items.addItem(
                Row.Builder()
                    .setTitle("Abastecimento: ${fuel.stops.size} parada(s)")
                    .addText(
                        String.format(
                            ptBR, "Consumo %.1f L · R$ %.2f · chega com %.1f L",
                            fuel.fuelConsumedL, fuel.tripFuelCost, fuel.arrivalFuelL,
                        ),
                    )
                    .apply { fuel.warnings.firstOrNull()?.let { addText("⚠ $it") } }
                    .setImage(carContext.icon(R.drawable.ic_fuel))
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(FuelStopsScreen(carContext, session)) }
                    .build(),
            )
            items.addItem(
                Row.Builder()
                    .setTitle("Radares no trajeto: ${trip.radars.size}")
                    .addText("Alertas a 1 km e a 300 m")
                    .setImage(carContext.icon(R.drawable.ic_radar))
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(RadarListScreen(carContext, session, progressM = 0.0)) }
                    .build(),
            )
        }

        items.addItem(
            Row.Builder()
                .setTitle("Buscar destino")
                .addText("Planeja a partir da sua localização")
                .setImage(carContext.icon(R.drawable.ic_search))
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(DestinationSearchScreen(carContext, session)) }
                .build(),
        )

        return PlaceListNavigationTemplate.Builder()
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setItemList(items.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(carContext.icon(R.drawable.ic_recenter))
                            .setOnClickListener { session.renderer.recenter() }
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun startNavigation() {
        if (!session.locationSource.hasPermission) {
            requestLocation()
            return
        }
        screenManager.push(NavigationScreen(carContext, session))
    }

    private fun requestLocation() {
        carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
            if (granted.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
                session.locationSource.start()
            } else {
                CarToast.makeText(carContext, "Permita a localização no celular", CarToast.LENGTH_LONG).show()
            }
            invalidate()
        }
    }
}
