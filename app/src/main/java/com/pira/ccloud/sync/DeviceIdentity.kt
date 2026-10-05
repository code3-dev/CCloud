package com.pira.ccloud.sync

import java.io.File
import java.util.UUID

/** A random id for this installation, made once. It identifies the device's changes in sync. */
object DeviceIdentity {
    private const val FILE_NAME = "sync_device_id"

    fun load(directory: File): String {
        val file = File(directory, FILE_NAME)
        file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = UUID.randomUUID().toString()
        file.writeText(id)
        return id
    }
}
