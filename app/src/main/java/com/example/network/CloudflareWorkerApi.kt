package com.example.network

import com.example.config.WorkerEndpointConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AppsScriptRpcRequestEnvelope(
    val action: String,
    val authToken: String,
    val timestamp: Long,
    val nonce: String,
    val requestId: String,
    val payload: Any = emptyMap<String, Any?>()
)

data class AppsScriptRpcResponseEnvelope(
    val ok: Boolean = false,
    val code: String = "UNKNOWN_ERROR",
    val message: String = "",
    val data: Any? = null,
    val requestId: String? = null,
    val serverTimestampIst: String? = null
)

data class AppsScriptConnectionTestDto(
    val appsScriptReachable: Boolean = false,
    val spreadsheetInitialized: Boolean = false,
    val sheetTabsReady: List<String> = emptyList(),
    val githubDispatchValid: Boolean = false,
    val githubDispatchIssues: List<String> = emptyList(),
    val githubDispatchTarget: String = "",
    val engineHeartbeatStatus: String = "AWAITING_FIRST_SYNC",
    val lastEngineSyncIst: String = "Never",
    val activeCycleLock: String = "NONE",
    val runState: String = "DRY_RUN",
    val dryRunMode: Boolean = true,
    val autoPublishEnabled: Boolean = false
)

data class WorkerStatusDto(
    val runState: String = "DRY_RUN",
    val authStatus: String = "OK",
    val lastSuccessfulRunIst: String = "",
    val nextScheduledRunIst: String = "",
    val candidatesDiscovered: Int = 0,
    val qualifyingPoolCount: Int = 0,
    val videosProcessed: Int = 0,
    val successfulPosts: Int = 0,
    val duplicateSkips: Int = 0,
    val errorsCount: Int = 0,
    val workflowConclusion: String = "success",
    val activeCycleLock: Boolean = false,
    val activeCycleLockOwnerId: String? = null
)

data class WorkerControlRequestDto(
    val command: String, // START_CYCLE | PAUSE | RESUME | DRY_RUN_CYCLE
    val dryRun: Boolean = true,
    val triggeredBy: String = "android_rilot_client",
    val timestampEpochMs: Long = System.currentTimeMillis()
)

data class WorkerMutationAckDto(
    val accepted: Boolean = true,
    val status: String = "OK",
    val targetReelPk: String? = null,
    val lockOwnerId: String? = null,
    val message: String = "",
    val workerTimestampIst: String = ""
)

data class DiscoveryConfigPayloadDto(
    val activeHashtags: List<String> = emptyList(),
    val candidateCollectionLimitPerTag: Int = 30,
    val qualifyingPoolSize: Int = 10,
    val contentSource: String = "RECENT_AND_TOP",
    val selectionStrategy: String = "QUALIFYING_POOL_WEIGHTED",
    val minViewCountThreshold: Long = 25000L,
    val minLikeToViewRatioPercent: Float = 3.2f,
    val recencyWeightPercent: Int = 35,
    val engagementWeightPercent: Int = 45,
    val diversityWeightPercent: Int = 20,
    val excludePreviouslyProcessed: Boolean = true,
    val preferYtdlpFallback: Boolean = true,
    val reuseAuthorizedByTag: Map<String, Boolean> = emptyMap(),
    val allowAllHashtagCandidatesWhenTargetAuthorized: Boolean = false,
    val allowAllHashtagCandidatesByTag: Map<String, Boolean> = emptyMap(),
    val authorizedCreators: List<String> = emptyList(),
    val ownedAccounts: List<String> = emptyList(),
    val authorizedCandidatePks: List<String> = emptyList()
)

data class ScheduleWindowDto(
    val id: String = "",
    val label: String = "",
    val startHourIst: Int = 9,
    val startMinuteIst: Int = 0,
    val endHourIst: Int = 21,
    val endMinuteIst: Int = 0,
    val isEnabled: Boolean = true
)

data class ScheduleConfigPayloadDto(
    val timezoneId: String = "Asia/Kolkata",
    val postIntervalHours: Int = 6,
    val maxPostsPerCycle: Int = 2,
    val dailyPostingLimit: Int = 6,
    val delayBetweenPostsSeconds: Int = 60,
    val dryRunMode: Boolean = true,
    val autoPublishEnabled: Boolean = false,
    val postingWindows: List<ScheduleWindowDto> = emptyList()
)

data class WorkerCaptionDto(
    val id: Long = 0L,
    val title: String = "",
    val body: String = "",
    val category: String = "VIRAL_COPYPASTA",
    val isAbsurdCopypasta: Boolean = false,
    val hashtagTemplate: String = "#reels #viral",
    val usageCount: Int = 0,
    val lastUsedEpochMs: Long? = null
)

data class CaptionsConfigPayloadDto(
    val captionRewriteMode: String = "FIXED_LIBRARY_RANDOM",
    val captions: List<WorkerCaptionDto> = emptyList()
)

data class WorkerActivityRecordDto(
    val reelPk: String = "",
    val shortCode: String = "",
    val reelUrl: String = "",
    val hashtag: String = "fitness",
    val category: String = "General",
    val creatorHandle: String = "@creator",
    val viewCount: Long = 0L,
    val likeCount: Long = 0L,
    val commentCount: Long = 0L,
    val durationSeconds: Int = 15,
    val qualityScore: Double = 0.0,
    val selectionReason: String = "",
    val downloaderUsed: String = "yt-dlp",
    val outcomeStatus: String = "QUALIFIED_QUEUED",
    val failureReason: String? = null,
    val captionPreview: String = "",
    val timestampIst: String = "",
    val timestampEpochMs: Long = 0L,
    val retryCount: Int = 0,
    val idempotencyToken: String = "",
    val reuseAuthorized: Boolean = false,
    val authorizationSource: String = "NONE"
)

data class WorkerActivityResponseDto(
    val records: List<WorkerActivityRecordDto> = emptyList()
)

data class SafeRetryRequestDto(
    val reelPk: String,
    val idempotencyToken: String,
    val verifyNotInPostedDb: Boolean = true,
    val dryRun: Boolean = true
)

/**
 * Backend API interface for the Rilot Google Apps Script Web App (`/exec`).
 * Keeps the `CloudflareWorkerApi` type name so existing ViewModels and tests compile without breaking changes,
 * while routing all calls as JSON RPC POST requests (`app.*` actions) to Google Apps Script.
 */
interface CloudflareWorkerApi {
    suspend fun testConnection(): Response<AppsScriptConnectionTestDto> =
        Response.success(
            AppsScriptConnectionTestDto(
                appsScriptReachable = true,
                spreadsheetInitialized = true,
                githubDispatchValid = true,
                githubDispatchTarget = "nexerpy-sys/rilot @ main (insta-automation-cycle.yml)"
            )
        )

    suspend fun getSystemStatus(): Response<WorkerStatusDto>

    suspend fun sendControlCommand(request: WorkerControlRequestDto): Response<WorkerMutationAckDto>

    suspend fun getDiscoveryConfig(): Response<DiscoveryConfigPayloadDto> =
        Response.error(404, "Not implemented in test stub".toResponseBody("text/plain".toMediaType()))

    suspend fun updateDiscoveryConfig(payload: DiscoveryConfigPayloadDto): Response<WorkerMutationAckDto>

    suspend fun getScheduleConfig(): Response<ScheduleConfigPayloadDto> =
        Response.error(404, "Not implemented in test stub".toResponseBody("text/plain".toMediaType()))

    suspend fun updateScheduleConfig(payload: ScheduleConfigPayloadDto): Response<WorkerMutationAckDto>

    suspend fun getCaptions(): Response<CaptionsConfigPayloadDto>

    suspend fun updateCaptions(payload: CaptionsConfigPayloadDto): Response<WorkerMutationAckDto>

    suspend fun getActivity(): Response<WorkerActivityResponseDto>

    suspend fun retryFailedCandidate(
        reelPk: String,
        request: SafeRetryRequestDto
    ): Response<WorkerMutationAckDto>
}

object CloudflareWorkerClientFactory {

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val requestEnvelopeAdapter = moshi.adapter(AppsScriptRpcRequestEnvelope::class.java)
    private val responseEnvelopeAdapter = moshi.adapter(AppsScriptRpcResponseEnvelope::class.java)

    fun buildSecurityHeaders(
        clientToken: String,
        timestampMs: Long = System.currentTimeMillis(),
        nonce: String = UUID.randomUUID().toString()
    ): Map<String, String> {
        val headers = mutableMapOf(
            "X-Rilot-Client" to "Android-Studio-Control-v1",
            "X-Rilot-Timezone" to "Asia/Kolkata",
            "X-Rilot-Timestamp" to timestampMs.toString(),
            "X-Rilot-Nonce" to nonce,
            "Accept" to "application/json",
            "Content-Type" to "application/json"
        )
        val trimmedToken = clientToken.trim()
        if (trimmedToken.isNotEmpty()) {
            headers["Authorization"] = "Bearer $trimmedToken"
        }
        return headers
    }

    fun buildRpcEnvelope(
        action: String,
        authToken: String,
        payload: Any = emptyMap<String, Any?>(),
        timestampMs: Long = System.currentTimeMillis(),
        nonce: String = "and_${timestampMs}_${UUID.randomUUID().toString().replace("-", "").take(16)}",
        requestId: String = "req_${action.replace(".", "_")}_$timestampMs"
    ): AppsScriptRpcRequestEnvelope {
        return AppsScriptRpcRequestEnvelope(
            action = action,
            authToken = authToken.trim(),
            timestamp = timestampMs,
            nonce = nonce,
            requestId = requestId,
            payload = payload
        )
    }

    fun <T> convertDataValue(rawData: Any?, targetClass: Class<T>): T? {
        if (rawData == null) return null
        return runCatching {
            moshi.adapter(targetClass).fromJsonValue(rawData)
        }.getOrNull()
    }

    fun create(
        config: WorkerEndpointConfig,
        customHttpClient: OkHttpClient? = null
    ): CloudflareWorkerApi {
        val okHttpClient = customHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        return object : CloudflareWorkerApi {

            private suspend fun <T> executeRpc(
                action: String,
                responseClass: Class<T>,
                payloadObj: Any = emptyMap<String, Any?>(),
                customDataDecoder: ((Any?, AppsScriptRpcResponseEnvelope) -> T)? = null
            ): Response<T> = withContext(Dispatchers.IO) {
                val targetUrl = config.normalizedBaseUrl()
                val envelope = buildRpcEnvelope(
                    action = action,
                    authToken = config.runtimeClientToken,
                    payload = payloadObj
                )
                val bodyJson = requestEnvelopeAdapter.toJson(envelope)
                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                buildSecurityHeaders(
                    clientToken = config.runtimeClientToken,
                    timestampMs = envelope.timestamp,
                    nonce = envelope.nonce
                ).forEach { (k, v) -> requestBuilder.header(k, v) }

                val rawBody: String
                val httpCode: Int
                try {
                    okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                        httpCode = resp.code
                        rawBody = resp.body?.string().orEmpty()
                    }
                } catch (e: Exception) {
                    val safeMsg = config.redactSecrets(e.message ?: "Apps Script Web App unreachable")
                    return@withContext Response.error(
                        503,
                        responseEnvelopeAdapter.toJson(
                            AppsScriptRpcResponseEnvelope(
                                ok = false,
                                code = "NETWORK_ERROR",
                                message = safeMsg
                            )
                        ).toResponseBody("application/json".toMediaType())
                    )
                }

                val parsedEnvelope = try {
                    val trimmed = rawBody.trim()
                    if (!trimmed.startsWith("{")) {
                        throw IllegalArgumentException("Not a JSON object")
                    }
                    responseEnvelopeAdapter.fromJson(trimmed)
                        ?: throw IllegalArgumentException("Empty JSON envelope")
                } catch (_: Exception) {
                    val errCode = if (httpCode in 200..299) 502 else httpCode.coerceAtLeast(400)
                    return@withContext Response.error(
                        errCode,
                        responseEnvelopeAdapter.toJson(
                            AppsScriptRpcResponseEnvelope(
                                ok = false,
                                code = "INVALID_JSON_RESPONSE",
                                message = "Apps Script returned non-JSON response (HTTP $httpCode). Ensure Web App access is set to Anyone."
                            )
                        ).toResponseBody("application/json".toMediaType())
                    )
                }

                if (!parsedEnvelope.ok) {
                    val statusCode = when (parsedEnvelope.code.uppercase()) {
                        "UNAUTHORIZED", "FORBIDDEN_ROLE", "REPLAY_DETECTED", "EXPIRED_TIMESTAMP" -> 401
                        "RATE_LIMITED" -> 429
                        "CYCLE_LOCKED", "CYCLE_ALREADY_IN_FLIGHT" -> 409
                        "NOT_FOUND" -> 404
                        else -> 400
                    }
                    val safeMsg = config.redactSecrets(parsedEnvelope.message)
                    val safeBody = responseEnvelopeAdapter.toJson(parsedEnvelope.copy(message = safeMsg))
                    return@withContext Response.error(
                        statusCode,
                        safeBody.toResponseBody("application/json".toMediaType())
                    )
                }

                val decoded: T = if (customDataDecoder != null) {
                    customDataDecoder(parsedEnvelope.data, parsedEnvelope)
                } else {
                    convertDataValue(parsedEnvelope.data, responseClass)
                        ?: throw IllegalStateException("Failed to decode RPC response data for $action")
                }
                Response.success(decoded)
            }

            override suspend fun testConnection(): Response<AppsScriptConnectionTestDto> {
                return executeRpc("app.connectionTest", AppsScriptConnectionTestDto::class.java) { rawData, _ ->
                    convertDataValue(rawData, AppsScriptConnectionTestDto::class.java)
                        ?: AppsScriptConnectionTestDto()
                }
            }

            override suspend fun getSystemStatus(): Response<WorkerStatusDto> {
                return executeRpc("app.getStatus", WorkerStatusDto::class.java) { rawData, _ ->
                    convertDataValue(rawData, WorkerStatusDto::class.java) ?: WorkerStatusDto()
                }
            }

            override suspend fun sendControlCommand(request: WorkerControlRequestDto): Response<WorkerMutationAckDto> {
                val normalizedCmd = when (request.command.uppercase()) {
                    "PAUSE" -> "PAUSE_AUTOMATION"
                    "RESUME" -> "RESUME_AUTOMATION"
                    else -> request.command.uppercase()
                }
                return executeRpc(
                    action = "app.setControl",
                    responseClass = WorkerMutationAckDto::class.java,
                    payloadObj = request.copy(command = normalizedCmd)
                ) { rawData, env ->
                    val ack = convertDataValue(rawData, WorkerMutationAckDto::class.java)
                        ?: WorkerMutationAckDto(accepted = true, message = env.message)
                    ack.copy(
                        message = ack.message.ifBlank { env.message },
                        workerTimestampIst = ack.workerTimestampIst.ifBlank { env.serverTimestampIst.orEmpty() }
                    )
                }
            }

            @Suppress("UNCHECKED_CAST")
            override suspend fun getDiscoveryConfig(): Response<DiscoveryConfigPayloadDto> {
                return executeRpc("app.getDiscoveryConfig", DiscoveryConfigPayloadDto::class.java) { rawData, _ ->
                    val mapData = (rawData as? Map<String, Any?>)?.let { outer ->
                        val inner = outer["discoveryConfig"] as? Map<String, Any?> ?: outer
                        val mutable = inner.toMutableMap()
                        if (!mutable.containsKey("activeHashtags") && mutable.containsKey("hashtags")) {
                            mutable["activeHashtags"] = mutable["hashtags"]
                        }
                        mutable
                    } ?: rawData
                    convertDataValue(mapData, DiscoveryConfigPayloadDto::class.java)
                        ?: DiscoveryConfigPayloadDto()
                }
            }

            override suspend fun updateDiscoveryConfig(payload: DiscoveryConfigPayloadDto): Response<WorkerMutationAckDto> {
                return executeRpc(
                    action = "app.updateDiscoveryConfig",
                    responseClass = WorkerMutationAckDto::class.java,
                    payloadObj = payload
                ) { rawData, env ->
                    val ack = convertDataValue(rawData, WorkerMutationAckDto::class.java)
                        ?: WorkerMutationAckDto(accepted = true, message = env.message)
                    ack.copy(
                        message = ack.message.ifBlank { env.message },
                        workerTimestampIst = ack.workerTimestampIst.ifBlank { env.serverTimestampIst.orEmpty() }
                    )
                }
            }

            @Suppress("UNCHECKED_CAST")
            override suspend fun getScheduleConfig(): Response<ScheduleConfigPayloadDto> {
                return executeRpc("app.getScheduleConfig", ScheduleConfigPayloadDto::class.java) { rawData, _ ->
                    val mapData = (rawData as? Map<String, Any?>)?.let { outer ->
                        outer["scheduleConfig"] as? Map<String, Any?> ?: outer
                    } ?: rawData
                    convertDataValue(mapData, ScheduleConfigPayloadDto::class.java)
                        ?: ScheduleConfigPayloadDto()
                }
            }

            override suspend fun updateScheduleConfig(payload: ScheduleConfigPayloadDto): Response<WorkerMutationAckDto> {
                return executeRpc(
                    action = "app.updateScheduleConfig",
                    responseClass = WorkerMutationAckDto::class.java,
                    payloadObj = payload
                ) { rawData, env ->
                    val ack = convertDataValue(rawData, WorkerMutationAckDto::class.java)
                        ?: WorkerMutationAckDto(accepted = true, message = env.message)
                    ack.copy(
                        message = ack.message.ifBlank { env.message },
                        workerTimestampIst = ack.workerTimestampIst.ifBlank { env.serverTimestampIst.orEmpty() }
                    )
                }
            }

            @Suppress("UNCHECKED_CAST")
            override suspend fun getCaptions(): Response<CaptionsConfigPayloadDto> {
                return executeRpc("app.getCaptions", CaptionsConfigPayloadDto::class.java) { rawData, _ ->
                    val mapData = rawData as? Map<String, Any?> ?: emptyMap()
                    val modeStr = (mapData["captionRewriteMode"] as? String)
                        ?: (mapData["mode"] as? String)
                        ?: "FIXED_LIBRARY_RANDOM"
                    val listType = Types.newParameterizedType(List::class.java, WorkerCaptionDto::class.java)
                    val listAdapter = moshi.adapter<List<WorkerCaptionDto>>(listType)
                    val caps = runCatching {
                        listAdapter.fromJsonValue(mapData["captions"])
                    }.getOrNull() ?: emptyList()
                    CaptionsConfigPayloadDto(captionRewriteMode = modeStr, captions = caps)
                }
            }

            override suspend fun updateCaptions(payload: CaptionsConfigPayloadDto): Response<WorkerMutationAckDto> {
                return executeRpc(
                    action = "app.updateCaptions",
                    responseClass = WorkerMutationAckDto::class.java,
                    payloadObj = payload
                ) { rawData, env ->
                    val ack = convertDataValue(rawData, WorkerMutationAckDto::class.java)
                        ?: WorkerMutationAckDto(accepted = true, message = env.message)
                    ack.copy(
                        message = ack.message.ifBlank { env.message },
                        workerTimestampIst = ack.workerTimestampIst.ifBlank { env.serverTimestampIst.orEmpty() }
                    )
                }
            }

            override suspend fun getActivity(): Response<WorkerActivityResponseDto> {
                return executeRpc("app.getActivity", WorkerActivityResponseDto::class.java) { rawData, _ ->
                    val listType = Types.newParameterizedType(List::class.java, WorkerActivityRecordDto::class.java)
                    val listAdapter = moshi.adapter<List<WorkerActivityRecordDto>>(listType)
                    when (rawData) {
                        is List<*> -> {
                            val records = runCatching { listAdapter.fromJsonValue(rawData) }.getOrNull() ?: emptyList()
                            WorkerActivityResponseDto(records = records)
                        }
                        else -> convertDataValue(rawData, WorkerActivityResponseDto::class.java)
                            ?: WorkerActivityResponseDto()
                    }
                }
            }

            override suspend fun retryFailedCandidate(
                reelPk: String,
                request: SafeRetryRequestDto
            ): Response<WorkerMutationAckDto> {
                return executeRpc(
                    action = "app.safeRetry",
                    responseClass = WorkerMutationAckDto::class.java,
                    payloadObj = request.copy(reelPk = reelPk)
                ) { rawData, env ->
                    val ack = convertDataValue(rawData, WorkerMutationAckDto::class.java)
                        ?: WorkerMutationAckDto(accepted = true, message = env.message)
                    ack.copy(
                        targetReelPk = ack.targetReelPk ?: reelPk,
                        message = ack.message.ifBlank { env.message },
                        workerTimestampIst = ack.workerTimestampIst.ifBlank { env.serverTimestampIst.orEmpty() }
                    )
                }
            }
        }
    }
}

typealias AppsScriptApi = CloudflareWorkerApi
typealias AppsScriptClientFactory = CloudflareWorkerClientFactory

