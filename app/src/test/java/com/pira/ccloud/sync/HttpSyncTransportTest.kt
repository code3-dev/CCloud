package com.pira.ccloud.sync

import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.sync.SyncRecord
import com.pira.ccloud.sync.transport.Credentials
import com.pira.ccloud.sync.transport.DeviceInfo
import com.pira.ccloud.sync.transport.HttpSyncTransport
import com.pira.ccloud.sync.transport.PairingKind
import com.pira.ccloud.sync.transport.PairingStatus
import com.pira.ccloud.sync.transport.SyncTransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket

/**
 * The HTTP transport against a scripted server (always), and two engines against a real sync
 * server when `CCLOUD_SYNC_SERVER` names one (e.g. `http://127.0.0.1:8080/v1` from
 * `go run ./cmd/ccloud-sync -trust-proxy=false` in the ccloud-sync repository).
 */
class HttpSyncTransportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val device = DeviceInfo("Phone", "android")
    private val credentials = Credentials("g", "d", "token", "token")

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun transport(vararg urls: String) = HttpSyncTransport(urls.toList())

    private fun url() = server.url("/v1").toString()

    @Test
    fun statusCodesBecomeTheEnginesErrors() = runBlocking {
        val cases = mapOf(
            401 to SyncTransportException.Unauthorized::class,
            404 to SyncTransportException.InvalidCode::class,
            409 to SyncTransportException.Conflict::class,
            429 to SyncTransportException.RateLimited::class,
            500 to SyncTransportException.Unavailable::class,
            413 to SyncTransportException.Unavailable::class
        )
        for ((status, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"x"}"""))
            try {
                transport(url()).claim("ABCD2345", device, null)
                fail("$status didn't throw")
            } catch (e: SyncTransportException) {
                assertEquals("status $status", expected, e::class)
            }
        }
    }

    @Test
    fun requestsFollowTheProtocol() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"pairingId":"p1","kind":"invite","code":"7K3Q9M2X","expiresAt":5,"pollSecret":"s"}"""))
        val ticket = transport(url()).startPairing(device, credentials)
        assertEquals(PairingKind.INVITE, ticket.kind)
        assertEquals("ccloud://pair?code=7K3Q9M2X", ticket.qrPayload)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/pairings", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains(""""device":{"name":"Phone","platform":"android"}"""))

        server.enqueue(MockResponse().setBody("""{"status":"done","credentials":{"groupId":"g2","deviceId":"d2","accessToken":"t2"}}"""))
        val status = transport(url()).pollPairing(ticket)
        assertEquals(Credentials("g2", "d2", "t2", "t2"), (status as PairingStatus.Done).credentials)
        val poll = server.takeRequest()
        assertEquals("/v1/pairings/p1/poll", poll.path)
        assertNull("polls carry no token", poll.getHeader("Authorization"))
    }

    @Test
    fun syncSkipsRecordsItCantReadAndKeepsTheRest() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"cursor":3,"changes":[{"c":"favorite","k":"movie:1","v":{"a":1},"t":5,"dev":"x"},{"c":"favorite","k":"movie:2","v":7,"t":5,"dev":"x"}],
                   "more":true,"serverTime":99,"config":{"progressHeartbeatSec":60,"foregroundPollSec":120,"minSyncGapSec":10}}"""
            )
        )
        val upload = SyncRecord("watched", "1:1:1", null, 4, d = true, dev = "me", dirty = true)
        val response = transport(url()).sync(credentials, null, listOf(upload))
        assertEquals(listOf("movie:1"), response.changes.map { it.k })
        assertEquals(3L, response.cursor)
        assertTrue(response.more)
        assertEquals(60L, response.config.progressHeartbeatSec)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("the first sync has no cursor: $body", !body.contains("cursor"))
        assertTrue("dirty stays on the device: $body", body.contains(""""dirty":false"""))
    }

    @Test
    fun anUnreachableHostMovesOnToTheNext() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        server.enqueue(MockResponse().setBody("""{"devices":[{"id":"a","name":"TV","platform":"android-tv","lastSeen":1,"isSelf":true}]}"""))
        val transport = transport("http://127.0.0.1:$closedPort/v1", url())
        assertEquals("TV", transport.devices(credentials).single().name)

        // Nothing answers at all
        try {
            transport("http://127.0.0.1:$closedPort/v1").devices(credentials)
            fail("no host answered")
        } catch (e: SyncTransportException.Unavailable) {
            // Expected
        }
    }

    @Test
    fun anUnreadableAnswerIsTreatedAsUnavailable() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        try {
            transport(url()).createGroup(device)
            fail("parsed HTML")
        } catch (e: SyncTransportException.Unavailable) {
            // Expected: a hotel Wi-Fi login page, a proxy's error page...
        }
    }

    // Against a real server

    @OptIn(ExperimentalCoroutinesApi::class)
    private inner class LiveDevice(name: String, url: String) {
        val records = RecordStore(folder.newFolder(name), "device-$name")
        val library = LibraryStore(records)
        val state = InMemorySyncStateStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val engine = SyncEngine(records, HttpSyncTransport(listOf(url)), state, DeviceInfo(name, "android"), scope).also { it.start() }
    }

    private fun item(id: Int) = FavoriteItem(
        id = id, type = "movie", title = "Movie $id", description = "", year = 2024, imdb = 7.0, rating = 0.0,
        duration = null, image = "", cover = "", genres = emptyList(), country = emptyList(), sources = emptyList()
    )

    @Test
    fun twoDevicesSyncThroughARealServer() = runBlocking {
        val url = System.getenv("CCLOUD_SYNC_SERVER")
        assumeTrue("CCLOUD_SYNC_SERVER isn't set", !url.isNullOrBlank())
        val phone = LiveDevice("phone", url!!)
        val tv = LiveDevice("tv", url)
        try {
            phone.library.addFavorite(item(1))
            tv.library.addFavorite(item(2))
            phone.engine.turnOn()
            assertTrue(phone.engine.syncNow())

            // The TV shows a join request; the phone approves it
            val request = tv.engine.startPairing()
            assertEquals(PairingKind.REQUEST, request.kind)
            assertEquals(ClaimOutcome.APPROVED, phone.engine.claim(request.code))
            assertTrue(tv.engine.pollPairing(request) is PairingStatus.Done)
            assertTrue(tv.engine.syncNow())
            assertTrue(phone.engine.syncNow())
            assertEquals(setOf(1, 2), phone.library.favorites().map { it.id }.toSet())
            assertEquals(setOf(1, 2), tv.library.favorites().map { it.id }.toSet())

            // A removal travels too, and a removed device stops syncing
            phone.library.removeFavorite("movie", 1)
            assertTrue(phone.engine.syncNow())
            assertTrue(tv.engine.syncNow())
            assertEquals(setOf(2), tv.library.favorites().map { it.id }.toSet())
            val tvId = phone.engine.devices().single { !it.isSelf }.id
            phone.engine.removeDevice(tvId)
            assertEquals(false, tv.engine.syncNow())
            assertEquals(SyncStatus.Off, tv.engine.status.value)
            phone.engine.deleteGroup()
        } finally {
            phone.scope.cancel()
            tv.scope.cancel()
        }
    }
}
