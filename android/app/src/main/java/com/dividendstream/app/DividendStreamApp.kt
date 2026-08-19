package com.dividendstream.app

import android.app.Application
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.local.SessionStore
import com.dividendstream.app.data.local.SnapshotCache
import com.dividendstream.app.data.remote.NetworkModule
import com.dividendstream.app.data.repository.AppInfoRepository
import com.dividendstream.app.data.repository.AuthRepository
import com.dividendstream.app.data.repository.DividendRepository
import com.dividendstream.app.data.repository.PortfolioRepository

/**
 * Manual dependency container.
 *
 * A graph this size does not need a DI framework: everything is a singleton created once at
 * startup, and constructor injection keeps every ViewModel testable with plain fakes. If the
 * graph grows scopes or generated bindings become worthwhile, this is the single place a
 * framework would replace.
 */
class AppContainer(application: Application) {

    val serverClock = ServerClock()
    val sessionStore = SessionStore(application)

    private val network = NetworkModule(
        baseUrl = BuildConfig.API_BASE_URL,
        sessionStore = sessionStore,
        isDebug = BuildConfig.DEBUG,
    )

    private val snapshotCache = SnapshotCache(application, network.json)

    val authRepository = AuthRepository(network.api, sessionStore, snapshotCache, network.json)
    val portfolioRepository = PortfolioRepository(network.api, snapshotCache, network.json)
    val dividendRepository = DividendRepository(network.api, snapshotCache, serverClock, network.json)
    val appInfoRepository = AppInfoRepository(network.api, network.json, BuildConfig.VERSION_NAME)

    /** Whether the server is answering. Fed by real traffic; read by screens that need it up. */
    val serverAvailability = network.serverAvailability
}

class DividendStreamApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
