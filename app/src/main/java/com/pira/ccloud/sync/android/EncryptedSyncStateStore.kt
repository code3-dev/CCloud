package com.pira.ccloud.sync.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.pira.ccloud.sync.SyncJson
import com.pira.ccloud.sync.SyncState
import com.pira.ccloud.sync.SyncStateStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the [SyncState] in `filesDir/sync_state.bin`, encrypted with an AES key that never leaves
 * the Android Keystore, since it holds the group's tokens. If the key or the file is lost (e.g.
 * after a restore onto another device), the device is simply no longer syncing.
 */
class EncryptedSyncStateStore(directory: File) : SyncStateStore {
    private val file = File(directory, "sync_state.bin")
    private val lock = Any()
    private var cached: SyncState? = null
    private var loaded = false

    override fun load(): SyncState? = synchronized(lock) {
        if (!loaded) {
            cached = read()
            loaded = true
        }
        cached
    }

    override fun save(state: SyncState?): Unit = synchronized(lock) {
        cached = state
        loaded = true
        if (state == null) {
            file.delete()
            return@synchronized
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(SyncJson.encodeToString(SyncState.serializer(), state).toByteArray())
            val temp = File(file.parentFile, "${file.name}.tmp")
            // Layout: IV length, IV, ciphertext
            temp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save the sync state", e)
        }
    }

    private fun read(): SyncState? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val ivLength = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val json = String(cipher.doFinal(bytes.copyOfRange(1 + ivLength, bytes.size)))
            SyncJson.decodeFromString(SyncState.serializer(), json)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read the sync state; sync is off", e)
            null
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SyncState"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ccloud_sync_state"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
