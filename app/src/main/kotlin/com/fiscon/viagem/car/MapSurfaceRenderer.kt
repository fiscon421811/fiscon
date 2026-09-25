package com.fiscon.viagem.car

import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fiscon.viagem.R
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.TripPlan
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlin.math.ln

/**
 * Desenha o Google Maps na tela do Android Auto.
 *
 * O host do Android Auto entrega uma [android.view.Surface]; criamos um VirtualDisplay
 * sobre ela e exibimos um [MapView] dentro de um [Presentation]. Todas as chamadas
 * acontecem na thread principal.
 */
class MapSurfaceRenderer(private val carContext: CarContext) : DefaultLifecycleObserver {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: GoogleMap? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var visibleArea: Rect? = null

    private var trip: TripPlan? = null
    private var vehicleMarker: Marker? = null
    private var location: GeoPoint? = null
    private var bearing = 0f
    private var focus: GeoPoint? = null

    /** Durante a navegação a câmera acompanha o veículo em perspectiva. */
    var navigating = false
        set(value) {
            field = value
            followVehicle = true
            updateCamera(animate = false)
        }

    /** Desligado quando o usuário arrasta o mapa; religado pelo botão "centralizar". */
    var followVehicle = true
        private set

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = createMap(surfaceContainer)

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = releaseMap()

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            this@MapSurfaceRenderer.visibleArea = visibleArea
            applyPadding()
            updateCamera(animate = false)
        }

        override fun onStableAreaChanged(stableArea: Rect) {}

        override fun onScroll(distanceX: Float, distanceY: Float) {
            followVehicle = false
            map?.moveCamera(CameraUpdateFactory.scrollBy(distanceX, distanceY))
        }

        override fun onFling(velocityX: Float, velocityY: Float) {}

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            val amount = (ln(scaleFactor.toDouble()) / ln(2.0)).toFloat()
            val update = if (focusX < 0 || focusY < 0) {
                CameraUpdateFactory.zoomBy(amount)
            } else {
                CameraUpdateFactory.zoomBy(amount, Point(focusX.toInt(), focusY.toInt()))
            }
            map?.moveCamera(update)
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        releaseMap()
    }

    private fun createMap(container: SurfaceContainer) {
        val surface = container.surface ?: return
        releaseMap()
        surfaceWidth = container.width
        surfaceHeight = container.height
        try {
            val displayManager = carContext.getSystemService(DisplayManager::class.java)
            val display = displayManager.createVirtualDisplay(
                "PlanejadorViagemMapa", container.width, container.height, container.dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            virtualDisplay = display
            val p = Presentation(carContext, display.display)
            val options = GoogleMapOptions()
                .compassEnabled(false)
                .mapToolbarEnabled(false)
                .zoomControlsEnabled(false)
            val view = MapView(p.context, options)
            view.onCreate(null)
            p.setContentView(view)
            p.show()
            view.onStart()
            view.onResume()
            presentation = p
            mapView = view
            view.getMapAsync { googleMap ->
                map = googleMap
                configureMap(googleMap)
                drawTrip()
                updateCamera(animate = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao criar o mapa no carro", e)
        }
    }

    private fun releaseMap() {
        mapView?.let {
            it.onPause()
            it.onStop()
            it.onDestroy()
        }
        presentation?.dismiss()
        virtualDisplay?.release()
        mapView = null
        presentation = null
        virtualDisplay = null
        map = null
        vehicleMarker = null
    }

    private fun configureMap(m: GoogleMap) {
        m.uiSettings.isMapToolbarEnabled = false
        m.uiSettings.isCompassEnabled = false
        m.isTrafficEnabled = true
        m.isBuildingsEnabled = true
        if (carContext.isDarkMode) {
            runCatching { m.setMapStyle(MapStyleOptions.loadRawResourceStyle(carContext, R.raw.map_style_night)) }
        }
        applyPadding()
    }

    private fun applyPadding() {
        val m = map ?: return
        val area = visibleArea ?: return
        m.setPadding(area.left, area.top, surfaceWidth - area.right, surfaceHeight - area.bottom)
    }

    fun setTrip(plan: TripPlan?) {
        trip = plan
        drawTrip()
        updateCamera(animate = false)
    }

    fun updateLocation(point: GeoPoint, bearingDegrees: Float) {
        location = point
        bearing = bearingDegrees
        val m = map ?: return
        val latLng = LatLng(point.lat, point.lon)
        val marker = vehicleMarker ?: m.addMarker(
            MarkerOptions().position(latLng).icon(vehicleIcon()).flat(true).anchor(0.5f, 0.5f).zIndex(10f),
        ).also { vehicleMarker = it }
        marker?.position = latLng
        marker?.rotation = bearingDegrees
        if (followVehicle) updateCamera(animate = true)
    }

    /** Centraliza o mapa em um ponto (ex.: posto escolhido na lista). */
    fun showPoint(point: GeoPoint) {
        followVehicle = false
        focus = point
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 15f))
    }

    fun recenter() {
        followVehicle = true
        focus = null
        updateCamera(animate = true)
    }

    fun zoom(delta: Float) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(delta))
    }

    private fun drawTrip() {
        val m = map ?: return
        m.clear()
        vehicleMarker = null
        val plan = trip
        if (plan != null) {
            m.addPolyline(
                PolylineOptions()
                    .addAll(plan.route.points.map { LatLng(it.lat, it.lon) })
                    .color(Color.rgb(21, 101, 192))
                    .width(14f)
                    .geodesic(false),
            )
            m.addMarker(
                MarkerOptions().position(plan.destination.location.toLatLng()).title(plan.destination.name),
            )
            plan.radars.forEach { radar ->
                m.addMarker(
                    MarkerOptions()
                        .position(radar.location.toLatLng())
                        .title(radar.maxSpeedKmh?.let { "Radar $it km/h" } ?: "Radar")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
                )
            }
            plan.fuelPlan.stops.forEach { stop ->
                m.addMarker(
                    MarkerOptions()
                        .position(stop.station.location.toLatLng())
                        .title(stop.station.displayName)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                        .zIndex(5f),
                )
            }
        }
        location?.let { updateLocation(it, bearing) }
    }

    private fun updateCamera(animate: Boolean) {
        val m = map ?: return
        if (!followVehicle) return
        val loc = location
        val plan = trip
        val update = when {
            navigating && loc != null -> CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(loc.toLatLng()).zoom(17f).tilt(50f).bearing(bearing).build(),
            )
            plan != null -> {
                val bounds = LatLngBounds.builder().apply { plan.route.points.forEach { include(it.toLatLng()) } }.build()
                CameraUpdateFactory.newLatLngBounds(bounds, 48)
            }
            loc != null -> CameraUpdateFactory.newLatLngZoom(loc.toLatLng(), 14f)
            else -> return
        }
        runCatching { if (animate) m.animateCamera(update, 900, null) else m.moveCamera(update) }
            .onFailure { Log.w(TAG, "Câmera não atualizada", it) }
    }

    private var cachedVehicleIcon: BitmapDescriptor? = null

    private fun vehicleIcon(): BitmapDescriptor = cachedVehicleIcon ?: run {
        val drawable = ContextCompat.getDrawable(carContext, R.drawable.ic_navigation)!!.mutate()
        DrawableCompat.setTint(drawable, Color.rgb(21, 101, 192))
        val size = (48 * carContext.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        BitmapDescriptorFactory.fromBitmap(bitmap).also { cachedVehicleIcon = it }
    }

    private fun GeoPoint.toLatLng() = LatLng(lat, lon)

    companion object {
        private const val TAG = "MapSurfaceRenderer"
    }
}
