package com.fiscon.viagem.car

import android.location.Location
import android.os.SystemClock
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Alert
import androidx.car.app.model.CarText
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.fiscon.viagem.R
import com.fiscon.viagem.core.geo.RouteGeometry
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.Place
import com.fiscon.viagem.core.navigation.Instructions
import com.fiscon.viagem.core.navigation.NavAlert
import com.fiscon.viagem.core.navigation.NavState
import com.fiscon.viagem.core.navigation.NavigationTracker
import com.fiscon.viagem.data.TripRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TimeZone

/** Navegação curva a curva com alertas de radar e de paradas para abastecer. */
class NavigationScreen(carContext: CarContext, private val session: TravelSession) : Screen(carContext) {
    private val navigationManager = carContext.getCarService(NavigationManager::class.java)
    private var tracker: NavigationTracker? = TripRepository.trip.value?.let { NavigationTracker(it) }
    private var state: NavState? = null
    private var rerouting = false
    private var lastRerouteAt = 0L
    private var simulation: Job? = null
    private var announcedStepStart = -1.0
    private var alertId = 0

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
                    override fun onStopNavigation() = finish()
                    override fun onAutoDriveEnabled() = startSimulation()
                })
                navigationManager.navigationStarted()
                NavigationForegroundService.start(carContext)
                session.renderer.navigating = true
                session.voice.speak("Iniciando navegação para ${TripRepository.trip.value?.destination?.name.orEmpty()}")
            }

            override fun onDestroy(owner: LifecycleOwner) {
                simulation?.cancel()
                navigationManager.navigationEnded()
                navigationManager.clearNavigationManagerCallback()
                NavigationForegroundService.stop(carContext)
                session.renderer.navigating = false
            }
        })

        lifecycleScope.launch {
            session.locationSource.location.collect { loc ->
                if (loc != null && simulation?.isActive != true) onPosition(loc.toGeoPoint())
            }
        }
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

    private fun onPosition(position: GeoPoint) {
        val t = tracker ?: return
        val s = t.update(position)
        state = s
        session.renderer.updateLocation(s.snappedPosition, s.bearing.toFloat())
        s.alerts.forEach(::handleAlert)
        announceManeuver(s)
        if (s.offRoute) reroute(position, s.estimatedFuelL)
        invalidate()
    }

    private fun handleAlert(alert: NavAlert) {
        when (alert) {
            is NavAlert.RadarAhead -> {
                val dist = Instructions.formatDistance(alert.distanceM)
                val limit = alert.radar.maxSpeedKmh
                val title = "Radar em $dist"
                val subtitle = limit?.let { "Limite $it km/h" } ?: "Limite não informado"
                session.voice.speak(
                    "Radar em $dist" + (limit?.let { ". Limite de $it quilômetros por hora" } ?: ""),
                )
                showAlert(title, subtitle, R.drawable.ic_radar)
            }
            is NavAlert.FuelStopAhead -> {
                val dist = Instructions.formatDistance(alert.distanceM)
                val name = alert.stop.station.displayName
                session.voice.speak("Parada para abastecer em $dist: $name")
                showAlert(
                    "Abastecer em $dist",
                    String.format(ptBR, "%s · %.0f L", name, alert.stop.litersToFill),
                    R.drawable.ic_fuel,
                )
            }
            NavAlert.Arrived -> {
                session.voice.speak("Você chegou ao destino")
                simulation?.cancel()
            }
        }
    }

    /** Anuncia por voz a próxima manobra ao se aproximar dela (uma vez por manobra). */
    private fun announceManeuver(s: NavState) {
        val step = s.nextStep ?: return
        if (step.startDistanceM == announcedStepStart) return
        if (s.distanceToNextStepM <= 400) {
            announcedStepStart = step.startDistanceM
            session.voice.speak("Em ${Instructions.formatDistance(s.distanceToNextStepM)}, ${step.instruction}")
        }
    }

    private fun showAlert(title: String, subtitle: String, icon: Int) {
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
            val alert = Alert.Builder(++alertId, CarText.create(title), 8_000)
                .setSubtitle(CarText.create(subtitle))
                .setIcon(carContext.icon(icon))
                .build()
            carContext.getCarService(AppManager::class.java).showAlert(alert)
        } else {
            CarToast.makeText(carContext, "$title · $subtitle", CarToast.LENGTH_LONG).show()
        }
    }

    private fun reroute(position: GeoPoint, estimatedFuelL: Double) {
        val plan = tracker?.plan ?: return
        val now = SystemClock.elapsedRealtime()
        if (rerouting || now - lastRerouteAt < 15_000) return
        rerouting = true
        lastRerouteAt = now
        session.voice.speak("Recalculando rota")
        lifecycleScope.launch {
            try {
                val newPlan = TripRepository.planner.reroute(plan, Place("Posição atual", "", position), estimatedFuelL)
                TripRepository.save(newPlan)
                tracker = NavigationTracker(newPlan)
                announcedStepStart = -1.0
            } catch (e: Exception) {
                CarToast.makeText(carContext, "Falha ao recalcular: ${e.message}", CarToast.LENGTH_LONG).show()
            } finally {
                rerouting = false
                invalidate()
            }
        }
    }

    /** Simula o veículo percorrendo a rota (usado pelo "auto drive" do emulador DHU). */
    private fun startSimulation() {
        val plan = tracker?.plan ?: return
        simulation?.cancel()
        CarToast.makeText(carContext, "Simulação de percurso iniciada", CarToast.LENGTH_SHORT).show()
        simulation = lifecycleScope.launch {
            val geometry = RouteGeometry(plan.route.points)
            var distance = state?.progressM ?: 0.0
            val speedMps = 110 / 3.6 * 4 // 4x mais rápido que 110 km/h
            while (isActive && distance <= geometry.lengthM) {
                onPosition(geometry.pointAt(distance))
                distance += speedMps
                delay(1_000)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val builder = NavigationTemplate.Builder()
            .setActionStrip(actionStrip())
            .setMapActionStrip(mapActionStrip())

        val s = state
        val plan = tracker?.plan
        when {
            plan == null -> builder.setNavigationInfo(MessageInfo.Builder("Nenhuma viagem planejada").build())
            rerouting -> builder.setNavigationInfo(
                MessageInfo.Builder("Recalculando rota…").setImage(carContext.icon(R.drawable.ic_navigation)).build(),
            )
            s == null -> builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            s.arrived -> builder.setNavigationInfo(
                MessageInfo.Builder("Você chegou").setText(plan.destination.name)
                    .setImage(carContext.icon(R.drawable.ic_arrive)).build(),
            )
            s.offRoute -> builder.setNavigationInfo(MessageInfo.Builder("Fora da rota").setText("Recalculando…").build())
            else -> {
                val routing = RoutingInfo.Builder()
                val next = s.nextStep
                if (next != null) {
                    routing.setCurrentStep(ManeuverMapper.toStep(carContext, next), carDistance(s.distanceToNextStepM))
                    s.followingStep?.let { routing.setNextStep(ManeuverMapper.toStep(carContext, it)) }
                    builder.setNavigationInfo(routing.build())
                } else {
                    builder.setNavigationInfo(MessageInfo.Builder(plan.destination.name).build())
                }
                val arrival = System.currentTimeMillis() + (s.remainingS * 1000).toLong()
                builder.setDestinationTravelEstimate(
                    TravelEstimate.Builder(
                        carDistance(s.remainingM),
                        DateTimeWithZone.create(arrival, TimeZone.getDefault()),
                    ).setRemainingTimeSeconds(s.remainingS.toLong()).build(),
                )
            }
        }
        return builder.build()
    }

    private fun actionStrip(): ActionStrip {
        val s = state
        val radarTitle = s?.distanceToRadarM?.takeIf { it <= 5_000 }?.let { Instructions.formatDistance(it) } ?: "Radares"
        val fuelTitle = s?.distanceToFuelStopM?.let { Instructions.formatDistance(it) } ?: "Postos"
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(radarTitle)
                    .setIcon(carContext.icon(R.drawable.ic_radar))
                    .setOnClickListener {
                        screenManager.push(RadarListScreen(carContext, session, state?.progressM ?: 0.0))
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle(fuelTitle)
                    .setIcon(carContext.icon(R.drawable.ic_fuel))
                    .setOnClickListener { screenManager.push(FuelStopsScreen(carContext, session)) }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Parar")
                    .setOnClickListener { finish() }
                    .build(),
            )
            .build()
    }

    private fun mapActionStrip(): ActionStrip = ActionStrip.Builder()
        .addAction(Action.PAN)
        .addAction(
            Action.Builder().setIcon(carContext.icon(R.drawable.ic_recenter))
                .setOnClickListener { session.renderer.recenter() }.build(),
        )
        .addAction(
            Action.Builder().setIcon(carContext.icon(R.drawable.ic_zoom_in))
                .setOnClickListener { session.renderer.zoom(1f) }.build(),
        )
        .addAction(
            Action.Builder().setIcon(carContext.icon(R.drawable.ic_zoom_out))
                .setOnClickListener { session.renderer.zoom(-1f) }.build(),
        )
        .build()
}
