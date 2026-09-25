package com.fiscon.viagem.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.core.graphics.drawable.IconCompat
import com.fiscon.viagem.R
import com.fiscon.viagem.core.model.ManeuverModifier
import com.fiscon.viagem.core.model.ManeuverModifier.LEFT
import com.fiscon.viagem.core.model.ManeuverModifier.RIGHT
import com.fiscon.viagem.core.model.ManeuverModifier.SHARP_LEFT
import com.fiscon.viagem.core.model.ManeuverModifier.SHARP_RIGHT
import com.fiscon.viagem.core.model.ManeuverModifier.SLIGHT_LEFT
import com.fiscon.viagem.core.model.ManeuverModifier.SLIGHT_RIGHT
import com.fiscon.viagem.core.model.ManeuverModifier.UTURN
import com.fiscon.viagem.core.model.ManeuverType
import com.fiscon.viagem.core.model.RouteStep

fun CarContext.icon(@DrawableRes res: Int): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).build()

/** Converte metros em [Distance] com a unidade adequada para o painel do carro. */
fun carDistance(meters: Double): Distance = when {
    meters < 1000 -> Distance.create((Math.round(meters / 10.0) * 10).toDouble(), Distance.UNIT_METERS)
    meters < 10_000 -> Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
    else -> Distance.create(Math.round(meters / 1000.0).toDouble(), Distance.UNIT_KILOMETERS)
}

object ManeuverMapper {
    fun toStep(context: CarContext, step: RouteStep): Step {
        val builder = Step.Builder(step.instruction).setManeuver(toManeuver(context, step))
        val road = step.roadName.ifBlank { step.roadRef }
        if (road.isNotBlank()) builder.setRoad(road)
        return builder.build()
    }

    fun toManeuver(context: CarContext, step: RouteStep): Maneuver {
        val m = step.modifier
        val left = m == LEFT || m == SLIGHT_LEFT || m == SHARP_LEFT
        val right = m == RIGHT || m == SLIGHT_RIGHT || m == SHARP_RIGHT
        // Brasil: mão de direção à direita -> rotatórias no sentido anti-horário (CCW).
        val type = when (step.type) {
            ManeuverType.DEPART -> Maneuver.TYPE_DEPART
            ManeuverType.ARRIVE -> when {
                left -> Maneuver.TYPE_DESTINATION_LEFT
                right -> Maneuver.TYPE_DESTINATION_RIGHT
                else -> Maneuver.TYPE_DESTINATION
            }
            ManeuverType.ROUNDABOUT, ManeuverType.ROTARY ->
                if (step.roundaboutExit != null) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
                else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            ManeuverType.EXIT_ROUNDABOUT -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
            ManeuverType.ON_RAMP -> when (m) {
                SLIGHT_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
                LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
                SHARP_LEFT -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
                SLIGHT_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
                RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
                SHARP_RIGHT -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
                else -> Maneuver.TYPE_STRAIGHT
            }
            ManeuverType.OFF_RAMP -> when (m) {
                SLIGHT_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
                LEFT, SHARP_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
                SLIGHT_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
                RIGHT, SHARP_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
                else -> Maneuver.TYPE_STRAIGHT
            }
            ManeuverType.FORK -> when {
                left -> Maneuver.TYPE_FORK_LEFT
                right -> Maneuver.TYPE_FORK_RIGHT
                else -> Maneuver.TYPE_STRAIGHT
            }
            ManeuverType.MERGE -> when {
                left -> Maneuver.TYPE_MERGE_LEFT
                right -> Maneuver.TYPE_MERGE_RIGHT
                else -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            }
            else -> turnType(m)
        }
        val builder = Maneuver.Builder(type).setIcon(context.icon(iconFor(step.type, m)))
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) {
            builder.setRoundaboutExitNumber(step.roundaboutExit!!.coerceAtLeast(1))
        }
        return builder.build()
    }

    private fun turnType(m: ManeuverModifier) = when (m) {
        UTURN -> Maneuver.TYPE_U_TURN_LEFT
        SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
        RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
        SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
        else -> Maneuver.TYPE_STRAIGHT
    }

    @DrawableRes
    private fun iconFor(type: ManeuverType, m: ManeuverModifier): Int = when {
        type == ManeuverType.ARRIVE -> R.drawable.ic_arrive
        type == ManeuverType.ROUNDABOUT || type == ManeuverType.ROTARY ||
            type == ManeuverType.EXIT_ROUNDABOUT -> R.drawable.ic_roundabout
        m == UTURN -> R.drawable.ic_uturn
        m == LEFT || m == SLIGHT_LEFT || m == SHARP_LEFT -> R.drawable.ic_turn_left
        m == RIGHT || m == SLIGHT_RIGHT || m == SHARP_RIGHT -> R.drawable.ic_turn_right
        else -> R.drawable.ic_straight
    }
}
