package com.kreativekoala.surfsense

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST("complete-linking")
    suspend fun linkDevice(@Body request: LinkDeviceRequest): Response<LinkDeviceResponse>

    @POST("register-client")
    suspend fun registerClient(@Body request: RegisterClientRequest): Response<RegisterClientResponse>

    @GET("client/{id}")
    suspend fun getClient(@Path("id") clientId: String): Response<ClientResponse>

    @GET("client/{id}")
    suspend fun getClientInfo(
        @Path("id") clientId: String,
        @Query("clientName") clientName: String,
        @Query("clientType") clientType: String = Config.CLIENT_TYPE
    ): Response<ClientInfoResponse>

    @POST("get-category-mapping")
    suspend fun getAppCategoryMapping(@Body request: AppCategoryRequest): Response<Map<String, String>>

    @POST("unlink-device")
    suspend fun unlinkDevice(@Body request: UnlinkDeviceRequest): Response<Unit>

    @GET("get-linked-clients")
    suspend fun getLinkedClients(
        @Query("clientId") clientId: String
    ): Response<LinkedClientsResponse>

    @GET("get-summary-history")
    suspend fun getSummaryHistory(
        @Query("userId") userId: String,
        @Query("day") day: String
    ): Response<List<SummaryEntry>>

    @POST("submit-category-summary")
    suspend fun submitUsageReport(@Body request: UsageReportRequest): Response<Unit>

    @POST("initiate-linking")
    suspend fun initiateLinking(@Body request: InitiateLinkingRequest): Response<InitiateLinkingResponse>
}