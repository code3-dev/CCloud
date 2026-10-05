package com.pira.ccloud.sync

import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.WatchedEpisode
import com.pira.ccloud.sync.transport.DeviceInfo
import com.pira.ccloud.sync.transport.FakeSyncTransport
import com.pira.ccloud.sync.transport.PairingCodes
import com.pira.ccloud.sync.transport.PairingKind
import com.pira.ccloud.sync.transport.PairingStatus
import com.pira.ccloud.sync.transport.SyncTransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val base = 1_700_000_000_000L

    /** One simulated device: its own storage and engine, sharing the server with the others. */
    private inner class Device(name: String, scope: TestScope, server: FakeSyncTransport) {
        val offset = ClockOffset()
        val records = RecordStore(folder.newFolder(name), "device-$name") { scope.testScheduler.currentTime + base + offset.offsetMs }
        val library = LibraryStore(records)
        val state = InMemorySyncStateStore()
        val engine = SyncEngine(
            records = records,
            transport = server,
            stateStore = state,
            device = DeviceInfo(name, "android"),
            scope = scope.backgroundScope,
            clockOffset = offset,
            now = { scope.testScheduler.currentTime + base },
            random = Random(1)
        ).also { it.start() }
    }

    private fun item(id: Int) = FavoriteItem(
        id = id, type = "movie", title = "Movie $id", description = "", year = 2024, imdb = 7.0, rating = 0.0,
        duration = null, image = "", cover = "", genres = emptyList(), country = emptyList(), sources = emptyList()
    )

    private fun TestScope.server() = FakeSyncTransport(clock = { testScheduler.currentTime + base })

    @Test
    fun aJoinedDeviceGetsTheGroupsLibraryAndKeepsItsOwn() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        val tv = Device("tv", this, server)
        runCurrent()
        phone.library.addFavorite(item(1))
        tv.library.addFavorite(item(2)) // made before joining: must not be lost

        phone.engine.turnOn()
        advanceTimeBy(1_000)
        // The phone shows an invite; the TV can't scan, so this is the TV showing a join request
        val ticket = tv.engine.startPairing()
        assertEquals(PairingKind.REQUEST, ticket.kind)
        assertEquals(com.pira.ccloud.sync.ClaimOutcome.APPROVED, phone.engine.claim(ticket.code))
        assertTrue(tv.engine.pollPairing(ticket) is PairingStatus.Done)
        advanceTimeBy(1_000)
        runCurrent()

        // Within a few seconds the phone fetches what the TV brought
        advanceTimeBy(SyncEngine.AFTER_APPROVAL_DELAY_MS + 1_000)
        runCurrent()
        assertEquals(setOf(1, 2), tv.library.favorites().map { it.id }.toSet())
        assertEquals(setOf(1, 2), phone.library.favorites().map { it.id }.toSet())
    }

    @Test
    fun invitesWorkTheOtherWayRound() = runTest {
        val server = server()
        val tv = Device("tv", this, server)
        val phone = Device("phone", this, server)
        runCurrent()
        tv.engine.turnOn()
        tv.library.addFavorite(item(5))
        advanceTimeBy(SyncEngine.FAVORITES_DELAY_MS + 100)

        val invite = tv.engine.startPairing()
        assertEquals(PairingKind.INVITE, invite.kind)
        assertEquals(ClaimOutcome.JOINED, phone.engine.claim(PairingCodes.normalize(PairingCodes.format(invite.code).lowercase())!!))
        runCurrent()
        assertEquals(listOf(5), phone.library.favorites().map { it.id })
        // An invite is single use
        try {
            Device("third", this, server).engine.claim(invite.code)
            fail("A used invite must not work again")
        } catch (e: SyncTransportException.InvalidCode) {
        }
    }

    @Test
    fun changesArePushedAfterTheirDelay() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        runCurrent()
        phone.engine.turnOn()
        runCurrent()
        val groupId = phone.state.load()!!.credentials.groupId
        val callsAfterJoin = server.syncCalls

        phone.library.addFavorite(item(1))
        advanceTimeBy(2_000)
        phone.library.addFavorite(item(2)) // restarts the wait
        advanceTimeBy(2_000)
        assertEquals("nothing pushed while changes keep coming", callsAfterJoin, server.syncCalls)
        advanceTimeBy(1_500)
        assertEquals(callsAfterJoin + 1, server.syncCalls)
        assertEquals(2, server.recordsOf(groupId).count { it.c == SyncCollection.FAVORITE })
        assertTrue("pushed records are clean", phone.records.dirtyRecords().isEmpty())

        // Settings wait longer
        phone.library.setSetting(SyncSetting.FONT, JsonPrimitive("vazirmatn"), JsonPrimitive("system"))
        advanceTimeBy(SyncEngine.FAVORITES_DELAY_MS + 100)
        assertEquals(callsAfterJoin + 1, server.syncCalls)
        advanceTimeBy(SyncEngine.SETTINGS_DELAY_MS)
        assertEquals(callsAfterJoin + 2, server.syncCalls)
    }

    @Test
    fun positionsArePushedOnPauseOrByTheHeartbeat() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        runCurrent()
        phone.engine.turnOn()
        runCurrent()
        val calls = server.syncCalls

        // Saved every 10 s while playing: not pushed each time
        repeat(5) {
            phone.library.recordProgress(SyncKeys.movieProgress(7), 60_000L + it * 10_000, 6_000_000)
            advanceTimeBy(10_000)
        }
        assertEquals(calls, server.syncCalls)

        // Pausing pushes shortly after
        phone.engine.pushProgressSoon()
        advanceTimeBy(SyncEngine.PROGRESS_PUSH_DELAY_MS + 100)
        assertEquals(calls + 1, server.syncCalls)

        // Without a pause, the heartbeat pushes eventually
        phone.library.recordProgress(SyncKeys.movieProgress(7), 200_000, 6_000_000)
        advanceTimeBy(300_000 + 100)
        assertTrue(server.syncCalls >= calls + 2)
        assertTrue(phone.records.dirtyRecords().isEmpty())
    }

    @Test
    fun aRemovedDeviceStopsSyncingAndKeepsItsData() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        val tv = Device("tv", this, server)
        runCurrent()
        phone.engine.turnOn()
        val invite = phone.engine.startPairing()
        tv.engine.claim(invite.code)
        tv.library.markWatched(WatchedEpisode(1, 2, 3, watchedAt = 9))
        runCurrent()

        val tvId = tv.state.load()!!.credentials.deviceId
        phone.engine.removeDevice(tvId)
        assertFalse(tv.engine.syncNow())
        assertEquals(SyncStatus.Off, tv.engine.status.value)
        assertNull(tv.state.load())
        assertTrue(tv.library.isWatched(1, 2, 3))
    }

    @Test
    fun anUnreachableServerIsRetriedWithGrowingDelays() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        runCurrent()
        phone.engine.turnOn()
        runCurrent()
        server.isOffline = true
        phone.library.addFavorite(item(1))
        advanceTimeBy(SyncEngine.FAVORITES_DELAY_MS + 100)
        assertTrue((phone.engine.status.value as SyncStatus.On).isOffline)

        server.isOffline = false
        // The first retry comes after about 5 s (± 20 %)
        advanceTimeBy(6_500)
        assertTrue(phone.records.dirtyRecords().isEmpty())
        assertFalse((phone.engine.status.value as SyncStatus.On).isOffline)
    }

    @Test
    fun regainedConnectivityRetriesAtOnce() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        runCurrent()
        phone.engine.turnOn()
        runCurrent()
        server.isOffline = true
        phone.library.addFavorite(item(1))
        advanceTimeBy(SyncEngine.FAVORITES_DELAY_MS + 100)
        server.isOffline = false
        phone.engine.onConnectivityRegained()
        runCurrent()
        assertTrue(phone.records.dirtyRecords().isEmpty())
    }

    @Test
    fun theServerClockIsUsedForLocalChanges() = runTest {
        // The server's clock is a day ahead of this device's
        val day = 86_400_000L
        val server = FakeSyncTransport(clock = { testScheduler.currentTime + base + day })
        val phone = Device("phone", this, server)
        runCurrent()
        phone.engine.turnOn()
        runCurrent()
        phone.library.addFavorite(item(1))
        val stamped = phone.records.get(SyncCollection.FAVORITE, "movie:1")!!.t
        assertTrue(stamped >= base + day)
    }

    @Test
    fun joiningAnotherGroupNeedsASwitch() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        val tv = Device("tv", this, server)
        runCurrent()
        phone.engine.turnOn()
        tv.engine.turnOn()
        tv.library.addFavorite(item(3))
        runCurrent()
        val invite = phone.engine.startPairing()
        assertEquals(ClaimOutcome.NEEDS_SWITCH, tv.engine.claim(invite.code))
        assertEquals(ClaimOutcome.JOINED, tv.engine.switchGroup(invite.code))
        runCurrent()
        assertEquals(phone.state.load()!!.credentials.groupId, tv.state.load()!!.credentials.groupId)
        assertTrue(tv.library.isFavorite("movie", 3))
        // The TV's library had already been uploaded to its old group; it's uploaded again
        // to the new one, so the phone gets it too
        phone.engine.syncNow()
        assertTrue(phone.library.isFavorite("movie", 3))
    }

    @Test
    fun switchingWithAnExpiredCodeKeepsTheCurrentGroup() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        val tv = Device("tv", this, server)
        runCurrent()
        phone.engine.turnOn()
        tv.engine.turnOn()
        runCurrent()
        val tvGroup = tv.state.load()!!.credentials.groupId
        val invite = phone.engine.startPairing()
        advanceTimeBy(5 * 60_000L + 1_000)
        try {
            tv.engine.switchGroup(invite.code)
            fail("An expired code should be refused")
        } catch (e: SyncTransportException.InvalidCode) {
            // Expected
        }
        assertEquals(tvGroup, tv.state.load()?.credentials?.groupId)
        assertTrue(tv.engine.status.value is SyncStatus.On)
    }

    @Test
    fun leavingDuringASyncStaysOff() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        runCurrent()
        phone.engine.turnOn()
        runCurrent()
        server.syncLatencyMs = 2_000
        // A sync before playback is waiting for the server's answer...
        val sync = async { phone.engine.syncNow() }
        advanceTimeBy(500)
        assertTrue((phone.engine.status.value as SyncStatus.On).isSyncing)
        // ...when the user leaves; the answer mustn't bring the membership back
        phone.engine.leave()
        advanceTimeBy(5_000)
        assertFalse(sync.await())
        assertNull(phone.state.load())
        assertEquals(SyncStatus.Off, phone.engine.status.value)
    }

    @Test
    fun switchingDuringASyncKeepsTheNewGroup() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        val tv = Device("tv", this, server)
        runCurrent()
        phone.engine.turnOn()
        tv.engine.turnOn()
        runCurrent()
        val invite = phone.engine.startPairing()
        tv.library.addFavorite(item(1))
        server.syncLatencyMs = 2_000
        val sync = async { tv.engine.syncNow() }
        advanceTimeBy(500)
        assertTrue((tv.engine.status.value as SyncStatus.On).isSyncing)
        assertEquals(ClaimOutcome.JOINED, tv.engine.switchGroup(invite.code))
        advanceTimeBy(10_000)
        assertFalse(sync.await())
        assertEquals(phone.state.load()!!.credentials.groupId, tv.state.load()!!.credentials.groupId)
        server.syncLatencyMs = 0
        phone.engine.syncNow()
        assertTrue(phone.library.isFavorite("movie", 1))
    }

    @Test
    fun pairingCodesExpire() = runTest {
        val server = server()
        val tv = Device("tv", this, server)
        runCurrent()
        val ticket = tv.engine.startPairing()
        advanceTimeBy(5 * 60_000L + 1_000)
        assertEquals(PairingStatus.Expired, tv.engine.pollPairing(ticket))
    }

    @Test
    fun prepareToPlayPullsAPositionFromAnotherDevice() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        val tv = Device("tv", this, server)
        runCurrent()
        phone.engine.turnOn()
        tv.engine.claim(phone.engine.startPairing().code)
        runCurrent()
        advanceTimeBy(60_000)

        phone.library.recordProgress(SyncKeys.movieProgress(9), 2_530_000, 6_000_000)
        phone.engine.pushProgressSoon()
        advanceTimeBy(SyncEngine.PROGRESS_PUSH_DELAY_MS + 100)

        tv.engine.prepareToPlay()
        assertEquals(2_530_000L, ProgressPolicy.resumePosition(tv.library.progress(SyncKeys.movieProgress(9))))
    }

    @Test
    fun prepareToPlayReturnsAtOnceWhenSyncIsOff() = runTest {
        val phone = Device("phone", this, server())
        val before = testScheduler.currentTime
        phone.engine.prepareToPlay()
        assertEquals(before, testScheduler.currentTime)
    }

    @Test
    fun largeLibrariesArePagedBothWays() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        val tv = Device("tv", this, server)
        runCurrent()
        repeat(700) { phone.library.markWatched(WatchedEpisode(1, 1, it, watchedAt = it.toLong())) }
        phone.engine.turnOn()
        runCurrent()
        tv.engine.claim(phone.engine.startPairing().code)
        runCurrent()
        assertEquals(700, tv.library.watchedEpisodes().size)
    }

    @Test
    fun leavingKeepsDataAndStopsSyncing() = runTest {
        val server = server()
        val phone = Device("phone", this, server)
        runCurrent()
        phone.engine.turnOn()
        phone.library.addFavorite(item(1))
        runCurrent()
        phone.engine.leave()
        assertEquals(SyncStatus.Off, phone.engine.status.value)
        assertTrue(phone.library.isFavorite("movie", 1))
        val calls = server.syncCalls
        phone.library.addFavorite(item(2))
        advanceTimeBy(60_000)
        assertEquals(calls, server.syncCalls)
    }

    @Test
    fun codesAreNormalized() {
        assertEquals("ABCD1234", PairingCodes.normalize("abcd-1234"))
        assertEquals("ABCD1234", PairingCodes.normalize("ccloud://pair?code=ABCD1234"))
        assertEquals("0BCD1234", PairingCodes.normalize("obcd-l234".replace("l", "1")))
        assertNull(PairingCodes.normalize("ABC"))
        assertEquals("ABCD-1234", PairingCodes.format("ABCD1234"))
    }
}
