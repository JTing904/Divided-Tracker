package com.dividendstream.app.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface DividendStreamApi {

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("api/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): AuthResponse

    /** Whether Google sign-in is available, and the client ID the desktop flow needs. */
    @GET("api/auth/google/config")
    suspend fun googleConfig(): GoogleConfigDto

    @POST("api/auth/google")
    suspend fun googleSignIn(@Body request: GoogleSignInRequest): AuthResponse

    @POST("api/auth/google/desktop")
    suspend fun googleDesktopSignIn(@Body request: GoogleDesktopSignInRequest): AuthResponse

    @POST("api/auth/logout")
    suspend fun logout()

    @GET("api/user/profile")
    suspend fun profile(): UserProfileDto

    @PUT("api/user/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): UserProfileDto

    @GET("api/stocks/search")
    suspend fun searchStocks(@Query("query") query: String): List<StockSummaryDto>

    @GET("api/stocks/{symbol}")
    suspend fun stockDetail(@Path("symbol") symbol: String): StockDetailDto

    @GET("api/portfolio")
    suspend fun portfolio(): PortfolioDto

    @POST("api/portfolio")
    suspend fun addHolding(@Body request: CreateHoldingRequest): HoldingDto

    @PUT("api/portfolio/{id}")
    suspend fun updateHolding(@Path("id") id: String, @Body request: UpdateHoldingRequest): HoldingDto

    @DELETE("api/portfolio/{id}")
    suspend fun deleteHolding(@Path("id") id: String)

    /** The live snapshot. Poll-safe: the backend performs no writes to serve it. */
    @GET("api/dividends/live")
    suspend fun liveDividends(): LiveDividendDto

    @GET("api/dividends/upcoming")
    suspend fun upcomingDividends(): UpcomingDividendsDto

    @GET("api/dividends/history")
    suspend fun dividendHistory(): DividendHistoryDto

    /** Records the day a dividend actually arrived; only its holder can know it. */
    @POST("api/dividends/{id}/received")
    suspend fun confirmReceived(
        @Path("id") id: String,
        @Body request: ConfirmReceivedRequest,
    ): DividendDto

    @GET("api/dividends/{id}")
    suspend fun dividendDetail(@Path("id") id: String): DividendDto

    /**
     * The whole ledger in one call. Poll-safe for the same reason the dividend counter is:
     * the accruing figures are derived from stored parameters, so no write is performed.
     */
    @GET("api/ledger")
    suspend fun ledger(): LedgerDto

    @POST("api/ledger/flows")
    suspend fun saveCashFlow(@Body request: SaveCashFlowRequest): CashFlowDto

    @DELETE("api/ledger/flows/{id}")
    suspend fun deleteCashFlow(@Path("id") id: String)

    @POST("api/ledger/entries")
    suspend fun saveLedgerEntry(@Body request: SaveLedgerEntryRequest): LedgerEntryDto

    @DELETE("api/ledger/entries/{id}")
    suspend fun deleteLedgerEntry(@Path("id") id: String)

    @POST("api/ledger/funds")
    suspend fun saveFund(@Body request: SaveFundRequest): FundDto

    @DELETE("api/ledger/funds/{id}")
    suspend fun deleteFund(@Path("id") id: String)

    /**
     * Which build is serving, and which client release is current. Needs no session, and
     * touches no database -- so it answers even when the rest of the API cannot.
     */
    @GET("api/app/version")
    suspend fun appVersion(): AppVersionDto
}
