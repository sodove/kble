package ru.sodovaya.kble.service

enum class WakelockVariant {
    KEEP_SCREEN_ON,
    HIDDEN_ALLOWED_CPU,
    DISABLED
}

data class ServiceSettings(
    val voltageMin: Float = 39f,
    val voltageMax: Float = 55f,
    val amperageMin: Float = -20f,
    val amperageMax: Float = 40f,
    val powerMin: Float = -800f,
    val powerMax: Float = 2000f,
    val wheelDiameterInch: Float = 29f,
    val maximumVolumeAt: Float = 30f,
    val minimalVolume: Float = 5f,
    val volumeServiceEnabled: Boolean = false,
    val wakelockVariant: WakelockVariant = WakelockVariant.HIDDEN_ALLOWED_CPU
)
