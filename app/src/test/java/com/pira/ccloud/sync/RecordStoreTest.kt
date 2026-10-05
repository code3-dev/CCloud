package com.pira.ccloud.sync

import com.pira.ccloud.data.model.sync.SyncRecord
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000L
    private fun store(dir: File = folder.root, device: String = "device-a") = RecordStore(dir, device) { now }
    private val value = buildJsonObject { put("x", 1) }

    @Test
    fun changesSurviveReload() {
        store().edit { put(SyncCollection.FAVORITE, "movie:1", value) }
        val reloaded = store()
        val record = reloaded.get(SyncCollection.FAVORITE, "movie:1")!!
        assertEquals(value, record.v)
        assertEquals(1_000L, record.t)
        assertEquals("device-a", record.dev)
        assertTrue(record.dirty)
    }

    @Test
    fun localChangesAlwaysGetALaterTimestamp() {
        val store = store()
        store.applyRemote(listOf(SyncRecord("favorite", "movie:1", value, t = 5_000, dev = "b")))
        // The clock is behind the remote change, but the local change must still win locally
        store.edit { delete(SyncCollection.FAVORITE, "movie:1") }
        val record = store.get(SyncCollection.FAVORITE, "movie:1")!!
        assertTrue(record.d)
        assertEquals(5_001L, record.t)
    }

    @Test
    fun deleteLeavesATombstone() {
        val store = store()
        store.edit { put(SyncCollection.WATCHED, "1:2:3", value) }
        now = 2_000
        assertTrue(store.edit { delete(SyncCollection.WATCHED, "1:2:3") })
        assertNull(store.value(SyncCollection.WATCHED, "1:2:3"))
        assertTrue(store.live(SyncCollection.WATCHED).isEmpty())
        assertEquals(1, store.all(SyncCollection.WATCHED).size)
        // Deleting again does nothing
        assertFalse(store.edit { delete(SyncCollection.WATCHED, "1:2:3") })
    }

    @Test
    fun remoteRecordsFollowTheMergeRule() {
        val store = store()
        store.edit { put(SyncCollection.SETTING, "font", value) }
        val older = SyncRecord("setting", "font", buildJsonObject { put("value", JsonPrimitive("system")) }, t = 500, dev = "b")
        val newer = older.copy(t = 1_500)
        assertEquals(0, store.applyRemote(listOf(older)))
        assertEquals(1, store.applyRemote(listOf(newer)))
        val record = store.get(SyncCollection.SETTING, "font")!!
        assertEquals(newer.v, record.v)
        assertFalse("remote records aren't uploaded again", record.dirty)
        // Unknown collections from newer app versions are ignored
        assertEquals(0, store.applyRemote(listOf(older.copy(c = "somethingNew"))))
    }

    @Test
    fun importKeepsExistingRecords() {
        val store = store()
        store.edit { put(SyncCollection.FAVORITE, "movie:1", value) }
        store.edit {
            import(SyncRecord("favorite", "movie:1", buildJsonObject { put("x", 2) }, t = 1))
            import(SyncRecord("favorite", "movie:2", value, t = 1))
        }
        assertEquals(value, store.value(SyncCollection.FAVORITE, "movie:1"))
        assertEquals(1L, store.get(SyncCollection.FAVORITE, "movie:2")!!.t)
    }

    @Test
    fun changesAreCounted() {
        val store = store()
        val before = store.changes(SyncCollection.PLAYLIST).value
        store.edit { put(SyncCollection.PLAYLIST, "p", value) }
        assertEquals(before + 1, store.changes(SyncCollection.PLAYLIST).value)
        assertEquals(0, store.changes(SyncCollection.FAVORITE).value)
    }

    @Test
    fun unreadableFileIsSetAsideNotOverwritten() {
        File(folder.root, "records_favorite.json").writeText("{ not json")
        val store = store()
        assertTrue(store.live(SyncCollection.FAVORITE).isEmpty())
        store.edit { put(SyncCollection.FAVORITE, "movie:1", value) }
        assertNotNull(folder.root.listFiles()!!.firstOrNull { it.name.startsWith("records_favorite.json.corrupt") })
    }

    @Test
    fun deviceIdIsStable() {
        val first = DeviceIdentity.load(folder.root)
        assertEquals(first, DeviceIdentity.load(folder.root))
        assertTrue(first.isNotBlank())
    }
}
