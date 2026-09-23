package com.elchanan.rhythm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The rule the writer lives by: change what was asked, keep everything else.
 * The first version broke it - editing one field erased the title, artist or
 * album that nobody had touched.
 */
class Id3TagsTest {

    private fun frame(id: String, body: ByteArray): ByteArray {
        val s = body.size
        return id.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf((s shr 24).toByte(), (s shr 16).toByte(), (s shr 8).toByte(), s.toByte(), 0, 0) + body
    }

    private fun text(id: String, value: String) = frame(id, byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1))

    private fun mp3(vararg frames: ByteArray): ByteArray {
        val tag = frames.fold(ByteArray(0)) { acc, f -> acc + f }
        val n = tag.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()
        )
        return header + tag + AUDIO
    }

    /** Frame id to its text, read back from a rewritten file. */
    private fun read(file: ByteArray): Map<String, String> {
        val size = ((file[6].toInt() and 0x7F) shl 21) or ((file[7].toInt() and 0x7F) shl 14) or
            ((file[8].toInt() and 0x7F) shl 7) or (file[9].toInt() and 0x7F)
        val out = LinkedHashMap<String, String>()
        var p = 10
        while (p + 10 <= 10 + size) {
            val id = String(file, p, 4, Charsets.ISO_8859_1)
            if (id.any { !(it.isUpperCase() || it.isDigit()) }) break
            val len = ((file[p + 4].toInt() and 0xFF) shl 24) or ((file[p + 5].toInt() and 0xFF) shl 16) or
                ((file[p + 6].toInt() and 0xFF) shl 8) or (file[p + 7].toInt() and 0xFF)
            val body = file.copyOfRange(p + 10, p + 10 + len)
            out[id] = when {
                !id.startsWith("T") -> "<${body.size} bytes>"
                body[0].toInt() == 0 -> String(body, 1, body.size - 1, Charsets.ISO_8859_1)
                else -> String(body, 3, body.size - 5, Charsets.UTF_16LE)
            }
            p += 10 + len
        }
        return out
    }

    private fun rewrite(file: ByteArray, edit: TagEdit): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(Id3Tags.rewrite(ByteArrayInputStream(file), out, edit))
        return out.toByteArray()
    }

    private val cover = frame("APIC", ByteArray(40) { it.toByte() })

    @Test
    fun anEditTouchesOnlyWhatItNames() {
        val file = mp3(text("TIT2", "Title"), text("TPE1", "Artist"), text("TALB", "Album"), cover)
        val tags = read(rewrite(file, TagEdit(album = "אלבום חדש")))
        assertEquals("Title", tags["TIT2"])
        assertEquals("Artist", tags["TPE1"])
        assertEquals("אלבום חדש", tags["TALB"])
        assertEquals("<40 bytes>", tags["APIC"])
    }

    @Test
    fun everyFieldCanBeSetAndTheAudioIsUntouched() {
        val file = mp3(text("TIT2", "Old"), text("TDRC", "1999"))
        val result = rewrite(
            file,
            TagEdit("שם", "אמן", "אלבום", "אמן האלבום", "2024", "7", "חסידי")
        )
        val tags = read(result)
        assertEquals("שם", tags["TIT2"])
        assertEquals("אמן", tags["TPE1"])
        assertEquals("אלבום", tags["TALB"])
        assertEquals("אמן האלבום", tags["TPE2"])
        assertEquals("2024", tags["TYER"])
        assertFalse("the old 2.4 year goes with the new one", "TDRC" in tags)
        assertEquals("7", tags["TRCK"])
        assertEquals("חסידי", tags["TCON"])
        assertTrue(result.copyOfRange(result.size - AUDIO.size, result.size).contentEquals(AUDIO))
    }

    @Test
    fun anEmptyValueClearsTheField() {
        val tags = read(rewrite(mp3(text("TIT2", "T"), text("TALB", "Folder name")), TagEdit(album = "")))
        assertEquals("T", tags["TIT2"])
        assertFalse("TALB" in tags)
    }

    @Test
    fun aFileWithNoTagGetsOne() {
        val result = rewrite(AUDIO, TagEdit(title = "T"))
        assertEquals("T", read(result)["TIT2"])
        assertTrue(result.copyOfRange(result.size - AUDIO.size, result.size).contentEquals(AUDIO))
    }

    companion object {
        private val AUDIO = ByteArray(64) { (0xF0 + (it % 16)).toByte() }
    }
}
