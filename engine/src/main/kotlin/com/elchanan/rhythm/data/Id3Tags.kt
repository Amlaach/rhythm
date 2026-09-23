package com.elchanan.rhythm.data

import java.io.InputStream
import java.io.OutputStream

/**
 * What to change in a song's own tag. Null leaves a field exactly as the file
 * has it; an empty string clears it.
 *
 * Null really does mean untouched. The first writer dropped the title, artist
 * and album frames from every file it rewrote and put back only the ones it
 * was given - so an edit of the album alone erased the title and the artist,
 * and a title fix erased the album.
 */
data class TagEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val track: String? = null,
    val genre: String? = null
) {
    /** The ID3v2.3 frames this edit touches, with their new values. */
    fun frames(): Map<String, String> = buildMap {
        title?.let { put("TIT2", it.trim()) }
        artist?.let { put("TPE1", it.trim()) }
        album?.let { put("TALB", it.trim()) }
        albumArtist?.let { put("TPE2", it.trim()) }
        year?.let { put("TYER", it.trim()) }
        track?.let { put("TRCK", it.trim()) }
        genre?.let { put("TCON", it.trim()) }
    }

    val isEmpty: Boolean get() = frames().isEmpty()
}

/**
 * Rewrites an MP3's ID3 tag, changing only the fields a [TagEdit] names.
 *
 * Everything is built around one rule: never lose what was already in the
 * file. Every other frame is copied through byte for byte, which matters most
 * for APIC - the embedded cover. Text goes out as UTF-16 with a byte order
 * mark, the one encoding ID3v2.3 defines that can carry Hebrew. The audio
 * itself is never touched: it is streamed across unchanged behind the new tag.
 *
 * In :engine because it is bytes in and bytes out, so the rule above can be
 * tested; the phone supplies the file.
 */
object Id3Tags {

    private const val HEADER = 10

    private class Frame(val id: String, val flags: ByteArray, val body: ByteArray)

    fun rewrite(input: InputStream, output: OutputStream, edit: TagEdit): Boolean {
        val changes = edit.frames()
        if (changes.isEmpty()) return false
        // Only the frames being changed are dropped from the old tag - and a
        // year takes both of its spellings with it (TYER in 2.3, TDRC in 2.4).
        val replaced = changes.keys + (if ("TYER" in changes) setOf("TDRC") else emptySet())
        val header = ByteArray(HEADER)
        if (input.read(header) != HEADER) return false

        val kept = ArrayList<Frame>(16)

        val hasTag = header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()

        if (hasTag) {
            // Read, used and finished with inside this block: a file with no
            // tag has no version to speak of, and the tag this writes is
            // always version 3 whatever it found.
            val major = header[3].toInt()
            // Unsynchronisation and extended headers are rare and fiddly; a file
            // using either is left alone rather than guessed at.
            if (major != 3 && major != 4) return false
            val flags = header[5].toInt()
            if (flags and 0x40 != 0) return false
            val tagSize = syncSafe(header, 6)
            val tag = ByteArray(tagSize)
            if (readFully(input, tag) != tagSize) return false
            parseFrames(tag, major, kept, replaced)
            // A 2.4 footer sits outside the declared size and is not audio.
            if (flags and 0x10 != 0) readFully(input, ByteArray(HEADER))
        }

        val frames = ArrayList<Frame>(kept.size + 3)
        frames.addAll(kept)
        // An empty value clears the field: its old frame is gone and nothing replaces it.
        for ((id, value) in changes) if (value.isNotEmpty()) frames.add(textFrame(id, value))

        var body = 0
        for (f in frames) body += HEADER + f.body.size
        val padding = 512
        val newSize = body + padding

        output.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))
        // Always emit 2.3: it is the most widely understood, and the frames we
        // keep from a 2.4 tag are byte compatible for the text types involved.
        output.write(byteArrayOf(3, 0, 0))
        output.write(toSyncSafe(newSize))

        for (f in frames) {
            output.write(f.id.toByteArray(Charsets.ISO_8859_1))
            output.write(toSizeBe(f.body.size))
            output.write(f.flags)
            output.write(f.body)
        }
        output.write(ByteArray(padding))

        // With no tag to skip, those first ten bytes were audio, not a header,
        // and have to go back in front of the rest of the stream.
        if (!hasTag) output.write(header)
        input.copyTo(output)
        return true
    }

    private fun parseFrames(tag: ByteArray, major: Int, out: MutableList<Frame>, replaced: Set<String>) {
        var p = 0
        while (p + HEADER <= tag.size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            // Padding starts where a valid frame id stops, so this also ends the loop.
            if (id.any { !(it.isUpperCase() || it.isDigit()) }) break
            val size = if (major == 4) syncSafe(tag, p + 4) else beInt(tag, p + 4)
            if (size <= 0 || p + HEADER + size > tag.size) break
            val flags = tag.copyOfRange(p + 8, p + 10)
            if (id !in replaced) {
                out.add(Frame(id, flags, tag.copyOfRange(p + HEADER, p + HEADER + size)))
            }
            p += HEADER + size
        }
    }

    /**
     * Encoding 1: UTF-16 with a byte order mark, which is what carries Hebrew
     * safely through ID3v2.3.
     *
     * Little endian, written with an explicit FF FE mark. Kotlin's `UTF_16`
     * would produce big endian, which the spec allows and Android reads back
     * correctly - but the point of writing into the file at all is that other
     * programs read it, and a decoder that ignores the mark and assumes little
     * endian is common enough to be worth matching.
     */
    private fun textFrame(id: String, value: String): Frame {
        val text = value.toByteArray(Charsets.UTF_16LE)
        val body = ByteArray(1 + 2 + text.size + 2)
        body[0] = 1
        body[1] = 0xFF.toByte()
        body[2] = 0xFE.toByte()
        System.arraycopy(text, 0, body, 3, text.size)
        // The two trailing bytes are already zero: the UTF-16 terminator.
        return Frame(id, byteArrayOf(0, 0), body)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    private fun syncSafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or
            ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or
            (b[at + 3].toInt() and 0x7F)

    private fun beInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun toSyncSafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun toSizeBe(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}
