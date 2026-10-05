package com.pira.ccloud.data.model.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One synced value: a favorite, a playlist, a playlist membership, a watched episode, a playback
 * position or a setting. The format is shared with the Apple app (see the sync plan), so field
 * names must not change.
 *
 * @property c collection, one of [com.pira.ccloud.sync.SyncCollection]
 * @property k canonical key within the collection (see [com.pira.ccloud.sync.SyncKeys])
 * @property v the value, or null for a deleted record
 * @property t when the record was last changed (Unix ms); the newest change wins
 * @property d true when the record was deleted (a tombstone, kept so the deletion can sync)
 * @property dev id of the device that made the change; breaks ties between equal [t]
 * @property dirty changed on this device since the last successful upload (local only)
 */
@Serializable
data class SyncRecord(
    val c: String,
    val k: String,
    val v: JsonObject? = null,
    val t: Long,
    val d: Boolean = false,
    val dev: String = "",
    val dirty: Boolean = false
)
