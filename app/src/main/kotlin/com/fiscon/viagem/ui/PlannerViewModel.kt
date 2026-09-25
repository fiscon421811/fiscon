package com.fiscon.viagem.ui

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.Place
import com.fiscon.viagem.core.model.TripPlan
import com.fiscon.viagem.core.model.VehicleProfile
import com.fiscon.viagem.core.planning.PlanningProgress
import com.fiscon.viagem.data.TripRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class PlaceFieldState(
    val query: String = "",
    val selected: Place? = null,
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
)

/** Campos do formulário do veículo, mantidos como texto para edição livre. */
data class VehicleForm(
    val tankL: String,
    val consumptionKmL: String,
    val fuelPercent: Float,
    val reservePercent: Float,
    val pricePerL: String,
    val maxDetourKm: String,
) {
    fun toProfile(): VehicleProfile? {
        val tank = tankL.toNumber() ?: return null
        val consumption = consumptionKmL.toNumber() ?: return null
        val price = pricePerL.toNumber() ?: return null
        val detour = maxDetourKm.toNumber() ?: return null
        if (tank <= 0 || consumption <= 0 || price < 0 || detour <= 0) return null
        return VehicleProfile(
            tankCapacityL = tank,
            consumptionKmPerL = consumption,
            currentFuelL = tank * fuelPercent / 100.0,
            reserveFraction = reservePercent / 100.0,
            fuelPricePerL = price,
            maxDetourM = detour * 1000.0,
        )
    }

    companion object {
        fun from(v: VehicleProfile) = VehicleForm(
            tankL = v.tankCapacityL.clean(),
            consumptionKmL = v.consumptionKmPerL.clean(),
            fuelPercent = (v.currentFuelL / v.tankCapacityL * 100).toFloat().coerceIn(0f, 100f),
            reservePercent = (v.reserveFraction * 100).toFloat(),
            pricePerL = v.fuelPricePerL.clean(),
            maxDetourKm = (v.maxDetourM / 1000.0).clean(),
        )

        private fun Double.clean() = if (this % 1.0 == 0.0) toLong().toString() else toString().replace('.', ',')
        private fun String.toNumber() = trim().replace(',', '.').toDoubleOrNull()
    }
}

data class PlannerUiState(
    val origin: PlaceFieldState = PlaceFieldState(),
    val destination: PlaceFieldState = PlaceFieldState(),
    val vehicle: VehicleForm,
    val planning: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

enum class Field { ORIGIN, DESTINATION }

class PlannerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = TripRepository
    private val _state = MutableStateFlow(PlannerUiState(vehicle = VehicleForm.from(repo.settings.vehicle)))
    val state: StateFlow<PlannerUiState> = _state.asStateFlow()
    val trip: StateFlow<TripPlan?> = repo.trip

    private fun updateField(field: Field, f: (PlaceFieldState) -> PlaceFieldState) = _state.update {
        when (field) {
            Field.ORIGIN -> it.copy(origin = f(it.origin))
            Field.DESTINATION -> it.copy(destination = f(it.destination))
        }
    }

    fun onQueryChange(field: Field, query: String) =
        updateField(field) { it.copy(query = query, selected = null, results = emptyList()) }

    fun search(field: Field) {
        val query = when (field) {
            Field.ORIGIN -> state.value.origin.query
            Field.DESTINATION -> state.value.destination.query
        }
        if (query.isBlank()) return
        updateField(field) { it.copy(searching = true) }
        viewModelScope.launch {
            runCatching { repo.planner.nominatim.search(query) }
                .onSuccess { results ->
                    updateField(field) { it.copy(results = results, searching = false) }
                    if (results.isEmpty()) _state.update { it.copy(error = "Nenhum resultado para \"$query\"") }
                }
                .onFailure { e ->
                    updateField(field) { it.copy(searching = false) }
                    _state.update { it.copy(error = "Erro na busca: ${e.message}") }
                }
        }
    }

    fun select(field: Field, place: Place) =
        updateField(field) { it.copy(selected = place, query = place.name, results = emptyList()) }

    fun onVehicleChange(form: VehicleForm) = _state.update { it.copy(vehicle = form) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Planeja a viagem. Sem origem escolhida, usa a localização atual do aparelho. */
    fun plan(hasLocationPermission: Boolean) {
        val s = state.value
        val destination = s.destination.selected ?: run {
            _state.update { it.copy(error = "Escolha o destino na lista de resultados da busca.") }
            return
        }
        val vehicle = s.vehicle.toProfile() ?: run {
            _state.update { it.copy(error = "Verifique os dados do veículo.") }
            return
        }
        repo.settings.vehicle = vehicle
        _state.update { it.copy(planning = true, status = "Obtendo origem…", error = null) }
        viewModelScope.launch {
            try {
                val origin = s.origin.selected ?: currentPlace(hasLocationPermission)
                repo.plan(origin, destination, vehicle) { progress ->
                    val text = when (progress) {
                        PlanningProgress.Routing -> "Calculando rota…"
                        is PlanningProgress.SearchingPois ->
                            "Buscando radares e postos (${progress.done}/${progress.total})…"
                        PlanningProgress.PlanningFuel -> "Planejando abastecimentos…"
                    }
                    _state.update { it.copy(status = text) }
                }
                _state.update { it.copy(planning = false, status = null) }
            } catch (e: Exception) {
                _state.update { it.copy(planning = false, status = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun clearTrip() = viewModelScope.launch { repo.clear() }

    @SuppressLint("MissingPermission")
    private suspend fun currentPlace(hasPermission: Boolean): Place {
        if (!hasPermission) throw IllegalStateException("Informe a origem ou permita o acesso à localização.")
        val client = LocationServices.getFusedLocationProviderClient(getApplication<Application>())
        val location = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            ?: client.lastLocation.await()
            ?: throw IllegalStateException("Não foi possível obter a localização atual.")
        return repo.planner.nominatim.reverse(GeoPoint(location.latitude, location.longitude))
    }
}
