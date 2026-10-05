package com.pira.ccloud.sync

import com.pira.ccloud.data.model.FontType
import com.pira.ccloud.data.model.sync.SyncRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Runs the shared fixtures that the Apple app must pass too. */
class SyncContractTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("sync-fixtures/$name")) { "missing fixture $name" }.readText()

    @Test
    fun mergeCases() {
        val cases = SyncJson.parseToJsonElement(fixture("merge-cases.json")).jsonArray
        for (case in cases) {
            val obj = case.jsonObject
            val name = obj["name"]!!.jsonPrimitive.content
            val existing = (obj["existing"] as? JsonObject)?.let { SyncJson.decodeFromJsonElement(SyncRecord.serializer(), it) }
            val incoming = SyncJson.decodeFromJsonElement(SyncRecord.serializer(), obj["incoming"]!!)
            val expected = obj["incomingWins"]!!.jsonPrimitive.content.toBoolean()
            assertEquals(name, expected, SyncMerge.wins(incoming, existing))
        }
    }

    @Test
    fun settingsMapping() {
        val mapping = SyncJson.parseToJsonElement(fixture("settings-mapping.json")).jsonObject
        fun argb(hex: String) = hex.toLong(16).toInt()

        for (entry in mapping["textColors"]!!.jsonArray) {
            val argb = argb(entry.jsonObject["argb"]!!.jsonPrimitive.content)
            val name = entry.jsonObject["name"]!!.jsonPrimitive.content
            assertEquals(name, SettingsMapping.textColorName(argb))
            assertEquals(argb, SettingsMapping.textColorArgb(name))
        }
        for (hex in mapping["unmappedTextColors"]!!.jsonArray) {
            assertNull(hex.toString(), SettingsMapping.textColorName(argb(hex.jsonPrimitive.content)))
        }
        for (entry in mapping["backgrounds"]!!.jsonArray) {
            val argb = argb(entry.jsonObject["argb"]!!.jsonPrimitive.content)
            val name = entry.jsonObject["name"]!!.jsonPrimitive.content
            assertEquals(name, SettingsMapping.backgroundName(argb))
            assertEquals(argb, SettingsMapping.backgroundArgb(name))
        }
        for (hex in mapping["unmappedBackgrounds"]!!.jsonArray) {
            assertNull(hex.toString(), SettingsMapping.backgroundName(argb(hex.jsonPrimitive.content)))
        }
        for (entry in mapping["fonts"]!!.jsonArray) {
            val type = FontType.valueOf(entry.jsonObject["android"]!!.jsonPrimitive.content)
            val name = entry.jsonObject["name"]!!.jsonPrimitive.content
            assertEquals(name, SettingsMapping.fontName(type))
            assertEquals(type, SettingsMapping.fontType(name))
        }
        val seek = mapping["seekIntervals"]!!.jsonObject
        seek["valid"]!!.jsonArray.map { it.jsonPrimitive.int }.forEach { assertEquals(it, SettingsMapping.seekInterval(it)) }
        seek["invalid"]!!.jsonArray.map { it.jsonPrimitive.int }.forEach { assertNull(SettingsMapping.seekInterval(it)) }
        assertNull(SettingsMapping.fontType("comic-sans"))
    }

    @Test
    fun composeHalfBlackIsGlass() {
        // Color.Black.copy(alpha = 0.5f).toArgb() in Compose; any partial black counts
        assertEquals("glass", SettingsMapping.backgroundName(0x7F000000))
        assertEquals("glass", SettingsMapping.backgroundName(0x80000000.toInt()))
    }

    @Test
    fun keys() {
        assertEquals("movie:12", SyncKeys.media("movie", 12))
        assertEquals("series" to 3, SyncKeys.parseMedia("series:3"))
        assertNull(SyncKeys.parseMedia("series:x"))
        assertEquals("p1|movie:12", SyncKeys.playlistItem("p1", "movie", 12))
        assertEquals("p1" to "movie:12", SyncKeys.parsePlaylistItem("p1|movie:12"))
        assertEquals("1:2:3", SyncKeys.episode(1, 2, 3))
        assertEquals(Triple(1, 2, 3), SyncKeys.parseEpisode("1:2:3"))
        assertNull(SyncKeys.parseEpisode("1:2"))
        assertEquals("movie:5", SyncKeys.movieProgress(5))
        assertEquals("episode:1:2:3", SyncKeys.episodeProgress(1, 2, 3))
    }
}
