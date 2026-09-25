package com.fiscon.viagem.core.navigation

import com.fiscon.viagem.core.model.ManeuverModifier
import com.fiscon.viagem.core.model.ManeuverModifier.LEFT
import com.fiscon.viagem.core.model.ManeuverModifier.NONE
import com.fiscon.viagem.core.model.ManeuverModifier.RIGHT
import com.fiscon.viagem.core.model.ManeuverModifier.SHARP_LEFT
import com.fiscon.viagem.core.model.ManeuverModifier.SHARP_RIGHT
import com.fiscon.viagem.core.model.ManeuverModifier.SLIGHT_LEFT
import com.fiscon.viagem.core.model.ManeuverModifier.SLIGHT_RIGHT
import com.fiscon.viagem.core.model.ManeuverModifier.STRAIGHT
import com.fiscon.viagem.core.model.ManeuverModifier.UTURN
import com.fiscon.viagem.core.model.ManeuverType
import java.util.Locale

/** Gera instruções de navegação em português a partir das manobras do OSRM. */
object Instructions {
    fun format(type: ManeuverType, modifier: ManeuverModifier, exit: Int?, name: String, ref: String): String {
        val road = roadLabel(name, ref)
        val onto = road?.let { " para $it" }.orEmpty()
        val side = side(modifier)
        return when (type) {
            ManeuverType.DEPART -> road?.let { "Siga pela $it" } ?: "Inicie o percurso"
            ManeuverType.ARRIVE -> when (modifier) {
                LEFT, SLIGHT_LEFT, SHARP_LEFT -> "Destino à esquerda"
                RIGHT, SLIGHT_RIGHT, SHARP_RIGHT -> "Destino à direita"
                else -> "Você chegou ao destino"
            }
            ManeuverType.ROUNDABOUT, ManeuverType.ROTARY ->
                if (exit != null) "Na rotatória, pegue a ${exit}ª saída$onto" else "Entre na rotatória$onto"
            ManeuverType.EXIT_ROUNDABOUT -> "Saia da rotatória$onto"
            ManeuverType.MERGE -> "Entre na via${side?.let { " $it" }.orEmpty()}$onto"
            ManeuverType.ON_RAMP -> "Pegue o acesso${side?.let { " $it" }.orEmpty()}$onto"
            ManeuverType.OFF_RAMP -> "Pegue a saída${side?.let { " $it" }.orEmpty()}$onto"
            ManeuverType.FORK -> "Na bifurcação, mantenha-se ${side ?: "em frente"}$onto"
            ManeuverType.END_OF_ROAD -> "No fim da via, vire ${side ?: "conforme indicado"}$onto"
            ManeuverType.NEW_NAME, ManeuverType.CONTINUE, ManeuverType.NOTIFICATION ->
                when (modifier) {
                    UTURN -> "Faça o retorno$onto"
                    STRAIGHT, NONE -> road?.let { "Continue pela $it" } ?: "Continue em frente"
                    else -> turnText(modifier) + onto
                }
            ManeuverType.TURN, ManeuverType.UNKNOWN -> turnText(modifier) + onto
        }
    }

    private fun turnText(modifier: ManeuverModifier) = when (modifier) {
        UTURN -> "Faça o retorno"
        SHARP_RIGHT -> "Vire acentuadamente à direita"
        RIGHT -> "Vire à direita"
        SLIGHT_RIGHT -> "Mantenha-se à direita"
        STRAIGHT, NONE -> "Siga em frente"
        SLIGHT_LEFT -> "Mantenha-se à esquerda"
        LEFT -> "Vire à esquerda"
        SHARP_LEFT -> "Vire acentuadamente à esquerda"
    }

    private fun side(modifier: ManeuverModifier) = when (modifier) {
        LEFT, SLIGHT_LEFT, SHARP_LEFT -> "à esquerda"
        RIGHT, SLIGHT_RIGHT, SHARP_RIGHT -> "à direita"
        else -> null
    }

    private fun roadLabel(name: String, ref: String): String? = when {
        name.isNotBlank() && ref.isNotBlank() && !name.contains(ref) -> "$name ($ref)"
        name.isNotBlank() -> name
        ref.isNotBlank() -> ref
        else -> null
    }

    /** "850 m", "12 km", "1,5 km" */
    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${(Math.round(meters / 10.0) * 10)} m"
        meters < 10_000 -> String.format(Locale("pt", "BR"), "%.1f km", meters / 1000.0)
        else -> "${Math.round(meters / 1000.0)} km"
    }

    /** "2 h 15 min" */
    fun formatDuration(seconds: Double): String {
        val totalMin = Math.round(seconds / 60.0)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h ${m} min" else "$m min"
    }
}
