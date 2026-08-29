package com.dividendstream.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.dividendstream.app.core.ThemePreference
import com.dividendstream.app.ui.navigation.DividendStreamRoot
import com.dividendstream.app.ui.theme.DividendStreamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Read here rather than inside the tree: the scheme has to be decided before the
            // first frame, or the app opens in one theme and switches in front of the user.
            val settings = (application as DividendStreamApp).container.settingsStore
            val preference by settings.theme.collectAsState(initial = ThemePreference.System)

            DividendStreamTheme(darkTheme = preference.isDark(isSystemInDarkTheme())) {
                DividendStreamRoot()
            }
        }
    }
}
