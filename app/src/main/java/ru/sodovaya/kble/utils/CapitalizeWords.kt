package ru.sodovaya.kble.utils

fun String.CapitalizeWords(): String = split(" ").joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
