package com.dividendstream.app

import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.local.SessionStore
import com.dividendstream.app.data.local.SnapshotCache
import com.dividendstream.app.data.remote.NetworkModule
import com.dividendstream.app.data.repository.AppInfoRepository
import com.dividendstream.app.data.repository.AuthRepository
import com.dividendstream.app.data.repository.DividendRepository
import com.dividendstream.app.data.repository.PortfolioRepository

/**
 * Manual dependency container, desktop edition.
 *
 * Identical to the Android container apart from the two stores, which are file-backed here
 * instead of DataStore-backed. Everything below them — network module, repositories,
 * ViewModels — is the same code.
 */
class AppContainer {

    val serverClock = ServerClock()
    val sessionStore = SessionStore()

    private val network = NetworkModule(
        baseUrl = BuildConfig.API_BASE_URL,
        sessionStore = sessionStore,
        isDebug = BuildConfig.DEBUG,
    )

    private val snapshotCache = SnapshotCache(network.json)

    val authRepository = AuthRepository(network.api, sessionStore, snapshotCache, network.json)
    val portfolioRepository = PortfolioRepository(network.api, snapshotCache, network.json)
    val dividendRepository = DividendRepository(network.api, snapshotCache, serverClock, network.json)
    val appInfoRepository = AppInfoRepository(network.api, network.json, BuildConfig.VERSION_NAME)
}
