package com.pira.ccloud.sync.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pira.ccloud.BuildConfig
import com.pira.ccloud.sync.SyncEngine
import com.pira.ccloud.sync.SyncLibrary
import com.pira.ccloud.sync.transport.DeviceInfo
import com.pira.ccloud.sync.transport.FakeSyncTransport
import com.pira.ccloud.sync.transport.HttpSyncTransport
import com.pira.ccloud.sync.transport.SyncTransport
import com.pira.ccloud.utils.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/**
 * The app's [SyncEngine], wired to the process lifecycle (foreground / background) and to network
 * changes. Null while sync is hidden ([BuildConfig.SYNC_ENABLED] is off in release builds until
 * the sync server is live).
 *
 * It talks to the servers in [BuildConfig.SYNC_SERVERS] (base URLs ending in `/v1`, separated by
 * commas, tried in order). With none, debug builds use an in-memory [FakeSyncTransport], so groups
 * disappear when the app restarts; a stored membership then fails and turns sync off, keeping the
 * local data.
 */
object SyncService {
    @Volatile
    private var engine: SyncEngine? = null

    val isAvailable: Boolean get() = BuildConfig.SYNC_ENABLED

    /** The engine, created and started on first use; call from the main thread. */
    fun engine(context: Context): SyncEngine? {
        if (!isAvailable) return null
        return engine ?: synchronized(this) {
            engine ?: create(context.applicationContext).also { engine = it }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun create(context: Context): SyncEngine {
        val engine = SyncEngine(
            records = SyncLibrary.get(context).records,
            transport = transport(),
            stateStore = EncryptedSyncStateStore(context.filesDir),
            device = DeviceInfo(deviceName(context), if (DeviceUtils.isTv(context)) "android-tv" else "android"),
            // One coroutine at a time, as the engine requires; file and network work stays off
            // the main thread
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
            clockOffset = SyncLibrary.clockOffset
        )
        engine.start()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = engine.onForeground()
            override fun onStop(owner: LifecycleOwner) = engine.onBackground()
        })

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = engine.onConnectivityRegained()
        })
        return engine
    }

    private fun transport(): SyncTransport {
        val servers = BuildConfig.SYNC_SERVERS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (servers.isEmpty()) FakeSyncTransport(simulatePeer = true) else HttpSyncTransport(servers)
    }

    /** "Living room TV" when the user named the device, else the model. */
    private fun deviceName(context: Context): String {
        val named = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        return named?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }
}
