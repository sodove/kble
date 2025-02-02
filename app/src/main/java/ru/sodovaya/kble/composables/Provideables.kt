package ru.sodovaya.kble.composables

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import ru.sodovaya.kble.service.ScooterData

val LocalScooterStatus: ProvidableCompositionLocal<ScooterData> =
    staticCompositionLocalOf { ScooterData() }