package com.pira.ccloud.sync

import android.content.Context
import android.util.Log

/** The app's single [LibraryStore], created on first use (which also migrates the old storage). */
object SyncLibrary {
    private const val TAG = "SyncLibrary"

    @Volatile
    private var instance: LibraryStore? = null

    /** The server's clock minus this device's, kept current by sync; local changes use it. */
    val clockOffset = ClockOffset()

    fun get(context: Context): LibraryStore =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): LibraryStore {
        val directory = context.filesDir
        val records = RecordStore(directory, DeviceIdentity.load(directory)) {
            System.currentTimeMillis() + clockOffset.offsetMs
        }
        try {
            LegacyMigration(directory, records).runIfNeeded()
        } catch (e: Exception) {
            // The old files are only renamed after a successful import, so the next launch retries
            Log.e(TAG, "Migrating the old storage failed", e)
        }
        return LibraryStore(records)
    }
}
