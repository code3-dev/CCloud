package com.pira.ccloud.sync.transport

import com.pira.ccloud.data.model.sync.SyncRecord
import kotlinx.serialization.Serializable

/**
 * The connection to the sync server (see the sync plan's protocol). The engine only knows this
 * interface; [FakeSyncTransport] implements it in memory until the real server exists.
 *
 * Failures are thrown as [SyncTransportException]s.
 */
interface SyncTransport {
    /** Creates a sync group with this device as its first member ("Turn on sync"). */
    suspend fun createGroup(device: DeviceInfo): Credentials

    /**
     * Starts pairing. Without [credentials] it's a [PairingKind.REQUEST] to join some group;
     * with them it's an [PairingKind.INVITE] into the caller's group.
     */
    suspend fun startPairing(device: DeviceInfo, credentials: Credentials?): PairingTicket

    /** Whether the other device has used the ticket's code yet. */
    suspend fun pollPairing(ticket: PairingTicket): PairingStatus

    /**
     * Uses a code shown by another device. With [credentials] it approves that device's join
     * request; without them it joins the group of an invite. Any other combination throws
     * [SyncTransportException.Conflict].
     */
    suspend fun claim(code: String, device: DeviceInfo, credentials: Credentials?): ClaimResult

    /**
     * Uploads [changes] (at most [MAX_UPLOAD]) and returns the group's records changed since
     * [cursor] (null for everything).
     */
    suspend fun sync(credentials: Credentials, cursor: Long?, changes: List<SyncRecord>): SyncResponse

    suspend fun devices(credentials: Credentials): List<DeviceSummary>

    /** Removes a device from the group; the last device leaving deletes the group. */
    suspend fun removeDevice(credentials: Credentials, deviceId: String)

    /** Deletes the group, its devices and all its records. */
    suspend fun deleteGroup(credentials: Credentials)

    companion object {
        const val MAX_UPLOAD = 200
    }
}

@Serializable
data class DeviceInfo(
    val name: String,
    /** "android", "android-tv", "ios", "tvos" or "macos". */
    val platform: String
)

/** What a device keeps to talk to its group. Secret: stored encrypted. */
@Serializable
data class Credentials(
    val groupId: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String
)

enum class PairingKind { REQUEST, INVITE }

data class PairingTicket(
    val pairingId: String,
    val kind: PairingKind,
    /** 8 characters; shown as `XXXX-XXXX`. */
    val code: String,
    /** What the QR code encodes. */
    val qrPayload: String,
    /** Unix ms. */
    val expiresAt: Long,
    val pollSecret: String
)

sealed interface PairingStatus {
    data object Pending : PairingStatus
    data object Expired : PairingStatus

    /** The code was used. For a join request, [credentials] are this device's way in. */
    data class Done(val credentials: Credentials?) : PairingStatus
}

sealed interface ClaimResult {
    /** This device joined an invite's group. */
    data class Joined(val credentials: Credentials) : ClaimResult

    /** This device let another device into its group. */
    data object Approved : ClaimResult
}

data class SyncResponse(
    val cursor: Long,
    val changes: List<SyncRecord>,
    /** More changes are waiting: sync again with [cursor]. */
    val more: Boolean,
    /** The server's clock (Unix ms), to correct this device's clock. */
    val serverTime: Long,
    val config: SyncConfig = SyncConfig()
)

/** Intervals the server can tune without an app release. */
data class SyncConfig(
    val progressHeartbeatSec: Long = 300,
    val foregroundPollSec: Long = 900,
    val minSyncGapSec: Long = 30
)

data class DeviceSummary(
    val id: String,
    val name: String,
    val platform: String,
    /** Unix ms. */
    val lastSeen: Long,
    val isSelf: Boolean
)

sealed class SyncTransportException(message: String) : Exception(message) {
    /** The device was removed or the group deleted: stop syncing, keep the local data. */
    class Unauthorized : SyncTransportException("Not a member of a sync group")

    /** The server can't be reached right now (offline, timeout, 429, 5xx). Retry later. */
    class Unavailable(message: String = "Sync server unavailable") : SyncTransportException(message)

    /** The code doesn't exist, expired or was used. */
    class InvalidCode : SyncTransportException("Unknown or expired code")

    /** The request doesn't fit the code (e.g. joining with an invite while in a group). */
    class Conflict : SyncTransportException("Code can't be used this way")

    /** Too many attempts; try again later. */
    class RateLimited : SyncTransportException("Too many attempts")
}

/** Pairing codes: 8 characters of Crockford base32, shown as `XXXX-XXXX`. */
object PairingCodes {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val QR_PREFIX = "ccloud://pair?code="

    fun random(random: java.util.Random = java.security.SecureRandom()): String =
        (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun format(code: String): String = if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code

    /**
     * The code in typed or scanned text: accepts `XXXX-XXXX`, lowercase, the confusable letters
     * Crockford maps (O→0, I/L→1) and a full `ccloud://pair?code=` link. Null if there's none.
     */
    fun normalize(text: String): String? {
        val raw = text.trim().substringAfter("code=", text.trim())
        val code = raw.uppercase()
            .filter { it.isLetterOrDigit() }
            .map {
                when (it) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> it
                }
            }
            .joinToString("")
        return code.takeIf { it.length == 8 && it.all { char -> char in ALPHABET } }
    }

    fun qrPayload(code: String) = QR_PREFIX + code
}
