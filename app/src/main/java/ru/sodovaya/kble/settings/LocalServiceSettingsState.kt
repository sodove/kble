package ru.sodovaya.kble.settings

import androidx.compose.runtime.compositionLocalOf

val LocalServiceSettingsState = compositionLocalOf<ServiceSettingsState> {
    error("ServiceSettingsState not provided")
}