package com.pira.ccloud.sync.transport

import com.pira.ccloud.data.model.sync.SyncRecord
import com.pira.ccloud.sync.SyncMerge
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * The sync server's behaviour, in memory: groups, devices, records merged with [SyncMerge] and
 * numbered per group, and single-use pairing codes in both directions. Used by the tests (several
 * engines can share one instance to act as several devices) and by debug builds until the real
 * server exists.
 *
 * With [simulatePeer], a join request is approved by a pretend phone a few seconds after it
 * starts, so the pairing screens can be tried on one device.
 */
class FakeSyncTransport(
    private val clock: () -> Long = System::currentTimeMillis,
    private val simulatePeer: Boolean = false,
    private val pairingLifetimeMs: Long = 5 * 60_000L
) : SyncTransport {
    private class Group(val id: String) {
        var seq = 0L
        val devices = LinkedHashMap<String, DeviceSummary>()
        val records = LinkedHashMap<Pair<String, String>, Pair<SyncRecord, Long>>()
    }

    private class Pairing(
        val ticket: PairingTicket,
        val device: DeviceInfo,
        /** The inviting group, or the group that approved a join request. */
        var groupId: String?,
        val createdAt: Long
    )

    private val lock = Any()
    private val groups = HashMap<String, Group>()
    private val tokens = HashMap<String, Pair<String, String>>() // access token -> group, device
    private val pairings = HashMap<String, Pairing>() // code -> pairing

    /** When true, every call fails as if the server were unreachable. */
    @Volatile
    var isOffline = false

    /** How long each [sync] response takes to arrive, like a slow network; tests use it to overlap calls. */
    @Volatile
    var syncLatencyMs = 0L

    /** Number of [sync] calls, for tests. */
    @Volatile
    var syncCalls = 0
        private set

    override suspend fun createGroup(device: DeviceInfo): Credentials = locked {
        val group = Group(UUID.randomUUID().toString())
        groups[group.id] = group
        join(group, device)
    }

    override suspend fun startPairing(device: DeviceInfo, credentials: Credentials?): PairingTicket = locked {
        val groupId = credentials?.let { member(it).id }
        var code: String
        do {
            code = PairingCodes.random()
        } while (code in pairings)
        val ticket = PairingTicket(
            pairingId = UUID.randomUUID().toString(),
            kind = if (groupId == null) PairingKind.REQUEST else PairingKind.INVITE,
            code = code,
            qrPayload = PairingCodes.qrPayload(code),
            expiresAt = clock() + pairingLifetimeMs,
            pollSecret = UUID.randomUUID().toString()
        )
        pairings[code] = Pairing(ticket, device, groupId, clock())
        ticket
    }

    override suspend fun pollPairing(ticket: PairingTicket): PairingStatus = locked {
        val pairing = pairings[ticket.code]
        if (pairing == null || pairing.ticket.pollSecret != ticket.pollSecret) {
            // An invite disappears once it's used
            return@locked if (ticket.kind == PairingKind.INVITE) PairingStatus.Done(null) else PairingStatus.Expired
        }
        if (clock() > pairing.ticket.expiresAt) {
            pairings.remove(ticket.code)
            return@locked PairingStatus.Expired
        }
        if (simulatePeer && ticket.kind == PairingKind.REQUEST && pairing.groupId == null &&
            clock() - pairing.createdAt >= SIMULATED_PEER_DELAY_MS
        ) {
            val peerGroup = Group(UUID.randomUUID().toString())
            groups[peerGroup.id] = peerGroup
            join(peerGroup, DeviceInfo("Simulated phone", "android"))
            pairing.groupId = peerGroup.id
        }
        val groupId = pairing.groupId
        if (ticket.kind == PairingKind.REQUEST && groupId != null) {
            pairings.remove(ticket.code)
            PairingStatus.Done(join(groups.getValue(groupId), pairing.device))
        } else {
            PairingStatus.Pending
        }
    }

    override suspend fun claim(code: String, device: DeviceInfo, credentials: Credentials?): ClaimResult = locked {
        val pairing = pairings[code]?.takeIf { clock() <= it.ticket.expiresAt }
            ?: throw SyncTransportException.InvalidCode()
        when {
            pairing.ticket.kind == PairingKind.REQUEST && credentials != null && pairing.groupId == null -> {
                pairing.groupId = member(credentials).id
                ClaimResult.Approved
            }
            pairing.ticket.kind == PairingKind.INVITE && credentials == null -> {
                pairings.remove(code)
                ClaimResult.Joined(join(groups.getValue(pairing.groupId!!), device))
            }
            else -> throw SyncTransportException.Conflict()
        }
    }

    override suspend fun sync(credentials: Credentials, cursor: Long?, changes: List<SyncRecord>): SyncResponse {
        val response = syncLocked(credentials, cursor, changes)
        // The server has handled the call; the response is still on its way
        if (syncLatencyMs > 0) delay(syncLatencyMs)
        return response
    }

    private fun syncLocked(credentials: Credentials, cursor: Long?, changes: List<SyncRecord>): SyncResponse = locked {
        syncCalls++
        val group = member(credentials)
        require(changes.size <= SyncTransport.MAX_UPLOAD) { "Too many changes" }
        val now = clock()
        group.devices[credentials.deviceId]?.let { group.devices[credentials.deviceId] = it.copy(lastSeen = now) }
        for (change in changes) {
            // The server keeps no device-local flags, and doesn't let a fast clock win forever
            val incoming = change.copy(dirty = false, t = minOf(change.t, now + CLOCK_TOLERANCE_MS))
            val key = incoming.c to incoming.k
            if (SyncMerge.wins(incoming, group.records[key]?.first)) {
                group.records[key] = incoming to ++group.seq
            }
        }
        val since = cursor ?: 0L
        val pending = group.records.values.filter { it.second > since }.sortedBy { it.second }
        val page = pending.take(PAGE_SIZE)
        SyncResponse(
            cursor = page.lastOrNull()?.second ?: maxOf(since, 0L),
            changes = page.map { it.first },
            more = pending.size > page.size,
            serverTime = now
        )
    }

    override suspend fun devices(credentials: Credentials): List<DeviceSummary> = locked {
        member(credentials).devices.values.map { it.copy(isSelf = it.id == credentials.deviceId) }
    }

    override suspend fun removeDevice(credentials: Credentials, deviceId: String): Unit = locked {
        val group = member(credentials)
        group.devices.remove(deviceId)
        tokens.entries.removeAll { it.value == group.id to deviceId }
        if (group.devices.isEmpty()) groups.remove(group.id)
    }

    override suspend fun deleteGroup(credentials: Credentials): Unit = locked {
        val group = member(credentials)
        groups.remove(group.id)
        tokens.entries.removeAll { it.value.first == group.id }
        pairings.values.removeAll { it.groupId == group.id }
    }

    /** The records the "server" holds for a group, for tests. */
    fun recordsOf(groupId: String): List<SyncRecord> = synchronized(lock) {
        groups[groupId]?.records?.values?.map { it.first }.orEmpty()
    }

    private fun join(group: Group, device: DeviceInfo): Credentials {
        val deviceId = UUID.randomUUID().toString()
        group.devices[deviceId] = DeviceSummary(deviceId, device.name, device.platform, clock(), isSelf = false)
        val token = UUID.randomUUID().toString()
        tokens[token] = group.id to deviceId
        return Credentials(group.id, deviceId, accessToken = token, refreshToken = token)
    }

    private fun member(credentials: Credentials): Group {
        val (groupId, deviceId) = tokens[credentials.accessToken] ?: throw SyncTransportException.Unauthorized()
        val group = groups[groupId] ?: throw SyncTransportException.Unauthorized()
        if (deviceId !in group.devices) throw SyncTransportException.Unauthorized()
        return group
    }

    private inline fun <T> locked(block: () -> T): T {
        if (isOffline) throw SyncTransportException.Unavailable()
        return synchronized(lock, block)
    }

    companion object {
        const val PAGE_SIZE = 500
        const val CLOCK_TOLERANCE_MS = 60_000L
        const val SIMULATED_PEER_DELAY_MS = 3_000L
    }
}
