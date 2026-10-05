package com.pira.ccloud.sync

import com.pira.ccloud.sync.transport.ClaimResult
import com.pira.ccloud.sync.transport.Credentials
import com.pira.ccloud.sync.transport.DeviceInfo
import com.pira.ccloud.sync.transport.DeviceSummary
import com.pira.ccloud.sync.transport.PairingStatus
import com.pira.ccloud.sync.transport.PairingTicket
import com.pira.ccloud.sync.transport.SyncConfig
import com.pira.ccloud.sync.transport.SyncTransport
import com.pira.ccloud.sync.transport.SyncTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** What this device keeps about its sync group between launches. */
@Serializable
data class SyncState(
    val credentials: Credentials,
    /** The last change received; null before the first sync. */
    val cursor: Long? = null,
    /** Upload the whole library on the next sync (after joining a group). */
    val needsFullUpload: Boolean = true,
    /** Unix ms of the last successful sync. */
    val lastSyncedAt: Long? = null
)

/** Keeps the [SyncState]; on Android it's encrypted, since it holds the group's tokens. */
interface SyncStateStore {
    fun load(): SyncState?

    /** Saves the state, or forgets it when [state] is null (not syncing). */
    fun save(state: SyncState?)
}

class InMemorySyncStateStore(private var state: SyncState? = null) : SyncStateStore {
    override fun load() = state
    override fun save(state: SyncState?) {
        this.state = state
    }
}

/**
 * The difference between the server's clock and this device's, so changes made here are stamped
 * in the server's time even when the device clock is wrong.
 */
class ClockOffset {
    @Volatile
    var offsetMs = 0L
}

sealed interface SyncStatus {
    /** This device isn't in a sync group. Everything stays local. */
    data object Off : SyncStatus

    data class On(
        val groupId: String,
        /** Unix ms of the last successful sync, or null before the first. */
        val lastSyncedAt: Long?,
        val isSyncing: Boolean = false,
        /** The last attempt couldn't reach the server; it retries by itself. */
        val isOffline: Boolean = false
    ) : SyncStatus
}

enum class ClaimOutcome {
    /** This device joined the group of the scanned invite. */
    JOINED,

    /** The other device joined this device's group. */
    APPROVED,

    /** The code is an invite into another group; joining it means leaving the current one. */
    NEEDS_SWITCH
}

/**
 * Keeps the local records and the sync group in step, automatically.
 *
 * Changes are pushed some time after they're made (favorites, playlists and watched marks after
 * [FAVORITES_DELAY_MS], settings after [SETTINGS_DELAY_MS], playback positions on pause or close
 * via [pushProgressSoon] or at the latest after the server's heartbeat interval). Every push also
 * pulls. Pulls also happen on start, when the app returns to the foreground, before playback
 * ([prepareToPlay]) and periodically while the app is in the foreground and nothing plays.
 * Failures retry with growing delays and never bother the user; losing membership (the device was
 * removed or the group deleted) turns sync off and keeps the local data.
 *
 * All its scheduling state is touched only from [scope], which must run one coroutine at a time
 * (a single-threaded dispatcher): the public functions hand their work to it, so they can be
 * called from any thread.
 */
class SyncEngine(
    private val records: RecordStore,
    private val transport: SyncTransport,
    private val stateStore: SyncStateStore,
    private val device: DeviceInfo,
    private val scope: CoroutineScope,
    private val clockOffset: ClockOffset = ClockOffset(),
    /** Wall-clock time in Unix ms. */
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default
) {
    private val statusFlow = MutableStateFlow(statusFor(stateStore.load()))
    val status: StateFlow<SyncStatus> = statusFlow.asStateFlow()

    private val syncLock = Mutex()
    private var config = SyncConfig()
    private val debounced = HashMap<String, Job>()
    private var progressHeartbeat: Job? = null
    private var retry: Job? = null
    private var polling: Job? = null
    private var failures = 0
    private var isForeground = true
    private var isPlaying = false
    private var started = false

    val isOn: Boolean get() = stateStore.load() != null

    /** Starts following local changes, and syncs if this device is in a group. Call once. */
    fun start() = onEngine {
        if (started) return@onEngine
        started = true
        scope.launch {
            records.localEdits.collect { collection -> onLocalEdit(collection) }
        }
        if (isOn) {
            syncSoon()
            restartPolling()
        }
    }

    // Lifecycle signals

    fun onForeground() = onEngine {
        isForeground = true
        val last = stateStore.load()?.lastSyncedAt
        if (isOn && (last == null || now() - last >= config.minSyncGapSec * 1000)) syncSoon()
        restartPolling()
    }

    /** Pushes what's waiting, so another device can continue from here. */
    fun onBackground() = onEngine {
        isForeground = false
        polling?.cancel()
        if (isOn && records.dirtyRecords().isNotEmpty()) syncSoon()
    }

    fun onConnectivityRegained() = onEngine {
        if (!isOn) return@onEngine
        retry?.cancel()
        failures = 0
        syncSoon()
    }

    fun setPlaying(playing: Boolean) = onEngine {
        isPlaying = playing
        restartPolling()
    }

    /** Playback paused or stopped: push the position shortly, for another device to resume. */
    fun pushProgressSoon() = onEngine {
        if (isOn) debounce(PROGRESS_PUSH, PROGRESS_PUSH_DELAY_MS)
    }

    /**
     * Pulls before playback starts, so a position saved on another device is offered. Waits at
     * most [timeoutMs]; returns at once when sync is off or a sync just happened.
     */
    suspend fun prepareToPlay(timeoutMs: Long = PREPARE_TIMEOUT_MS) {
        val state = stateStore.load() ?: return
        val last = state.lastSyncedAt
        if (last != null && now() - last < BEFORE_PLAY_GAP_MS) return
        val sync = scope.async { runSync() }
        withTimeoutOrNull(timeoutMs) { sync.await() }
    }

    // Group membership

    /** Creates a sync group with this device in it ("Turn on sync"). */
    suspend fun turnOn() {
        val credentials = transport.createGroup(device)
        onEngineAndWait { join(credentials) }
    }

    /** Shows this device's code: a join request when sync is off, an invite when it's on. */
    suspend fun startPairing(): PairingTicket = transport.startPairing(device, stateStore.load()?.credentials)

    /** Checks a shown code; joins the group when another device approved this one's request. */
    suspend fun pollPairing(ticket: PairingTicket): PairingStatus {
        val status = transport.pollPairing(ticket)
        if (status is PairingStatus.Done) status.credentials?.let { onEngineAndWait { join(it) } }
        return status
    }

    /** Uses a code shown by another device. */
    suspend fun claim(code: String): ClaimOutcome {
        val credentials = stateStore.load()?.credentials
        return try {
            when (val result = transport.claim(code, device, credentials)) {
                is ClaimResult.Joined -> {
                    onEngineAndWait { join(result.credentials) }
                    ClaimOutcome.JOINED
                }
                ClaimResult.Approved -> {
                    // The new device's data arrives with its first sync; fetch it soon after
                    onEngineAndWait { debounce(AFTER_APPROVAL, AFTER_APPROVAL_DELAY_MS) }
                    ClaimOutcome.APPROVED
                }
            }
        } catch (e: SyncTransportException.Conflict) {
            if (credentials != null) ClaimOutcome.NEEDS_SWITCH else throw e
        }
    }

    /**
     * Joins the group of [code] (an invite) instead of the current one, keeping local data. The
     * code is used first, so a code that fails leaves the device in its current group.
     */
    suspend fun switchGroup(code: String): ClaimOutcome {
        val old = stateStore.load()?.credentials ?: return claim(code)
        val joined = transport.claim(code, device, null) as? ClaimResult.Joined
            ?: throw SyncTransportException.InvalidCode()
        try {
            transport.removeDevice(old, old.deviceId)
        } catch (e: SyncTransportException) {
            // The old group forgets the device after a year without syncing
        }
        onEngineAndWait {
            turnOff()
            join(joined.credentials)
        }
        return ClaimOutcome.JOINED
    }

    suspend fun devices(): List<DeviceSummary> {
        val credentials = stateStore.load()?.credentials ?: return emptyList()
        return guard { transport.devices(credentials) }
    }

    suspend fun removeDevice(deviceId: String) {
        val credentials = stateStore.load()?.credentials ?: return
        if (deviceId == credentials.deviceId) return leave()
        guard { transport.removeDevice(credentials, deviceId) }
    }

    /** Stops syncing on this device ("Leave sync"). Local data stays. */
    suspend fun leave() {
        val credentials = stateStore.load()?.credentials ?: return
        try {
            transport.removeDevice(credentials, credentials.deviceId)
        } catch (e: SyncTransportException) {
            // Leave locally anyway; the server forgets the device after a year without syncing
        }
        onEngineAndWait { turnOff() }
    }

    /** Deletes the group and its data on the server for every device. Local data stays. */
    suspend fun deleteGroup() {
        val credentials = stateStore.load()?.credentials ?: return
        guard { transport.deleteGroup(credentials) }
        onEngineAndWait { turnOff() }
    }

    /** Syncs now and waits for it; false if it couldn't. */
    suspend fun syncNow(): Boolean = scope.async { runSync() }.await()

    // Scheduling

    private fun onLocalEdit(collection: String) {
        if (!isOn) return
        when (collection) {
            SyncCollection.PROGRESS -> {
                // Positions are saved every few seconds while playing; push them on pause or
                // close (pushProgressSoon), and at least once per heartbeat as crash insurance
                if (progressHeartbeat?.isActive != true) {
                    progressHeartbeat = scope.launch {
                        delay(config.progressHeartbeatSec * 1000)
                        runSync()
                    }
                }
            }
            SyncCollection.SETTING -> debounce(collection, SETTINGS_DELAY_MS)
            else -> debounce(collection, FAVORITES_DELAY_MS)
        }
    }

    private fun debounce(name: String, delayMs: Long) {
        debounced[name]?.cancel()
        debounced[name] = scope.launch {
            delay(delayMs)
            runSync()
        }
    }

    private fun syncSoon() {
        scope.launch { runSync() }
    }

    private fun restartPolling() {
        polling?.cancel()
        if (!isOn || !isForeground || isPlaying) return
        polling = scope.launch {
            while (isActive) {
                delay(config.foregroundPollSec * 1000)
                runSync()
            }
        }
    }

    private fun scheduleRetry() {
        val base = RETRY_DELAYS_MS[minOf(failures, RETRY_DELAYS_MS.lastIndex)]
        failures++
        val jittered = (base * (0.8 + 0.4 * random.nextDouble())).toLong()
        retry?.cancel()
        retry = scope.launch {
            delay(jittered)
            runSync()
        }
    }

    // Syncing

    private suspend fun runSync(): Boolean = syncLock.withLock {
        var state = stateStore.load() ?: return@withLock false
        setSyncing(true)
        try {
            if (state.needsFullUpload) records.markAllDirty()
            var rounds = 0
            do {
                val upload = records.dirtyRecords().take(SyncTransport.MAX_UPLOAD)
                val response = transport.sync(state.credentials, state.cursor, upload)
                // The device may have left or switched groups while the call was out; saving now
                // would bring the old membership back
                if (!isStillMember(state)) return@withLock false
                clockOffset.offsetMs = response.serverTime - now()
                records.markClean(upload)
                records.applyRemote(response.changes)
                config = response.config
                state = state.copy(cursor = response.cursor, needsFullUpload = false, lastSyncedAt = now())
                stateStore.save(state)
                rounds++
            } while ((response.more || upload.size == SyncTransport.MAX_UPLOAD) && rounds < MAX_ROUNDS)
            failures = 0
            retry?.cancel()
            statusFlow.value = SyncStatus.On(state.credentials.groupId, state.lastSyncedAt)
            true
        } catch (e: SyncTransportException.Unauthorized) {
            // Only if it's still the membership that was refused, not one joined since
            if (isStillMember(state)) turnOff()
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreachable server, or a storage error: try again later, without bothering the user
            if (!isStillMember(state)) return@withLock false
            statusFlow.update { (it as? SyncStatus.On)?.copy(isSyncing = false, isOffline = true) ?: it }
            scheduleRetry()
            false
        } finally {
            setSyncing(false)
        }
    }

    private fun isStillMember(state: SyncState) = stateStore.load()?.credentials == state.credentials

    private fun join(credentials: Credentials) {
        stateStore.save(SyncState(credentials))
        statusFlow.value = SyncStatus.On(credentials.groupId, lastSyncedAt = null)
        failures = 0
        syncSoon()
        restartPolling()
    }

    private fun turnOff() {
        stateStore.save(null)
        debounced.values.forEach { it.cancel() }
        debounced.clear()
        progressHeartbeat?.cancel()
        retry?.cancel()
        polling?.cancel()
        statusFlow.value = SyncStatus.Off
    }

    /** Runs a membership call; losing membership turns sync off before rethrowing. */
    private suspend fun <T> guard(block: suspend () -> T): T =
        try {
            block()
        } catch (e: SyncTransportException.Unauthorized) {
            onEngineAndWait { turnOff() }
            throw e
        }

    /** Runs [block] on the engine's scope. */
    private fun onEngine(block: () -> Unit) {
        scope.launch { block() }
    }

    private suspend fun onEngineAndWait(block: () -> Unit) {
        scope.launch { block() }.join()
    }

    private fun setSyncing(syncing: Boolean) {
        statusFlow.update { (it as? SyncStatus.On)?.copy(isSyncing = syncing) ?: it }
    }

    private fun statusFor(state: SyncState?): SyncStatus =
        state?.let { SyncStatus.On(it.credentials.groupId, it.lastSyncedAt) } ?: SyncStatus.Off

    companion object {
        const val FAVORITES_DELAY_MS = 3_000L
        const val SETTINGS_DELAY_MS = 10_000L
        const val PROGRESS_PUSH_DELAY_MS = 2_000L
        const val PREPARE_TIMEOUT_MS = 1_500L
        const val BEFORE_PLAY_GAP_MS = 30_000L
        const val AFTER_APPROVAL_DELAY_MS = 5_000L
        private const val MAX_ROUNDS = 100
        private const val PROGRESS_PUSH = "progress-push"
        private const val AFTER_APPROVAL = "after-approval"
        val RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 60_000L, 300_000L, 900_000L)
    }
}
