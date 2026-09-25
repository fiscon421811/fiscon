package com.fiscon.viagem.core

import com.fiscon.viagem.core.geo.PolylineCodec
import com.fiscon.viagem.core.model.GeoPoint

/** Rota sintética: linha reta para o leste, ~1,11 km por 0,01° de longitude no equador. */
object TestData {
    fun straightLine(km: Int): List<GeoPoint> = (0..km).map { GeoPoint(0.0, it * 0.0089932) }

    fun osrmResponse(points: List<GeoPoint>, durationS: Double = 3600.0): String {
        val geometry = PolylineCodec.encode(points, 6).replace("\\", "\\\\")
        val first = points.first()
        val mid = points[points.size / 2]
        val last = points.last()
        val total = (points.size - 1) * 1000.0
        return """
        {"code":"Ok","routes":[{"geometry":"$geometry","distance":$total,"duration":$durationS,
          "legs":[{"steps":[
            {"distance":${total / 2},"duration":${durationS / 2},"name":"Rodovia A","ref":"BR-101",
             "maneuver":{"type":"depart","location":[${first.lon},${first.lat}]}},
            {"distance":${total / 2},"duration":${durationS / 2},"name":"Avenida B",
             "maneuver":{"type":"turn","modifier":"right","location":[${mid.lon},${mid.lat}]}},
            {"distance":0,"duration":0,"name":"Avenida B",
             "maneuver":{"type":"arrive","location":[${last.lon},${last.lat}]}}
          ]}]}]}
        """.trimIndent()
    }
}
