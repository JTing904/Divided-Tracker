package com.dividendstream.app

import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.local.PendingLedgerStore
import com.dividendstream.app.data.local.PendingPurchaseStore
import com.dividendstream.app.data.local.SessionStore
import com.dividendstream.app.data.local.SettingsStore
import com.dividendstream.app.data.local.SnapshotCache
import com.dividendstream.app.data.remote.NetworkModule
import com.dividendstream.app.data.repository.AppInfoRepository
import com.dividendstream.app.data.repository.AuthRepository
import com.dividendstream.app.data.repository.DividendRepository
import com.dividendstream.app.data.repository.LedgerRepository
import com.dividendstream.app.data.repository.PortfolioRepository
import com.dividendstream.app.data.repository.ApiLedgerSource
import com.dividendstream.app.data.repository.LedgerSource
import com.dividendstream.app.data.repository.LedgerQueue
import com.dividendstream.app.data.repository.PurchaseQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /** Device preferences. Deliberately survives signing out; a theme is not account data. */
    val settingsStore = SettingsStore()

    private val network = NetworkModule(
        baseUrl = BuildConfig.API_BASE_URL,
        sessionStore = sessionStore,
        isDebug = BuildConfig.DEBUG,
    )

    private val snapshotCache = SnapshotCache(network.json)

    val authRepository = AuthRepository(network.api, sessionStore, snapshotCache, network.json)
    val portfolioRepository = PortfolioRepository(network.api, snapshotCache, network.json)
    val dividendRepository = DividendRepository(network.api, snapshotCache, serverClock, network.json)
    val ledgerRepository = LedgerRepository(network.api, snapshotCache, network.json)
    val appInfoRepository = AppInfoRepository(network.api, network.json, BuildConfig.VERSION_NAME)

    /** Whether the server is answering. Fed by real traffic; read by screens that need it up. */
    val serverAvailability = network.serverAvailability

    private val pendingPurchaseStore = PendingPurchaseStore(network.json)

    /** See the Android container: the queue outlives the screen that filled it. */
    val purchaseQueue = PurchaseQueue(
        store = pendingPurchaseStore,
        portfolioRepository = portfolioRepository,
        availability = serverAvailability,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val pendingLedgerStore = PendingLedgerStore(network.json)

    /**
     * Ledger changes live here between being entered and being accepted. Its own scope, like
     * the purchase queue and for the same reason: writing down a lunch should not tie a person
     * to the screen they wrote it on.
     */
    val ledgerQueue = LedgerQueue(
        store = pendingLedgerStore,
        ledgerRepository = ledgerRepository,
        availability = serverAvailability,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /**
     * Still the HTTP API, because the Firebase SDK the phone uses is Android-only.
     *
     * Deliberately unchanged rather than half-moved: a desktop on this path keeps its saved
     * copy, its stale banner and its queue exactly as they were, so nothing regresses for the
     * client that has not moved yet.
     */
    val ledgerSource: LedgerSource = ApiLedgerSource(ledgerRepository, ledgerQueue)
}
