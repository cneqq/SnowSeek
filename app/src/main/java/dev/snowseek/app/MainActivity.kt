package dev.snowseek.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.snowseek.app.ui.SnowSeekApp
import dev.snowseek.app.ui.SnowSeekViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: SnowSeekViewModel = viewModel()
            SnowSeekApp(model)
        }
    }
}
