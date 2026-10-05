package com.pira.ccloud.sync.transport

import com.pira.ccloud.data.model.sync.SyncRecord
import com.pira.ccloud.sync.SyncJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The sync server's HTTP API (`PROTOCOL.md` in the ccloud-sync repository; the Apple app's
 * `HTTPSyncTransport.swift` speaks the same). [baseUrls] end in `/v1` and are tried in order: a
 * host that can't be reached moves on to the next, and the last one that answered is tried first
 * next time.
 */
class HttpSyncTransport(
    private val baseUrls: List<String>,
    private val client: OkHttpClient = defaultClient
) : SyncTransport {
    init {
        require(baseUrls.isNotEmpty()) { "No sync server" }
    }

    @Volatile
    private var preferred = 0

    override suspend fun createGroup(device: DeviceInfo): Credentials {
        val text = request("POST", "/groups", null, encode(DeviceBody.serializer(), DeviceBody(device)))
        return decode(CredentialsBody.serializer(), text).credentials.toCredentials()
    }

    override suspend fun startPairing(device: DeviceInfo, credentials: Credentials?): PairingTicket {
        val text = request("POST", "/pairings", credentials, encode(DeviceBody.serializer(), DeviceBody(device)))
        val ticket = decode(TicketBody.serializer(), text)
        return PairingTicket(
            pairingId = ticket.pairingId,
            kind = if (ticket.kind == "invite") PairingKind.INVITE else PairingKind.REQUEST,
            code = ticket.code,
            qrPayload = PairingCodes.qrPayload(ticket.code),
            expiresAt = ticket.expiresAt,
            pollSecret = ticket.pollSecret
        )
    }

    override suspend fun pollPairing(ticket: PairingTicket): PairingStatus {
        val text = request("POST", "/pairings/${ticket.pairingId}/poll", null, encode(PollBody.serializer(), PollBody(ticket.pollSecret)))
        val poll = decode(PollResponse.serializer(), text)
        return when (poll.status) {
            "pending" -> PairingStatus.Pending
            "done" -> PairingStatus.Done(poll.credentials?.toCredentials())
            else -> PairingStatus.Expired
        }
    }

    override suspend fun claim(code: String, device: DeviceInfo, credentials: Credentials?): ClaimResult {
        val text = request("POST", "/pairings/claim", credentials, encode(ClaimBody.serializer(), ClaimBody(code, device)))
        val claim = decode(ClaimResponse.serializer(), text)
        val joined = claim.credentials
        return if (claim.result == "joined" && joined != null) ClaimResult.Joined(joined.toCredentials()) else ClaimResult.Approved
    }

    override suspend fun sync(credentials: Credentials, cursor: Long?, changes: List<SyncRecord>): SyncResponse {
        val body = SyncBody(cursor, changes.map { it.copy(dirty = false) })
        val response = decode(SyncResponseBody.serializer(), request("POST", "/sync", credentials, encode(SyncBody.serializer(), body)))
        return SyncResponse(
            cursor = response.cursor,
            // A record this app can't read is skipped, not the whole page
            changes = response.changes.mapNotNull { element ->
                runCatching { SyncJson.decodeFromJsonElement(SyncRecord.serializer(), element) }.getOrNull()
            },
            more = response.more,
            serverTime = response.serverTime,
            config = response.config?.let {
                SyncConfig(progressHeartbeatSec = it.progressHeartbeatSec, foregroundPollSec = it.foregroundPollSec, minSyncGapSec = it.minSyncGapSec)
            } ?: SyncConfig()
        )
    }

    override suspend fun devices(credentials: Credentials): List<DeviceSummary> =
        decode(DevicesBody.serializer(), request("GET", "/devices", credentials, null)).devices.map {
            DeviceSummary(it.id, it.name, it.platform, it.lastSeen, it.isSelf)
        }

    override suspend fun removeDevice(credentials: Credentials, deviceId: String) {
        request("DELETE", "/devices/$deviceId", credentials, null)
    }

    override suspend fun deleteGroup(credentials: Credentials) {
        request("DELETE", "/group", credentials, null)
    }

    // HTTP

    /** Sends a request and returns the answer's body, or throws a [SyncTransportException]. */
    private suspend fun request(method: String, path: String, credentials: Credentials?, json: String?): String =
        withContext(Dispatchers.IO) {
            val body = when {
                json != null -> json.toRequestBody(JSON)
                method == "POST" -> "{}".toRequestBody(JSON)
                else -> null
            }
            val first = preferred
            var lastFailure: IOException? = null
            for (offset in baseUrls.indices) {
                val index = (first + offset) % baseUrls.size
                val request = Request.Builder()
                    .url(baseUrls[index].trimEnd('/') + path)
                    .method(method, body)
                    .apply { credentials?.let { header("Authorization", "Bearer ${it.accessToken}") } }
                    .build()
                try {
                    client.newCall(request).execute().use { response ->
                        // The host answered, so its answer counts, whatever it is
                        preferred = index
                        if (!response.isSuccessful) throw errorFor(response.code)
                        return@withContext response.body?.string().orEmpty()
                    }
                } catch (e: IOException) {
                    lastFailure = e
                }
            }
            throw SyncTransportException.Unavailable(lastFailure?.message ?: "Sync server unreachable")
        }

    private fun errorFor(status: Int): SyncTransportException = when (status) {
        401 -> SyncTransportException.Unauthorized()
        404 -> SyncTransportException.InvalidCode()
        409 -> SyncTransportException.Conflict()
        429 -> SyncTransportException.RateLimited()
        else -> SyncTransportException.Unavailable("Sync server answered $status")
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = SyncJson.encodeToString(serializer, value)

    private fun <T> decode(serializer: KSerializer<T>, text: String): T =
        try {
            SyncJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            throw SyncTransportException.Unavailable("Unreadable answer from the sync server")
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Short timeouts: a sync that can't get through retries later on its own. */
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

// The protocol's JSON (see PROTOCOL.md)

@Serializable
private class DeviceBody(val device: DeviceInfo)

@Serializable
private class WireCredentials(val groupId: String, val deviceId: String, val accessToken: String) {
    // The server has no separate refresh token; the access token stays valid while the device is in the group
    fun toCredentials() = Credentials(groupId, deviceId, accessToken, refreshToken = accessToken)
}

@Serializable
private class CredentialsBody(val credentials: WireCredentials)

@Serializable
private class TicketBody(val pairingId: String, val kind: String, val code: String, val expiresAt: Long, val pollSecret: String)

@Serializable
private class PollBody(val pollSecret: String)

@Serializable
private class PollResponse(val status: String, val credentials: WireCredentials? = null)

@Serializable
private class ClaimBody(val code: String, val device: DeviceInfo)

@Serializable
private class ClaimResponse(val result: String, val credentials: WireCredentials? = null)

@Serializable
private class SyncBody(val cursor: Long?, val changes: List<SyncRecord>)

@Serializable
private class WireConfig(val progressHeartbeatSec: Long, val foregroundPollSec: Long, val minSyncGapSec: Long)

@Serializable
private class SyncResponseBody(
    val cursor: Long,
    val changes: JsonArray,
    val more: Boolean = false,
    val serverTime: Long,
    val config: WireConfig? = null
)

@Serializable
private class WireDevice(val id: String, val name: String, val platform: String, val lastSeen: Long, val isSelf: Boolean)

@Serializable
private class DevicesBody(val devices: List<WireDevice>)
