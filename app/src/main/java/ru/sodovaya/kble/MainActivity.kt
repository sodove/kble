package ru.sodovaya.kble

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.FadeTransition
import ru.sodovaya.kble.settings.ServiceSettingsProvider
import ru.sodovaya.kble.ui.screens.ConnectionScreen
import ru.sodovaya.kble.ui.theme.MtelemetryTheme

class MainActivity : ComponentActivity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ServiceSettingsProvider(this) {
                MtelemetryTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Column(
                            modifier = Modifier.padding(innerPadding)
                                .consumeWindowInsets(innerPadding)
                        ) {
                            Navigator(
                                ConnectionScreen()
                            ) {
                                FadeTransition(it)
                            }
                        }
                    }
                }
            }
        }
    }
}
