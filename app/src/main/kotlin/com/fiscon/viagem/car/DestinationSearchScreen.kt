package com.fiscon.viagem.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.fiscon.viagem.core.model.Place
import com.fiscon.viagem.data.TripRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Busca de destino no carro. A consulta só é enviada ao confirmar (a política do
 * Nominatim não permite autocompletar a cada tecla).
 */
class DestinationSearchScreen(
    carContext: CarContext,
    private val session: TravelSession,
    initialQuery: String? = null,
) : Screen(carContext) {
    private var results: List<Place> = emptyList()
    private var loading = false
    private var message: String? = null
    private var job: Job? = null
    private var lastQuery: String = initialQuery.orEmpty()

    init {
        if (!initialQuery.isNullOrBlank()) search(initialQuery)
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                lastQuery = searchText
            }

            override fun onSearchSubmitted(searchText: String) = search(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Cidade, endereço ou local")
            .setShowKeyboardByDefault(results.isEmpty() && !loading)
            .setInitialSearchText(lastQuery)

        if (loading) {
            builder.setLoading(true)
        } else {
            val items = ItemList.Builder()
            if (results.isEmpty()) items.setNoItemsMessage(message ?: "Digite o destino e confirme")
            results.take(carContext.placeListLimit()).forEach { place ->
                items.addItem(
                    Row.Builder()
                        .setTitle(place.name)
                        .addText(place.address)
                        .setOnClickListener { screenManager.push(PlanningScreen(carContext, session, place)) }
                        .build(),
                )
            }
            builder.setItemList(items.build())
        }
        return builder.build()
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        lastQuery = query
        job?.cancel()
        loading = true
        invalidate()
        job = lifecycleScope.launch {
            runCatching { TripRepository.planner.nominatim.search(query) }
                .onSuccess {
                    results = it
                    message = if (it.isEmpty()) "Nada encontrado para \"$query\"" else null
                }
                .onFailure {
                    results = emptyList()
                    message = "Erro na busca: ${it.message}"
                }
            loading = false
            invalidate()
        }
    }
}
