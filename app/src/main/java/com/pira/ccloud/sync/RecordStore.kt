package com.pira.ccloud.sync

import com.pira.ccloud.data.model.sync.SyncRecord
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The local copy of every synced record, one JSON file per collection (`records_<c>.json`).
 *
 * Reads come from memory; every change is written through to disk before [edit] returns, so the
 * synchronous call sites in `StorageUtils` keep their current behaviour. All access is serialized
 * on one lock. Changes made here are local: they get a fresh timestamp and are marked dirty for
 * upload. Records from other devices go through [applyRemote] instead.
 */
class RecordStore(
    private val directory: File,
    val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val collections = HashMap<String, LinkedHashMap<String, SyncRecord>>()
    private val versions = SyncCollection.all.associateWith { MutableStateFlow(0) }
    private val serializer = ListSerializer(SyncRecord.serializer())

    private val localEditsFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Increases after every change to [collection], local or remote. */
    fun changes(collection: String): StateFlow<Int> = versions.getValue(collection)

    /** The collection of every change made on this device (not by [applyRemote]). */
    val localEdits: SharedFlow<String> = localEditsFlow

    fun now(): Long = clock()

    /** The record, including a tombstone, or null if there is none. */
    fun get(collection: String, key: String): SyncRecord? = synchronized(lock) {
        load(collection)[key]
    }

    /** The value of a live (not deleted) record. */
    fun value(collection: String, key: String): JsonObject? =
        get(collection, key)?.takeUnless { it.d }?.v

    /** Live records of [collection], in no particular order. */
    fun live(collection: String): List<SyncRecord> = synchronized(lock) {
        load(collection).values.filter { !it.d }
    }

    /** Every record of [collection], tombstones included. */
    fun all(collection: String): List<SyncRecord> = synchronized(lock) {
        load(collection).values.toList()
    }

    /** Size of the collection's file on disk, in bytes. */
    fun fileSize(collection: String): Long = synchronized(lock) { file(collection).length() }

    /** Applies local changes and saves the touched collections once. */
    fun <T> edit(block: Editor.() -> T): T = synchronized(lock) {
        val editor = Editor()
        val result = editor.block()
        commit(editor.touched)
        editor.touched.forEach { localEditsFlow.tryEmit(it) }
        result
    }

    /** Records changed here and not yet uploaded, oldest change first. */
    fun dirtyRecords(): List<SyncRecord> = synchronized(lock) {
        SyncCollection.all.flatMap { load(it).values.filter { record -> record.dirty } }.sortedBy { it.t }
    }

    /**
     * Marks [uploaded] records clean, unless they changed again since they were read (then the
     * newer change still needs uploading).
     */
    fun markClean(uploaded: List<SyncRecord>) = synchronized(lock) {
        val touched = HashSet<String>()
        for (record in uploaded) {
            val records = load(record.c)
            val current = records[record.k] ?: continue
            if (current.dirty && current.t == record.t && current.dev == record.dev) {
                records[record.k] = current.copy(dirty = false)
                touched += record.c
            }
        }
        touched.forEach { write(it) }
    }

    /**
     * Marks every record dirty, so the next sync uploads the whole library: after joining a group,
     * this device's data is merged with the group's (nothing on either side is lost).
     */
    fun markAllDirty() = synchronized(lock) {
        for (collection in SyncCollection.all) {
            val records = load(collection)
            if (records.values.all { it.dirty }) continue
            records.replaceAll { _, record -> record.copy(dirty = true) }
            write(collection)
        }
    }

    /**
     * Merges records that came from another device. Each one replaces the local record only if it
     * wins under [SyncMerge]. Returns the number of records applied.
     */
    fun applyRemote(records: List<SyncRecord>): Int = synchronized(lock) {
        val touched = HashSet<String>()
        var applied = 0
        for (incoming in records) {
            if (incoming.c !in versions) continue
            val stored = load(incoming.c)
            if (SyncMerge.wins(incoming, stored[incoming.k])) {
                stored[incoming.k] = incoming.copy(dirty = false)
                touched += incoming.c
                applied++
            }
        }
        commit(touched)
        applied
    }

    inner class Editor internal constructor() {
        internal val touched = HashSet<String>()

        fun get(collection: String, key: String): SyncRecord? = load(collection)[key]

        fun live(collection: String): List<SyncRecord> = load(collection).values.filter { !it.d }

        fun all(collection: String): List<SyncRecord> = load(collection).values.toList()

        /** Creates or replaces a record. Its timestamp is later than the record it replaces. */
        fun put(collection: String, key: String, value: JsonObject): SyncRecord {
            val records = load(collection)
            val record = SyncRecord(
                c = collection,
                k = key,
                v = value,
                t = nextTimestamp(records[key]),
                dev = deviceId,
                dirty = true
            )
            records[key] = record
            touched += collection
            return record
        }

        /** Marks a live record deleted. Does nothing if there is no live record. */
        fun delete(collection: String, key: String): Boolean {
            val records = load(collection)
            val existing = records[key]
            if (existing == null || existing.d) return false
            records[key] = SyncRecord(
                c = collection,
                k = key,
                v = null,
                t = nextTimestamp(existing),
                d = true,
                dev = deviceId,
                dirty = true
            )
            touched += collection
            return true
        }

        /**
         * Adds a record carried over from the pre-sync storage, unless the key already exists.
         * Such records keep the timestamp they're given (a very old one), so any real change from
         * another device replaces them.
         */
        fun import(record: SyncRecord) {
            val records = load(record.c)
            if (record.k in records) return
            records[record.k] = record.copy(dev = deviceId, dirty = true)
            touched += record.c
        }

        /** Forgets records entirely, without tombstones (used to prune local history). */
        fun forget(collection: String, keys: Collection<String>) {
            val records = load(collection)
            if (keys.any { records.remove(it) != null }) touched += collection
        }
    }

    private fun nextTimestamp(existing: SyncRecord?): Long {
        val now = clock()
        return if (existing != null && existing.t >= now) existing.t + 1 else now
    }

    private fun load(collection: String): LinkedHashMap<String, SyncRecord> =
        collections.getOrPut(collection) {
            val records = LinkedHashMap<String, SyncRecord>()
            val file = file(collection)
            if (file.exists()) {
                try {
                    SyncJson.decodeFromString(serializer, file.readText())
                        .forEach { records[it.k] = it }
                } catch (e: Exception) {
                    // Keep the unreadable file for inspection instead of overwriting it
                    file.renameTo(File(directory, "${file.name}.corrupt-${clock()}"))
                }
            }
            records
        }

    private fun commit(touched: Set<String>) {
        for (collection in touched) {
            write(collection)
            versions.getValue(collection).value++
        }
    }

    private fun write(collection: String) {
        val records = collections[collection] ?: return
        val target = file(collection)
        val temp = File(directory, "${target.name}.tmp")
        temp.writeText(SyncJson.encodeToString(serializer, records.values.toList()))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun file(collection: String) = File(directory, "records_$collection.json")
}
