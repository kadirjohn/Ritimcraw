package com.ritim.ritimcraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ritim.ritimcraw.ui.AppNav
import com.ritim.ritimcraw.ui.theme.RitimTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RitimTheme {
                AppNav()
            }
        }
    }
}
