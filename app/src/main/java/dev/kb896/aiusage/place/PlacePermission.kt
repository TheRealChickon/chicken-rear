package dev.kb896.aiusage.place

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * WHAT A PLACE NOTE NEEDS FROM THE PHONE, as it really is now (SPEC R6). All of it is his to give; the app only asks,
 * at the first use, with the system's own screens: precise first (the system's question), then "all the time" (which
 * Android gives only on the app's location page), and Location itself switched on. "Only this time" is not given.
 */
object PlacePermission {
    fun fine(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun always(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun coarse(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun locationOn(context: Context): Boolean = runCatching { context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled }.getOrDefault(false)

    /** What is missing first, in the order he is asked for it. */
    enum class Need { NOTHING, PRECISE, ALWAYS, LOCATION_OFF }

    fun need(fine: Boolean, always: Boolean, on: Boolean): Need = when {
        !fine -> Need.PRECISE
        !always -> Need.ALWAYS
        !on -> Need.LOCATION_OFF
        else -> Need.NOTHING
    }

    fun need(context: Context): Need = need(fine(context), always(context), locationOn(context))

    /** The prompt's button for [need] (Google's own accuracy switch is read by the prompt itself, [ACCURACY_WORD]). */
    fun word(need: Need): String = when (need) {
        Need.NOTHING -> "Pin"
        Need.PRECISE, Need.ALWAYS -> "Allow location"
        Need.LOCATION_OFF -> "Turn on location"
    }

    const val ACCURACY_WORD = "Turn on location accuracy"

    /** The settings' one line on it. */
    fun state(fine: Boolean, always: Boolean, coarse: Boolean, on: Boolean): String = when {
        !fine && coarse -> "Approximate only: precise is needed"
        !fine -> "Not allowed yet"
        !always -> "Precise, while in use: \"all the time\" is needed"
        !on -> "Allowed, but Location is off"
        else -> "Precise, all the time"
    }
}
