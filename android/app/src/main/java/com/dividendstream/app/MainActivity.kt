package com.dividendstream.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dividendstream.app.ui.navigation.DividendStreamRoot
import com.dividendstream.app.ui.theme.DividendStreamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DividendStreamTheme {
                DividendStreamRoot()
            }
        }
    }
}
