package com.wipwn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wipwn.app.ui.WipwnMainScreen
import com.wipwn.app.ui.theme.WipwnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WipwnTheme {
                WipwnMainScreen()
            }
        }
    }
}
