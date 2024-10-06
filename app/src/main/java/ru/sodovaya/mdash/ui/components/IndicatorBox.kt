package ru.sodovaya.mdash.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate

/**
 * @author Anas Altair
 */
@Composable
internal fun SpeedometerScope.IndicatorBox(
    modifier: Modifier = Modifier,
    speed: Float,
    indicator: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.rotate(90f + degreeAtSpeed(speed)),
        contentAlignment = Alignment.Center,
    ) {
        indicator()
    }
}