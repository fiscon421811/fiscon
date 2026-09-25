package com.fiscon.viagem.data

import android.content.Context
import com.fiscon.viagem.core.model.VehicleProfile

/** Guarda o perfil do veículo (tanque, consumo, preço...) em SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("vehicle", Context.MODE_PRIVATE)

    var vehicle: VehicleProfile
        get() {
            val d = VehicleProfile()
            return VehicleProfile(
                tankCapacityL = prefs.getFloat("tank", d.tankCapacityL.toFloat()).toDouble(),
                consumptionKmPerL = prefs.getFloat("consumption", d.consumptionKmPerL.toFloat()).toDouble(),
                currentFuelL = prefs.getFloat("current", d.currentFuelL.toFloat()).toDouble(),
                reserveFraction = prefs.getFloat("reserve", d.reserveFraction.toFloat()).toDouble(),
                fuelPricePerL = prefs.getFloat("price", d.fuelPricePerL.toFloat()).toDouble(),
                maxDetourM = prefs.getFloat("detour", d.maxDetourM.toFloat()).toDouble(),
            )
        }
        set(v) {
            prefs.edit()
                .putFloat("tank", v.tankCapacityL.toFloat())
                .putFloat("consumption", v.consumptionKmPerL.toFloat())
                .putFloat("current", v.currentFuelL.toFloat())
                .putFloat("reserve", v.reserveFraction.toFloat())
                .putFloat("price", v.fuelPricePerL.toFloat())
                .putFloat("detour", v.maxDetourM.toFloat())
                .apply()
        }
}
