package com.pira.ccloud.sync

import com.pira.ccloud.data.model.sync.SyncRecord

/**
 * The conflict rule, identical on every device and on the server: the newer change wins, and of
 * two changes made at the same millisecond the one from the device with the greater id wins.
 * Applying a record twice, or in any order, gives the same result.
 */
object SyncMerge {
    fun wins(incoming: SyncRecord, existing: SyncRecord?): Boolean =
        existing == null ||
            incoming.t > existing.t ||
            (incoming.t == existing.t && incoming.dev > existing.dev)
}
