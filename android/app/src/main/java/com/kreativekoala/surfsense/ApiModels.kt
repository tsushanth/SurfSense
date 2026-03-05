package com.kreativekoala.surfsense

import com.google.gson.annotations.SerializedName

data class LinkDeviceRequest(
    @SerializedName("clientId")
    val clientId: String,
    @SerializedName("code")
    val code: String
)

data class LinkDeviceResponse(
    val success: Boolean,
    val extensionClientId: String?
)

data class ClientResponse(
    val success: Boolean,
    val client: ClientData
)

data class ClientData(
    val id: String
)

data class SummaryEntry(
    val timestamp: String,
    val userId: String,
    val summary: Map<String, Int>
)

data class Frequency(
    val unit: String,
    val value: Int
)

data class TrackJobResponse(
    val success: Boolean,
    val job: JobDetails,
    val recentRuns: List<JobRun>?
)

data class JobDetails(
    val id: String,
    val url: String,
    val userQuery: String,
    val format: String,
    val frequency: Frequency,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val nextRunAt: String
)

data class CreateJobDetals(
    val jobId: String,
    val status: String,
    val createdAt: String
)

data class JobRun(
    val documentId: String,
    val timestamp: String,
    val status: String
)

data class ResultResponse(
    val success: Boolean,
    val data: ResultData
)

data class CreateTrackerRequest(
    val url: String,
    val format: String,
    val frequency: Frequency,
    val clientType: String = "CHROME_EXTENSION",
    val clientId: String,
    val userQuery: String
)

data class LinkedClientsResponse(
    val success: Boolean,
    val data: LinkedClientsData
)

data class LinkedClientsData(
    val count: Int,
    val clients: List<ClientInfo>
)

data class ClientInfo(
    val clientId: String,
    val clientName: String,
    val clientType: String
)

data class ExtensionLookupResponse(
    val success: Boolean,
    val extensionClientIds: List<String>
)

data class CreateTrackerResponse(
    val success: Boolean,
    val job: CreateJobDetals
)

data class ClientInfoResponse(
    val success: Boolean,
    val data: Map<String, Any>? // Change to exact structure if known
)

data class LinkedDevicesResponse(
    val success: Boolean,
    val devices: List<String>
)

data class AppCategoryRequest(
    val packages: List<String>
)

data class UnlinkDeviceRequest(
    val clientIdA: String,
    val clientIdB: String
)


data class ResultData(
    val jobId: String,
    val clientId: String,
    val url: String,
    val userQuery: String,
    val responseFormat: String,
    val result: String,
    val status: String,
    val createdAt: Map<String, Any>,
    val updatedAt: Map<String, Any>
)

data class UsageReportRequest(
    val userId: String,
    val timestamp: String,
    val categorySummary: MutableMap<String, Int>
)

data class InitiateLinkingRequest(
    val clientId: String
)

data class InitiateLinkingResponse(
    val success: Boolean,
    val code: String?,
    val error: String?
)

data class RegisterClientRequest(
    val clientId: String,
    val clientType: String = Config.CLIENT_TYPE,
    val clientName: String
)

data class RegisterClientResponse(
    val success: Boolean,
    val apiKey: String?,
    val data: Map<String, Any>?
)

