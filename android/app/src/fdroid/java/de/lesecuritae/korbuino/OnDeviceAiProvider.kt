package de.lesecuritae.korbuino

import android.content.Context

/** The F-Droid build contains no Google libraries, so there is no on-device model; the rule-based assistant stays. */
object OnDeviceAiProvider {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): OnDeviceAi = NoOnDeviceAi
}
