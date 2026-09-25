package com.fiscon.viagem.data

import android.content.Context
import android.util.Log
import com.fiscon.viagem.core.model.Place
import com.fiscon.viagem.core.model.TripPlan
import com.fiscon.viagem.core.model.VehicleProfile
import com.fiscon.viagem.core.planning.PlanningProgress
import com.fiscon.viagem.core.planning.TripPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Fonte única da viagem planejada, compartilhada entre o celular e o Android Auto
 * (ambos rodam no mesmo processo). A viagem é salva em disco para sobreviver a
 * reinícios do app.
 */
object TripRepository {
    private const val TAG = "TripRepository"
    private val json = Json { ignoreUnknownKeys = true }

    val planner: TripPlanner by lazy { TripPlanner.create() }

    private val _trip = MutableStateFlow<TripPlan?>(null)
    val trip: StateFlow<TripPlan?> = _trip.asStateFlow()

    private lateinit var file: File
    lateinit var settings: SettingsStore
        private set

    fun init(context: Context) {
        file = File(context.filesDir, "trip.json")
        settings = SettingsStore(context)
        _trip.value = runCatching {
            if (file.exists()) json.decodeFromString(TripPlan.serializer(), file.readText()) else null
        }.onFailure { Log.w(TAG, "Viagem salva inválida", it) }.getOrNull()
    }

    suspend fun plan(
        origin: Place,
        destination: Place,
        vehicle: VehicleProfile,
        onProgress: (PlanningProgress) -> Unit = {},
    ): TripPlan {
        val plan = planner.plan(origin, destination, vehicle, onProgress)
        save(plan)
        return plan
    }

    suspend fun save(plan: TripPlan) {
        _trip.value = plan
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(TripPlan.serializer(), plan)) }
                .onFailure { Log.w(TAG, "Falha ao salvar viagem", it) }
        }
    }

    suspend fun clear() {
        _trip.value = null
        withContext(Dispatchers.IO) { file.delete() }
    }
}
