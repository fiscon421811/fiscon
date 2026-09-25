package com.fiscon.viagem.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fiscon.viagem.core.model.GeoPoint
import com.fiscon.viagem.core.model.TripPlan
import com.fiscon.viagem.core.navigation.Instructions
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.util.Locale

private val ptBR = Locale("pt", "BR")
private fun GeoPoint.toLatLng() = LatLng(lat, lon)
private fun money(v: Double) = String.format(ptBR, "R$ %.2f", v)
private fun liters(v: Double) = String.format(ptBR, "%.1f L", v)
private fun km(m: Double) = String.format(ptBR, "km %.0f", m / 1000.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(vm: PlannerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val trip by vm.trip.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun hasLocation() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Planejador de Viagem") },
            actions = {
                if (trip != null) {
                    IconButton(onClick = vm::clearTrip) { Icon(Icons.Default.Delete, "Apagar viagem") }
                }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PlaceField("Origem (vazio = minha localização)", state.origin, Field.ORIGIN, vm)
            }
            item { PlaceField("Destino", state.destination, Field.DESTINATION, vm) }
            item { VehicleCard(state.vehicle, vm::onVehicleChange) }
            item {
                Button(
                    onClick = { vm.plan(hasLocation()) },
                    enabled = !state.planning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Planejar viagem") }
                if (state.planning) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(state.status.orEmpty())
                    }
                }
            }
            trip?.let { plan ->
                item { TripMap(plan) }
                item { SummaryCard(plan) }
                if (plan.fuelPlan.stops.isNotEmpty()) {
                    item { SectionTitle("Paradas para abastecer") }
                    items(plan.fuelPlan.stops, key = { "f${it.station.id}" }) { stop ->
                        ListRow(
                            icon = { Icon(Icons.Default.LocalGasStation, null, tint = Color(0xFF2E7D32)) },
                            title = stop.station.displayName,
                            subtitle = "${km(stop.station.distanceAlongM)} · chega com ${liters(stop.arrivalFuelL)} · " +
                                "abastecer ${liters(stop.litersToFill)} (${money(stop.estimatedCost)})" +
                                (if (stop.station.open24h) " · 24h" else ""),
                        )
                    }
                }
                item { SectionTitle("Radares no trajeto (${plan.radars.size})") }
                items(plan.radars, key = { "r${it.id}" }) { radar ->
                    ListRow(
                        icon = { Icon(Icons.Default.Warning, null, tint = Color(0xFFC62828)) },
                        title = radar.maxSpeedKmh?.let { "Radar · $it km/h" } ?: "Radar",
                        subtitle = km(radar.distanceAlongM),
                    )
                }
                item {
                    Text(
                        "Conecte o celular ao Android Auto para iniciar a navegação com alertas.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Atenção") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun PlaceField(label: String, field: PlaceFieldState, which: Field, vm: PlannerViewModel) {
    val selected = field.selected
    Column {
        OutlinedTextField(
            value = field.query,
            onValueChange = { vm.onQueryChange(which, it) },
            label = { Text(label) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Place, null) },
            trailingIcon = {
                if (field.searching) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { vm.search(which) }) { Icon(Icons.Default.Search, "Buscar") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(which) }),
            supportingText = if (selected != null) {
                { Text(selected.address, maxLines = 1) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        field.results.forEach { place ->
            Column(
                Modifier.fillMaxWidth().clickable { vm.select(which, place) }.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(place.name, fontWeight = FontWeight.SemiBold)
                Text(place.address, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun VehicleCard(form: VehicleForm, onChange: (VehicleForm) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Veículo e combustível", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            Text(
                "Tanque ${form.tankL} L · ${form.consumptionKmL} km/L · ${form.fuelPercent.toInt()}% no tanque",
                style = MaterialTheme.typography.bodySmall,
            )
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Tanque (L)", form.tankL, Modifier.weight(1f)) { onChange(form.copy(tankL = it)) }
                    NumberField("Consumo (km/L)", form.consumptionKmL, Modifier.weight(1f)) {
                        onChange(form.copy(consumptionKmL = it))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Preço (R$/L)", form.pricePerL, Modifier.weight(1f)) { onChange(form.copy(pricePerL = it)) }
                    NumberField("Desvio máx. (km)", form.maxDetourKm, Modifier.weight(1f)) {
                        onChange(form.copy(maxDetourKm = it))
                    }
                }
                Text("Combustível atual: ${form.fuelPercent.toInt()}%", Modifier.padding(top = 8.dp))
                Slider(form.fuelPercent, { onChange(form.copy(fuelPercent = it)) }, valueRange = 0f..100f)
                Text("Reserva de segurança: ${form.reservePercent.toInt()}%")
                Slider(form.reservePercent, { onChange(form.copy(reservePercent = it)) }, valueRange = 5f..40f)
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun TripMap(plan: TripPlan) {
    val cameraState = rememberCameraPositionState()
    val routePoints = remember(plan) { plan.route.points.map { it.toLatLng() } }
    val stopIds = remember(plan) { plan.fuelPlan.stops.map { it.station.id }.toSet() }
    var mapLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(plan, mapLoaded) {
        if (!mapLoaded || routePoints.isEmpty()) return@LaunchedEffect
        val bounds = LatLngBounds.builder().apply { routePoints.forEach { include(it) } }.build()
        runCatching { cameraState.move(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
    }

    Card(Modifier.fillMaxWidth().height(360.dp)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            properties = MapProperties(isTrafficEnabled = true),
            uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false),
            onMapLoaded = { mapLoaded = true },
        ) {
            Polyline(points = routePoints, color = Color(0xFF1565C0), width = 12f)
            Marker(state = remember(plan) { MarkerState(plan.origin.location.toLatLng()) }, title = plan.origin.name)
            Marker(
                state = remember(plan) { MarkerState(plan.destination.location.toLatLng()) },
                title = plan.destination.name,
            )
            plan.radars.forEach { radar ->
                Marker(
                    state = remember(radar.id, radar.location) { MarkerState(radar.location.toLatLng()) },
                    title = radar.maxSpeedKmh?.let { "Radar $it km/h" } ?: "Radar",
                    snippet = km(radar.distanceAlongM),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                )
            }
            plan.fuelStations.forEach { station ->
                val planned = station.id in stopIds
                Marker(
                    state = remember(station.id, station.location) { MarkerState(station.location.toLatLng()) },
                    title = station.displayName,
                    snippet = km(station.distanceAlongM) + if (planned) " · parada planejada" else "",
                    icon = BitmapDescriptorFactory.defaultMarker(
                        if (planned) BitmapDescriptorFactory.HUE_GREEN else BitmapDescriptorFactory.HUE_AZURE,
                    ),
                    alpha = if (planned) 1f else 0.6f,
                    zIndex = if (planned) 2f else 0f,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(plan: TripPlan) {
    val fuel = plan.fuelPlan
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (fuel.feasible) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${plan.origin.name} → ${plan.destination.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${Instructions.formatDistance(plan.route.distanceM)} · ${Instructions.formatDuration(plan.route.durationS)}" +
                    " · ${plan.radars.size} radares",
            )
            Text("Consumo estimado: ${liters(fuel.fuelConsumedL)} (${money(fuel.tripFuelCost)})")
            Text("Paradas: ${fuel.stops.size} · compra de ${liters(fuel.litersPurchased)} (${money(fuel.purchaseCost)})")
            Text("Chegada com ~${liters(fuel.arrivalFuelL)} no tanque")
            fuel.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ListRow(icon: @Composable () -> Unit, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
