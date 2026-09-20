package com.elchanan.rhythm.desktop.data

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.PlaylistEntity
import com.elchanan.rhythm.data.db.PlaylistItemEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.engine.EngineTuning
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Everything the desktop build remembers between launches.
 *
 * Every entry point is synchronised. A JDBC Connection cannot be used from
 * two threads at once, and this one is: the analysis pass holds a background
 * thread for minutes at a time while the interface writes likes and ratings
 * from another.
 *
 * Room is Android only, so this is plain SQLite through JDBC with the SQL
 * written out. The rows it reads and writes are the same entity classes the
 * phone uses - they live in :engine - so the recommender, when it is wired
 * up, will be handed exactly what it expects.
 *
 * The split between the two main tables is the important part and is copied
 * from the phone deliberately. `songs` is rebuilt from scratch on every scan,
 * because the disk is the truth about what exists. `song_stats` is never
 * touched by a scan: ratings, likes and play counts are the user's, they took
 * months to accumulate, and a rescan must not be able to lose them. They stay
 * joined by the song id, which is why that id has to be derived from
 * something stable - see LibraryScan.idOf.
 */
class Store private constructor(private val conn: Connection) {

    companion object {

        /**
         * Opens the database, creating it if this is the first launch.
         *
         * Beside the user's own data rather than beside the program, because
         * an installed application's own folder is not writable without
         * elevation on Windows, and because reinstalling should not take
         * someone's ratings with it.
         */
        fun open(): Store {
            val dir = dataDir()
            dir.mkdirs()
            val conn = DriverManager.getConnection("jdbc:sqlite:${File(dir, "rhythm.db").absolutePath}")
            conn.createStatement().use { st ->
                // Write ahead logging, so a play count being recorded does not
                // block the scan that is running behind it.
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA foreign_keys=ON")
                for (ddl in SCHEMA) st.execute(ddl)
            }
            return Store(conn)
        }

        private fun dataDir(): File {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if (os.contains("win")) {
                val local = System.getenv("LOCALAPPDATA")
                if (!local.isNullOrBlank()) return File(local, "Rhythm")
            }
            val xdg = System.getenv("XDG_DATA_HOME")
            if (!xdg.isNullOrBlank()) return File(xdg, "Rhythm")
            return File(System.getProperty("user.home"), ".local/share/Rhythm")
        }

        private val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS songs (
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, titleLower TEXT NOT NULL,
                artistName TEXT NOT NULL, artistKey TEXT NOT NULL, albumName TEXT NOT NULL,
                albumId INTEGER NOT NULL, durationMs INTEGER NOT NULL, trackNumber INTEGER NOT NULL,
                year INTEGER NOT NULL, genre TEXT, path TEXT NOT NULL, folder TEXT NOT NULL,
                dateAddedSec INTEGER NOT NULL, sizeBytes INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS songs_artistKey ON songs(artistKey)",
            """
            CREATE TABLE IF NOT EXISTS song_stats (
                songId INTEGER PRIMARY KEY, playCount INTEGER NOT NULL DEFAULT 0,
                skipCount INTEGER NOT NULL DEFAULT 0, completeCount INTEGER NOT NULL DEFAULT 0,
                listenedMs INTEGER NOT NULL DEFAULT 0, lastPlayedAt INTEGER NOT NULL DEFAULT 0,
                liked INTEGER NOT NULL DEFAULT 0, likedAt INTEGER NOT NULL DEFAULT 0,
                rating INTEGER NOT NULL DEFAULT 0, styles TEXT NOT NULL DEFAULT '',
                stylesAuto INTEGER NOT NULL DEFAULT 0, b0 INTEGER NOT NULL DEFAULT 0,
                b1 INTEGER NOT NULL DEFAULT 0, b2 INTEGER NOT NULL DEFAULT 0,
                b3 INTEGER NOT NULL DEFAULT 0, dWeekend INTEGER NOT NULL DEFAULT 0,
                dWeekday INTEGER NOT NULL DEFAULT 0, genre TEXT NOT NULL DEFAULT '',
                spoken INTEGER NOT NULL DEFAULT -1
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS artists (
                artistKey TEXT PRIMARY KEY, displayName TEXT NOT NULL,
                rating INTEGER NOT NULL DEFAULT 0, styles TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '', updatedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS audio_features (
                songId INTEGER PRIMARY KEY, analyzedAt INTEGER NOT NULL, bpm REAL NOT NULL,
                bpmConfidence REAL NOT NULL, musicalKey INTEGER NOT NULL, mode INTEGER NOT NULL,
                energy REAL NOT NULL, brightness REAL NOT NULL, flatness REAL NOT NULL,
                dynamics REAL NOT NULL, onsetRate REAL NOT NULL, chroma TEXT NOT NULL,
                timbre TEXT NOT NULL, timbreVar TEXT NOT NULL, shape TEXT NOT NULL,
                scaleMode INTEGER NOT NULL, scaleConfidence REAL NOT NULL,
                chroma24 TEXT NOT NULL, tags TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
            // The song is referenced by id and not by path, so a file that
            // moves stays on the lists it is on - the id is derived from the
            // tags, not from where the file sits. ON DELETE CASCADE is what
            // makes deleting a list take its rows with it; it needs the
            // foreign_keys pragma above, which is why that pragma is on.
            """
            CREATE TABLE IF NOT EXISTS playlist_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlistId INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                songId INTEGER NOT NULL, position INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS playlist_items_playlistId ON playlist_items(playlistId)",
            // A song can only be on a list once. Without this the same track
            // added twice from two different screens sits there twice, and
            // removing it once leaves the copy behind.
            """
            CREATE UNIQUE INDEX IF NOT EXISTS playlist_items_unique
                ON playlist_items(playlistId, songId)
            """.trimIndent(),
            "CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)"
        )

        private const val KEY_FOLDERS = "folders"
        private const val KEY_SEED = "feedSeed"
    }

    // ---------------------------------------------------------------------
    // The library
    // ---------------------------------------------------------------------

    /**
     * Replaces the song table with what the scan found.
     *
     * One transaction, so a scan interrupted half way leaves the previous
     * library intact rather than a fragment of the new one. Stats are not
     * touched: a song that disappears keeps its row in song_stats, which is
     * what makes moving a folder and moving it back a non event.
     */
    @Synchronized
    fun replaceSongs(songs: List<SongEntity>) {
        conn.autoCommit = false
        try {
            conn.createStatement().use { it.execute("DELETE FROM songs") }
            conn.prepareStatement(
                "INSERT INTO songs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (s in songs) {
                    ps.setLong(1, s.id)
                    ps.setString(2, s.title)
                    ps.setString(3, s.titleLower)
                    ps.setString(4, s.artistName)
                    ps.setString(5, s.artistKey)
                    ps.setString(6, s.albumName)
                    ps.setLong(7, s.albumId)
                    ps.setLong(8, s.durationMs)
                    ps.setInt(9, s.trackNumber)
                    ps.setInt(10, s.year)
                    ps.setString(11, s.genre)
                    ps.setString(12, s.path)
                    ps.setString(13, s.folder)
                    ps.setLong(14, s.dateAddedSec)
                    ps.setLong(15, s.sizeBytes)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            // Every artist the scan saw, without disturbing one the user has
            // already rated - the rating and the styles are the whole reason
            // this table exists.
            conn.prepareStatement(
                "INSERT INTO artists (artistKey, displayName) VALUES (?,?) " +
                    "ON CONFLICT(artistKey) DO UPDATE SET displayName = excluded.displayName"
            ).use { ps ->
                for ((key, name) in songs.associate { it.artistKey to it.artistName }) {
                    ps.setString(1, key)
                    ps.setString(2, name)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    fun songs(): List<SongEntity> {
        val out = ArrayList<SongEntity>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM songs ORDER BY titleLower")
            while (rs.next()) out.add(readSong(rs))
        }
        return out
    }

    private fun readSong(rs: ResultSet) = SongEntity(
        id = rs.getLong("id"),
        title = rs.getString("title"),
        titleLower = rs.getString("titleLower"),
        artistName = rs.getString("artistName"),
        artistKey = rs.getString("artistKey"),
        albumName = rs.getString("albumName"),
        albumId = rs.getLong("albumId"),
        durationMs = rs.getLong("durationMs"),
        trackNumber = rs.getInt("trackNumber"),
        year = rs.getInt("year"),
        genre = rs.getString("genre"),
        path = rs.getString("path"),
        folder = rs.getString("folder"),
        dateAddedSec = rs.getLong("dateAddedSec"),
        sizeBytes = rs.getLong("sizeBytes")
    )

    // ---------------------------------------------------------------------
    // What the user thinks of it
    // ---------------------------------------------------------------------

    @Synchronized
    fun stats(): Map<Long, SongStatsEntity> {
        val out = HashMap<Long, SongStatsEntity>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM song_stats")
            while (rs.next()) {
                val row = SongStatsEntity(
                    songId = rs.getLong("songId"),
                    playCount = rs.getInt("playCount"),
                    skipCount = rs.getInt("skipCount"),
                    completeCount = rs.getInt("completeCount"),
                    listenedMs = rs.getLong("listenedMs"),
                    lastPlayedAt = rs.getLong("lastPlayedAt"),
                    liked = rs.getInt("liked"),
                    likedAt = rs.getLong("likedAt"),
                    rating = rs.getInt("rating"),
                    styles = rs.getString("styles"),
                    stylesAuto = rs.getInt("stylesAuto"),
                    b0 = rs.getInt("b0"),
                    b1 = rs.getInt("b1"),
                    b2 = rs.getInt("b2"),
                    b3 = rs.getInt("b3"),
                    dWeekend = rs.getInt("dWeekend"),
                    dWeekday = rs.getInt("dWeekday"),
                    genre = rs.getString("genre"),
                    spoken = rs.getInt("spoken")
                )
                out[row.songId] = row
            }
        }
        return out
    }

    /**
     * Marks a song liked, disliked, or neither.
     *
     * Pressing the mark a song already carries clears it, which is the same
     * rule the phone uses: the button is a toggle, not a setting, and there
     * has to be a way back from a press that was a mistake.
     */
    @Synchronized
    fun setLike(songId: Long, value: Int) {
        ensureStats(songId)
        conn.prepareStatement(
            "UPDATE song_stats SET liked = CASE WHEN liked = ? THEN 0 ELSE ? END, " +
                "likedAt = CASE WHEN liked = ? THEN 0 ELSE ? END WHERE songId = ?"
        ).use { ps ->
            ps.setInt(1, value)
            ps.setInt(2, value)
            ps.setInt(3, value)
            ps.setLong(4, System.currentTimeMillis())
            ps.setLong(5, songId)
            ps.executeUpdate()
        }
    }

    /**
     * A rating out of five, or nothing.
     *
     * Pressing the star a song already carries clears it, so a rating given
     * by mistake has a way back that is not picking a different wrong one.
     */
    @Synchronized
    fun setRating(songId: Long, rating: Int) {
        ensureStats(songId)
        conn.prepareStatement(
            "UPDATE song_stats SET rating = CASE WHEN rating = ? THEN 0 ELSE ? END WHERE songId = ?"
        ).use { ps ->
            ps.setInt(1, rating)
            ps.setInt(2, rating)
            ps.setLong(3, songId)
            ps.executeUpdate()
        }
    }

    /** Records that a song was played, and whether it was heard out. */
    @Synchronized
    fun notePlay(songId: Long, listenedMs: Long, completed: Boolean) {
        ensureStats(songId)
        conn.prepareStatement(
            "UPDATE song_stats SET playCount = playCount + 1, " +
                "completeCount = completeCount + ?, skipCount = skipCount + ?, " +
                "listenedMs = listenedMs + ?, lastPlayedAt = ? WHERE songId = ?"
        ).use { ps ->
            ps.setInt(1, if (completed) 1 else 0)
            ps.setInt(2, if (completed) 0 else 1)
            ps.setLong(3, listenedMs.coerceAtLeast(0L))
            ps.setLong(4, System.currentTimeMillis())
            ps.setLong(5, songId)
            ps.executeUpdate()
        }
    }

    private fun ensureStats(songId: Long) {
        conn.prepareStatement(
            "INSERT INTO song_stats (songId) VALUES (?) ON CONFLICT(songId) DO NOTHING"
        ).use { ps ->
            ps.setLong(1, songId)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun artists(): List<ArtistEntity> {
        val out = ArrayList<ArtistEntity>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM artists")
            while (rs.next()) {
                out.add(
                    ArtistEntity(
                        artistKey = rs.getString("artistKey"),
                        displayName = rs.getString("displayName"),
                        rating = rs.getInt("rating"),
                        styles = rs.getString("styles"),
                        note = rs.getString("note"),
                        updatedAt = rs.getLong("updatedAt")
                    )
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // What the analyser measured
    // ---------------------------------------------------------------------

    @Synchronized
    fun features(): Map<Long, AudioFeatureEntity> {
        val out = HashMap<Long, AudioFeatureEntity>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM audio_features")
            while (rs.next()) {
                val row = AudioFeatureEntity(
                    songId = rs.getLong("songId"),
                    analyzedAt = rs.getLong("analyzedAt"),
                    bpm = rs.getFloat("bpm"),
                    bpmConfidence = rs.getFloat("bpmConfidence"),
                    musicalKey = rs.getInt("musicalKey"),
                    mode = rs.getInt("mode"),
                    energy = rs.getFloat("energy"),
                    brightness = rs.getFloat("brightness"),
                    flatness = rs.getFloat("flatness"),
                    dynamics = rs.getFloat("dynamics"),
                    onsetRate = rs.getFloat("onsetRate"),
                    chroma = rs.getString("chroma"),
                    timbre = rs.getString("timbre"),
                    timbreVar = rs.getString("timbreVar"),
                    shape = rs.getString("shape"),
                    scaleMode = rs.getInt("scaleMode"),
                    scaleConfidence = rs.getFloat("scaleConfidence"),
                    chroma24 = rs.getString("chroma24"),
                    tags = rs.getString("tags")
                )
                out[row.songId] = row
            }
        }
        return out
    }

    /**
     * Stores one analysed song.
     *
     * Replaces rather than skips, because re-analysing is how a measurement
     * improves: the code that produced the old row may simply have been worse
     * than the code producing this one.
     */
    @Synchronized
    fun putFeature(f: AudioFeatureEntity) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO audio_features VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, f.songId)
            ps.setLong(2, f.analyzedAt)
            ps.setFloat(3, f.bpm)
            ps.setFloat(4, f.bpmConfidence)
            ps.setInt(5, f.musicalKey)
            ps.setInt(6, f.mode)
            ps.setFloat(7, f.energy)
            ps.setFloat(8, f.brightness)
            ps.setFloat(9, f.flatness)
            ps.setFloat(10, f.dynamics)
            ps.setFloat(11, f.onsetRate)
            ps.setString(12, f.chroma)
            ps.setString(13, f.timbre)
            ps.setString(14, f.timbreVar)
            ps.setString(15, f.shape)
            ps.setInt(16, f.scaleMode)
            ps.setFloat(17, f.scaleConfidence)
            ps.setString(18, f.chroma24)
            ps.setString(19, f.tags)
            ps.executeUpdate()
        }
    }

    /**
     * A rating out of five, or nothing.
     *
     * Pressing the star a artist already carries clears it, because a rating
     * set by mistake otherwise has no way back except picking a different
     * wrong one.
     */
    @Synchronized
    fun setArtistRating(artistKey: String, displayName: String, rating: Int) {
        // An insert and not an update, because not every artist with a page
        // has a row here. A scan writes one per primary artist; a singer who
        // only ever appears as a guest gets a page built from the credits and
        // no row at all, and an UPDATE for them would quietly do nothing.
        conn.prepareStatement(
            "INSERT INTO artists (artistKey, displayName, rating, updatedAt) VALUES (?,?,?,?) " +
                "ON CONFLICT(artistKey) DO UPDATE SET rating = " +
                "CASE WHEN artists.rating = excluded.rating THEN 0 ELSE excluded.rating END, " +
                "updatedAt = excluded.updatedAt"
        ).use { ps ->
            ps.setString(1, artistKey)
            ps.setString(2, displayName)
            ps.setInt(3, rating)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    /**
     * The style words for an artist, which is where the learner's labels come
     * from - every song by a tagged artist becomes a labelled example.
     */
    @Synchronized
    fun setArtistStyles(artistKey: String, displayName: String, styles: String) {
        conn.prepareStatement(
            "INSERT INTO artists (artistKey, displayName, styles, updatedAt) VALUES (?,?,?,?) " +
                "ON CONFLICT(artistKey) DO UPDATE SET styles = excluded.styles, " +
                "updatedAt = excluded.updatedAt"
        ).use { ps ->
            ps.setString(1, artistKey)
            ps.setString(2, displayName)
            ps.setString(3, styles)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    // ---------------------------------------------------------------------
    // Playlists
    // ---------------------------------------------------------------------

    /** The lists themselves, oldest first, which is the order they were made in. */
    @Synchronized
    fun playlists(): List<PlaylistEntity> {
        val out = ArrayList<PlaylistEntity>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM playlists ORDER BY createdAt, id")
            while (rs.next()) {
                out.add(
                    PlaylistEntity(
                        id = rs.getLong("id"),
                        name = rs.getString("name"),
                        createdAt = rs.getLong("createdAt")
                    )
                )
            }
        }
        return out
    }

    /**
     * Every membership row in the database, for every list at once.
     *
     * One query rather than one per list: the screen that shows the lists
     * shows all of their counts, and asking per list would be a query per row
     * on every reload.
     */
    @Synchronized
    fun playlistItems(): List<PlaylistItemEntity> {
        val out = ArrayList<PlaylistItemEntity>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM playlist_items ORDER BY playlistId, position")
            while (rs.next()) {
                out.add(
                    PlaylistItemEntity(
                        id = rs.getLong("id"),
                        playlistId = rs.getLong("playlistId"),
                        songId = rs.getLong("songId"),
                        position = rs.getInt("position")
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun createPlaylist(name: String): Long {
        conn.prepareStatement("INSERT INTO playlists (name, createdAt) VALUES (?,?)").use { ps ->
            ps.setString(1, name)
            ps.setLong(2, System.currentTimeMillis())
            ps.executeUpdate()
        }
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT last_insert_rowid()")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

    @Synchronized
    fun renamePlaylist(id: Long, name: String) {
        conn.prepareStatement("UPDATE playlists SET name = ? WHERE id = ?").use { ps ->
            ps.setString(1, name)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun deletePlaylist(id: Long) {
        conn.prepareStatement("DELETE FROM playlists WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    /**
     * Puts a song at the end of a list.
     *
     * The position is read rather than counted, because rows removed from the
     * middle leave gaps and a count would then hand out a position something
     * else already has. Adding a song that is already there does nothing,
     * which is what the unique index makes cheap to say.
     */
    @Synchronized
    fun addToPlaylist(playlistId: Long, songId: Long) {
        bulkAddToPlaylist(playlistId, listOf(songId))
    }

    @Synchronized
    fun bulkAddToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        var next = 0
        conn.prepareStatement(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = ?"
        ).use { ps ->
            ps.setLong(1, playlistId)
            val rs = ps.executeQuery()
            if (rs.next()) next = rs.getInt(1)
        }
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT OR IGNORE INTO playlist_items (playlistId, songId, position) VALUES (?,?,?)"
            ).use { ps ->
                for (songId in songIds) {
                    ps.setLong(1, playlistId)
                    ps.setLong(2, songId)
                    ps.setInt(3, next++)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    fun removeFromPlaylist(playlistId: Long, songId: Long) {
        conn.prepareStatement(
            "DELETE FROM playlist_items WHERE playlistId = ? AND songId = ?"
        ).use { ps ->
            ps.setLong(1, playlistId)
            ps.setLong(2, songId)
            ps.executeUpdate()
        }
    }

    // ---------------------------------------------------------------------
    // Where the music is
    // ---------------------------------------------------------------------

    /**
     * The folders the user picked, so they are not asked again every launch.
     *
     * Stored as newline separated paths. A path cannot contain a newline on
     * either Windows or Linux, which makes this the one separator that needs
     * no escaping.
     */
    var folders: List<File>
        @Synchronized
        get() = setting(KEY_FOLDERS)
            .orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .map { File(it) }
        @Synchronized
        set(value) = putSetting(KEY_FOLDERS, value.joinToString("\n") { it.absolutePath })

    /**
     * What the shelves were last shuffled to.
     *
     * A stored counter rather than the clock, so the feed is the same on
     * Tuesday afternoon as it was on Tuesday morning - a home screen that
     * rearranges itself every time it is looked at is one nobody learns the
     * shape of. It moves when the user asks it to.
     */
    var feedSeed: Long
        @Synchronized
        get() = setting(KEY_SEED)?.toLongOrNull() ?: 1L
        @Synchronized
        set(value) = putSetting(KEY_SEED, value.toString())

    /**
     * The five weights the user can move.
     *
     * Read back as [EngineTuning] because that is what the recommender takes;
     * there is no second representation of them anywhere, which is what keeps
     * a slider and the score it changes from drifting apart.
     */
    var tuning: EngineTuning
        @Synchronized
        get() = EngineTuning(
            discovery = setting("tune.discovery")?.toFloatOrNull() ?: 0.35f,
            artistWeight = setting("tune.artist")?.toFloatOrNull() ?: 1.0f,
            styleWeight = setting("tune.style")?.toFloatOrNull() ?: 1.0f,
            repeatGuard = setting("tune.repeat")?.toFloatOrNull() ?: 1.0f,
            acousticWeight = setting("tune.acoustic")?.toFloatOrNull() ?: 1.0f
        )
        @Synchronized
        set(value) {
            putSetting("tune.discovery", value.discovery.toString())
            putSetting("tune.artist", value.artistWeight.toString())
            putSetting("tune.style", value.styleWeight.toString())
            putSetting("tune.repeat", value.repeatGuard.toString())
            putSetting("tune.acoustic", value.acousticWeight.toString())
        }

    private fun setting(key: String): String? {
        conn.prepareStatement("SELECT v FROM settings WHERE k = ?").use { ps ->
            ps.setString(1, key)
            val rs = ps.executeQuery()
            return if (rs.next()) rs.getString("v") else null
        }
    }

    private fun putSetting(key: String, value: String) {
        conn.prepareStatement(
            "INSERT INTO settings (k, v) VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v = excluded.v"
        ).use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun close() = runCatching { conn.close() }.let { }
}
