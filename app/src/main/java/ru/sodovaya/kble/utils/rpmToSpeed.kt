package ru.sodovaya.kble.utils

import ru.sodovaya.kble.settings.ServiceSettingsPreferences

fun rpmToSpeed(rpm: Int): Double {
    val diameterInInches = ServiceSettingsPreferences.spref
        ?.getFloat("wheelDiameterInch", 29f) ?: 29f // dirty hack with prefs
    val diameterInMeters = diameterInInches * 0.0254
    val circumference = Math.PI * diameterInMeters
    val speedInKmh = rpm * circumference * 60 / 1000
    return speedInKmh
}