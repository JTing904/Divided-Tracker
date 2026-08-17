package com.dividendstream.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.dividendstream.app.AppContainer
import com.dividendstream.app.DesktopSettings
import com.dividendstream.app.ui.LocalAppContainer
import com.dividendstream.app.ui.navigation.DesktopRoot
import com.dividendstream.app.ui.theme.DividendStreamTheme
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/**
 * The desktop application.
 *
 * Everything runs in this one process: PostgreSQL, the Spring Boot API and the Compose UI.
 * The UI still talks to the API over HTTP on loopback rather than calling the services
 * directly, because that keeps a single definition of every rule — the backend stays the
 * source of truth, and the Android app and this one exercise identical endpoints.
 */
fun main() = application {
    var backend by remember { mutableStateOf<BackendState>(BackendState.Starting) }

    LaunchedEffect(Unit) {
        // Pointed at a shared server: nothing to start here, and starting a second database
        // would silently give this machine its own private copy of the data.
        if (!DesktopSettings.runsOwnBackend) {
            backend = BackendState.Ready
            return@LaunchedEffect
        }

        backend = withContext(Dispatchers.IO) {
            runCatching {
                BackendBootstrap.start()
                // Boot returns once the context is refreshed, but the connector may still be
                // finishing; poll until an actual request is answered.
                repeat(TOTAL_ATTEMPTS) {
                    if (BackendBootstrap.isUp()) return@runCatching BackendState.Ready
                    delay(POLL_INTERVAL_MILLIS)
                }
                BackendState.Failed("The local service did not start in time.")
            }.getOrElse { error ->
                BackendState.Failed(error.message ?: error::class.simpleName.orEmpty())
            }
        }
    }

    val windowState = rememberWindowState(
        size = DpSize(1180.dp, 820.dp),
        position = WindowPosition(Alignment.Center),
    )

    Window(
        onCloseRequest = {
            // The embedded PostgreSQL runs as a child process. Closing the Spring context
            // stops it; leaving it running would hold postmaster.pid and break the next
            // launch, so this happens before the JVM goes away.
            if (DesktopSettings.runsOwnBackend) BackendBootstrap.stop()
            exitApplication()
            exitProcess(0)
        },
        state = windowState,
        title = "Dividend Stream",
    ) {
        DividendStreamTheme {
            when (val state = backend) {
                BackendState.Ready -> {
                    val container = remember { AppContainer() }
                    CompositionLocalProvider(LocalAppContainer provides container) {
                        DesktopRoot()
                    }
                }

                BackendState.Starting -> StatusScreen(
                    heading = "Starting Dividend Stream",
                    detail = "Preparing your local database. The first launch takes a little longer.",
                    busy = true,
                )

                is BackendState.Failed -> StatusScreen(
                    heading = "Could not start",
                    detail = state.message,
                    busy = false,
                )
            }
        }
    }
}

private const val POLL_INTERVAL_MILLIS = 250L
private const val TOTAL_ATTEMPTS = 480 // two minutes; initdb on a cold first run is slow

@Composable
private fun StatusScreen(heading: String, detail: String, busy: Boolean) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Dividend Stream",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.height(28.dp)) {}
            Text(
                heading,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Column(Modifier.height(10.dp)) {}
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 460.dp),
            )
            if (busy) {
                Column(Modifier.height(28.dp)) {}
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
