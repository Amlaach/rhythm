package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalysisTransferTest {

    @Test fun roundTripPreservesEveryMeasurementAndUnicode() {
        val song = song(7, "C:\\Music\\ניגונים\\אור חדש.mp3", "אור חדש", "מקהלת לב", 1234, 184_000)
        val feature = feature(7, tags = "137:0.82,0:0.4")
        val text = AnalysisTransfer.encode(
            listOf(song), mapOf(song.id to feature), createdAt = 99,
            capabilities = setOf(AnalysisTransfer.CAP_ACOUSTIC, AnalysisTransfer.CAP_SEMANTIC_TAGS)
        )
        val decoded = AnalysisTransfer.decode(text)

        assertEquals(99L, decoded.createdAt)
        assertEquals(setOf("acoustic", "semantic-tags"), decoded.capabilities)
        assertEquals("אור חדש.mp3", decoded.tracks.single().fileName)
        assertEquals(feature.copy(songId = 0), decoded.tracks.single().feature)
    }

    @Test fun aDamagedFileIsRejectedBeforeAnythingCanBeImported() {
        val song = song(1, "/music/a.mp3", "a", "artist", 100, 180_000)
        val encoded = AnalysisTransfer.encode(listOf(song), mapOf(1L to feature(1)))
        try {
            AnalysisTransfer.decode(encoded.replace("180000", "180001"))
            fail("a changed payload must not pass its checksum")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun fileIdentityMatchesAcrossDifferentPlatformIdsAndRoots() {
        val desktop = song(10, "D:\\Library\\Album\\song.mp3", "Song", "Artist", 4567, 200_000)
        val phone = song(987, "/storage/emulated/0/Music/song.mp3", "Song", "Artist", 4567, 200_000)
        val bundle = AnalysisTransfer.decode(
            AnalysisTransfer.encode(listOf(desktop), mapOf(desktop.id to feature(desktop.id)))
        )
        val result = AnalysisTransfer.match(bundle, listOf(phone))

        assertEquals(listOf(987L), result.features.map { it.songId })
        assertEquals(0, result.unmatched)
        assertEquals(0, result.ambiguous)
    }

    @Test fun metadataFallbackSurvivesARenamedOrRetaggedFile() {
        val desktop = song(10, "D:\\Old\\old-name.mp3", "Same title", "Same Artist", 4567, 200_000)
        val phone = song(22, "/Music/new-name.flac", "same title", "same artist", 9999, 201_100)
        val bundle = AnalysisTransfer.decode(
            AnalysisTransfer.encode(listOf(desktop), mapOf(desktop.id to feature(desktop.id)))
        )
        val result = AnalysisTransfer.match(bundle, listOf(phone))
        assertEquals(22L, result.features.single().songId)
    }

    @Test fun anAmbiguousSongIsRefusedRatherThanGuessed() {
        val desktop = song(10, "D:\\one.mp3", "Song", "Artist", 1000, 200_000)
        val first = song(20, "/a/copy-a.mp3", "Song", "Artist", 2000, 200_000)
        val second = song(21, "/b/copy-b.mp3", "Song", "Artist", 3000, 200_000)
        val bundle = AnalysisTransfer.decode(
            AnalysisTransfer.encode(listOf(desktop), mapOf(desktop.id to feature(desktop.id)))
        )
        val result = AnalysisTransfer.match(bundle, listOf(first, second))

        assertTrue(result.features.isEmpty())
        assertEquals(1, result.ambiguous)
    }

    @Test fun importingAcousticDataDoesNotErasePhoneTags() {
        val desktop = song(1, "C:\\song.mp3", "Song", "Artist", 1000, 200_000)
        val phone = song(2, "/Music/song.mp3", "Song", "Artist", 1000, 200_000)
        val bundle = AnalysisTransfer.decode(
            AnalysisTransfer.encode(listOf(desktop), mapOf(1L to feature(1, tags = "")))
        )
        val result = AnalysisTransfer.match(bundle, listOf(phone), mapOf(2L to feature(2, tags = "5:0.9")))
        assertEquals("5:0.9", result.features.single().tags)
    }

    private fun song(
        id: Long,
        path: String,
        title: String,
        artist: String,
        size: Long,
        duration: Long
    ) = SongEntity(
        id = id, title = title, titleLower = title.lowercase(), artistName = artist,
        artistKey = artist.lowercase(), albumName = "Album", albumId = 1,
        durationMs = duration, trackNumber = 1, year = 2026, genre = null,
        path = path, folder = path.substringBeforeLast('/', ""), dateAddedSec = 0,
        sizeBytes = size
    )

    private fun feature(id: Long, tags: String = "") = AudioFeatureEntity(
        songId = id, analyzedAt = 123, bpm = 128f, bpmConfidence = 0.8f,
        musicalKey = 2, mode = 0, energy = 0.42f, brightness = 0.37f,
        flatness = 0.11f, dynamics = 0.22f, onsetRate = 3.1f,
        chroma = "1,2,3", timbre = "4,5,6", timbreVar = "7,8,9",
        shape = "0.1,0.2", scaleMode = 3, scaleConfidence = 0.7f,
        chroma24 = "1,2,3,4", tags = tags
    )
}
