package com.fiscon.viagem

import android.app.Application
import com.fiscon.viagem.data.TripRepository

class TravelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TripRepository.init(this)
    }
}
