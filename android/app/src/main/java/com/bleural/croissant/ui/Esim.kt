package com.bleural.croissant.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bleural.croissant.esim.EsimProblem
import com.bleural.croissant.esim.EsimTask

/** The words a step goes by on the progress page and on the eSIM page's Adding row. */
fun EsimTask.Step.word() = when (this) {
    EsimTask.Step.Connecting -> "Connecting"
    EsimTask.Step.Authenticating -> "Authenticating"
    EsimTask.Step.Downloading -> "Downloading"
    EsimTask.Step.Installing -> "Installing"
    EsimTask.Step.Deleting -> "Deleting"
    EsimTask.Step.Switching -> "Switching"
    // "Restarting the SIM" would truncate: the In use row shares its width with the toggle.
    EsimTask.Step.Restarting -> "Restarting"
    EsimTask.Step.Renaming -> "Renaming"
}

internal fun EsimProblem.sentence() = when (this) {
    EsimProblem.NoSim -> "No SIM in the device."
    EsimProblem.NotReady -> "The SIM is not ready. It may be starting up or powered off."
    EsimProblem.NoRoot -> "Root was refused, so the card cannot be reached. The app needs its Magisk policy row."
    EsimProblem.NotEsim -> "The SIM card in the slot is not an eSIM card."
    EsimProblem.NoAnswer -> "The card did not answer."
}
