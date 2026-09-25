package com.fiscon.viagem.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.Place
import com.fiscon.viagem.data.TripRepository
import kotlinx.coroutines.launch

/** Sessão do Android Auto: guarda o mapa, o GPS e a voz compartilhados entre as telas. */
class TravelSession : Session() {
    lateinit var renderer: MapSurfaceRenderer
        private set
    lateinit var locationSource: CarLocationSource
        private set
    lateinit var voice: VoiceAnnouncer
        private set

    override fun onCreateScreen(intent: Intent): Screen {
        renderer = MapSurfaceRenderer(carContext)
        locationSource = CarLocationSource(carContext)
        voice = VoiceAnnouncer(carContext)
        lifecycle.addObserver(renderer)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                locationSource.start()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                locationSource.stop()
                voice.shutdown()
            }
        })

        lifecycleScope.launch { TripRepository.trip.collect { renderer.setTrip(it) } }
        lifecycleScope.launch {
            locationSource.location.collect { loc ->
                // Durante a navegação quem atualiza o veículo é a NavigationScreen (posição na pista).
                if (loc != null && !renderer.navigating) {
                    renderer.updateLocation(GeoPoint(loc.latitude, loc.longitude), loc.bearing)
                }
            }
        }

        val home = TripOverviewScreen(carContext, this)
        handleNavigationIntent(intent, carContext.getCarService(ScreenManager::class.java), home)
        return home
    }

    override fun onNewIntent(intent: Intent) {
        handleNavigationIntent(intent, carContext.getCarService(ScreenManager::class.java), null)
    }

    /**
     * Trata pedidos "navegar para" do Google Assistente / outros apps
     * (geo:lat,lon ou geo:0,0?q=endereço).
     */
    private fun handleNavigationIntent(intent: Intent, screenManager: ScreenManager, home: Screen?) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        val uri = intent.data ?: return
        val (point, query) = parseGeoUri(uri)
        if (home == null) screenManager.popToRoot()
        val screen = if (point != null) {
            PlanningScreen(carContext, this, Place(query ?: "Destino", query.orEmpty(), point))
        } else {
            DestinationSearchScreen(carContext, this, initialQuery = query)
        }
        if (home != null) {
            // A tela inicial ainda não está na pilha: empilha depois que ela for exibida.
            home.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    home.lifecycle.removeObserver(this)
                    screenManager.push(screen)
                }
            })
        } else {
            screenManager.push(screen)
        }
    }

    private fun parseGeoUri(uri: Uri): Pair<GeoPoint?, String?> {
        val ssp = uri.schemeSpecificPart ?: return null to null
        val coords = ssp.substringBefore('?').split(',')
        val query = Regex("""[?&]q=([^&]*)""").find(ssp)?.groupValues?.get(1)?.let(Uri::decode)
        val lat = coords.getOrNull(0)?.toDoubleOrNull()
        val lon = coords.getOrNull(1)?.toDoubleOrNull()
        val point = if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) GeoPoint(lat, lon) else null
        return point to query?.takeIf { it.isNotBlank() }
    }
}
