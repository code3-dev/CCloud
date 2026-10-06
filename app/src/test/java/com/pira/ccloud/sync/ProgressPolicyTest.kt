package com.pira.ccloud.sync

import com.pira.ccloud.data.model.Episode
import com.pira.ccloud.data.model.Source
import com.pira.ccloud.data.model.sync.ProgressValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressPolicyTest {
    @Test
    fun evaluate() {
        assertNull(ProgressPolicy.evaluate(29_999, 6_000_000))
        assertEquals(ProgressValue(30_000, 6_000_000, false), ProgressPolicy.evaluate(30_000, 6_000_000))
        assertEquals(ProgressValue(0, 6_000_000, true), ProgressPolicy.evaluate(5_700_000, 6_000_000))
        // Unknown duration (live or not loaded yet): position only, never "finished"
        assertEquals(ProgressValue(60_000, 0, false), ProgressPolicy.evaluate(60_000, -1))
        assertNull(ProgressPolicy.evaluate(-5, 6_000_000))
    }

    @Test
    fun resumePosition() {
        assertNull(ProgressPolicy.resumePosition(null))
        assertNull(ProgressPolicy.resumePosition(ProgressValue(0, 100_000, true)))
        assertNull(ProgressPolicy.resumePosition(ProgressValue(10_000, 100_000, false)))
        assertNull(ProgressPolicy.resumePosition(ProgressValue(200_000, 100_000, false)))
        assertEquals(45_000L, ProgressPolicy.resumePosition(ProgressValue(45_000, 100_000, false)))
        assertEquals(45_000L, ProgressPolicy.resumePosition(ProgressValue(45_000, 0, false)))
    }

    @Test
    fun trailers() {
        val trailer = Source(1, "تیزر", "mp4", "u")
        val film = Source(2, "1080p", "mkv", "u")
        assertTrue(TrailerMarkers.isTrailer(trailer))
        assertTrue(TrailerMarkers.isTrailer(Source(3, "Trailer HD", "mp4", "u")))
        assertFalse(TrailerMarkers.isTrailer(film))
        assertTrue(TrailerMarkers.isTrailer(Episode(1, "تیزر فصل", "", null, "", listOf(film))))
        assertTrue(TrailerMarkers.isTrailer(Episode(1, "قسمت ۱", "", null, "", listOf(trailer))))
        assertFalse(TrailerMarkers.isTrailer(Episode(1, "قسمت ۱", "", null, "", listOf(film, trailer))))
        assertFalse(TrailerMarkers.isTrailer(Episode(1, "قسمت ۱", "", null, "", emptyList())))
    }
}
