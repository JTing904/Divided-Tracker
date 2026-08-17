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

    @GET("api/dividends/{id}")
    suspend fun dividendDetail(@Path("id") id: String): DividendDto
}
