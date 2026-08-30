package com.dividendstream.app

import android.app.Application
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.firestore.FirebaseSessionRepository
import com.dividendstream.app.data.firestore.FirestoreLedgerAdapter
import com.dividendstream.app.data.firestore.FirestoreLedgerRepository
import com.dividendstream.app.data.firestore.LedgerMigration
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
import com.dividendstream.app.data.repository.LedgerSource
import com.dividendstream.app.data.repository.LedgerQueue
import com.dividendstream.app.data.repository.PurchaseQueue
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /** Device preferences. Deliberately survives signing out; a theme is not account data. */
    val settingsStore = SettingsStore(application)

    private val network = NetworkModule(
        baseUrl = BuildConfig.API_BASE_URL,
        sessionStore = sessionStore,
        isDebug = BuildConfig.DEBUG,
    )

    private val snapshotCache = SnapshotCache(application, network.json)

    val authRepository = AuthRepository(network.api, sessionStore, snapshotCache, network.json)
    val portfolioRepository = PortfolioRepository(network.api, snapshotCache, network.json)
    val dividendRepository = DividendRepository(network.api, snapshotCache, serverClock, network.json)
    val ledgerRepository = LedgerRepository(network.api, snapshotCache, network.json)
    val appInfoRepository = AppInfoRepository(network.api, network.json, BuildConfig.VERSION_NAME)

    /** Whether the server is answering. Fed by real traffic; read by screens that need it up. */
    val serverAvailability = network.serverAvailability

    private val pendingPurchaseStore = PendingPurchaseStore(application, network.json)

    /**
     * Purchases live here between being entered and being accepted. Its own scope, because it
     * has to outlive whichever screen queued the purchase -- the point is that the person can
     * leave.
     */
    val purchaseQueue = PurchaseQueue(
        store = pendingPurchaseStore,
        portfolioRepository = portfolioRepository,
        availability = serverAvailability,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val pendingLedgerStore = PendingLedgerStore(application, network.json)

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

    // --- Firebase ------------------------------------------------------------
    //
    // The ledger's home now. Firestore keeps a copy on the device, answers from it before any
    // network is involved and sends changes when it can, which is what takes the wait out of
    // opening the app -- there is nothing here that has to wake up first.
    //
    // The portfolio and dividends still go through the API above, because working out what a
    // holding is expected to pay means fetching prices, and fetching prices needs a server.
    private val firebaseAuth: FirebaseAuth by lazy { Firebase.auth }
    private val firestore: FirebaseFirestore by lazy { Firebase.firestore }

    val firebaseSession by lazy { FirebaseSessionRepository(firebaseAuth, firestore) }

    val ledgerSource: LedgerSource by lazy {
        FirestoreLedgerAdapter(
            repository = FirestoreLedgerRepository(firestore, firebaseSession, serverClock),
            migration = LedgerMigration(ledgerRepository, firestore, firebaseSession),
        )
    }
}

class DividendStreamApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
