package ru.sodovaya.kble.utils

fun Int.ToHumanReadableGear(): String {
    return when (this) {
        0 -> "Eco"
        1 -> "Drive"
        2 -> "Sport"
        else -> "unk"
    }
}