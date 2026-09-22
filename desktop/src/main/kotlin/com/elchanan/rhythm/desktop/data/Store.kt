package com.elchanan.rhythm.desktop.data

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.BookmarkEntity
import com.elchanan.rhythm.data.db.HistoryEntity
import com.elchanan.rhythm.data.db.PlaylistEntity
import com.elchanan.rhythm.data.db.PlaylistItemEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.data.db.TagOverrideEntity
import com.elchanan.rhythm.engine.EngineTuning
import com.elchanan.rhythm.engine.Names
import com.elchanan.rhythm.engine.Recommender
import com.elchanan.rhythm.engine.BulkTagging
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.engine.TransitionEdge
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
            addMissingColumns(conn)
            return Store(conn)
        }

        /**
         * Brings a database written by an older build up to today's schema.
         *
         * Every table above is created IF NOT EXISTS, which is right the
         * first time and does nothing ever after. So a column added to a
         * table in a later version - and several have been: spoken, note,
         * stylesAuto, scaleMode, chroma24 - never reaches a database that
         * already has that table. The app then fails on the first query
         * that names the column, on a library that looks perfectly fine,
         * and the only cure anyone finds is deleting the lot and starting
         * again. Which is exactly what an update should never ask for.
         *
         * So the columns each table is supposed to have are read back out
         * of the same DDL that creates it - one source of truth, so a
         * column added up there is migrated down here without anyone
         * having to remember - and whatever is missing is added.
         *
         * Only ever additive. Nothing here drops or rewrites a column: the
         * data in it is the user's, and a schema change is not a reason to
         * lose it.
         */
        private fun addMissingColumns(conn: Connection) {
            for (ddl in SCHEMA) {
                val table = TABLE_NAME.find(ddl)?.groupValues?.get(1) ?: continue
                val wanted = columnsOf(ddl)
                if (wanted.isEmpty()) continue

                val present = HashSet<String>()
                conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA table_info(" + table + ")").use { rs ->
                        while (rs.next()) present += rs.getString("name").lowercase()
                    }
                }
                // Empty means the table did not exist a moment ago and was
                // just created complete. Nothing to reconcile.
                if (present.isEmpty()) continue

                for ((name, definition) in wanted) {
                    if (name.lowercase() in present) continue
                    try {
                        conn.createStatement().use {
                            it.execute("ALTER TABLE " + table + " ADD COLUMN " + addable(definition))
                        }
                    } catch (e: Exception) {
                        // One column that cannot be added is not a reason to
                        // refuse to start. Say so and carry on - the rest of
                        // the library still works, where a thrown exception
                        // here would take the whole app down.
                        System.err.println("Rhythm: could not add " + table + "." + name + " - " + e.message)
                    }
                }
            }
        }

        /**
         * The column definitions in a CREATE TABLE, as name to definition.
         *
         * Split at the commas that are not inside brackets, so a PRIMARY KEY
         * (a, b) at the end counts as one part rather than two, and then drop
         * the parts that describe the table rather than a column.
         */
        private fun columnsOf(ddl: String): List<Pair<String, String>> {
            val body = ddl.substringAfter('(', "").substringBeforeLast(')', "")
            if (body.isBlank()) return emptyList()

            val parts = ArrayList<String>()
            val part = StringBuilder()
            var depth = 0
            for (c in body) {
                when {
                    c == '(' -> { depth++; part.append(c) }
                    c == ')' -> { depth--; part.append(c) }
                    c == ',' && depth == 0 -> { parts += part.toString(); part.setLength(0) }
                    else -> part.append(c)
                }
            }
            parts += part.toString()

            val out = ArrayList<Pair<String, String>>()
            for (raw in parts) {
                val definition = raw.trim().replace(WHITESPACE, " ")
                if (definition.isEmpty()) continue
                val first = definition.substringBefore(' ')
                if (first.uppercase() in TABLE_CONSTRAINTS) continue
                out += first to definition
            }
            return out
        }

        /**
         * The same definition, in a form ALTER TABLE will accept.
         *
         * SQLite refuses to add a NOT NULL column without a default, because
         * it has no idea what to put in the rows already there. Which value
         * hardly matters - these are columns a later version invented, and
         * nothing has ever written them - so it is the empty one for its
         * type, matching what a fresh row would get anyway.
         */
        private fun addable(definition: String): String {
            val upper = definition.uppercase()
            if (!upper.contains(" NOT NULL") || upper.contains(" DEFAULT ")) return definition
            return definition + " DEFAULT " + (if (upper.contains(" TEXT")) "''" else "0")
        }

        private val TABLE_NAME = Regex("CREATE TABLE IF NOT EXISTS (\\w+)", RegexOption.IGNORE_CASE)
        private val WHITESPACE = Regex("\\s+")
        private val TABLE_CONSTRAINTS =
            setOf("PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "CONSTRAINT")

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
            // One row per play, which the aggregate counts in song_stats
            // cannot be rebuilt into: "how many plays" is a number, "when"
            // is a history, and the recap is entirely about when.
            """
            CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT, songId INTEGER NOT NULL,
                playedAt INTEGER NOT NULL, completed INTEGER NOT NULL,
                listenedMs INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS history_playedAt ON history(playedAt)",
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT, songId INTEGER NOT NULL,
                positionMs INTEGER NOT NULL, label TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS bookmarks_songId ON bookmarks(songId)",
            // Words someone typed or pasted in, which is the one source that
            // cannot be found again by looking at the file. Tags and sidecar
            // files are re-read on demand and never stored; this table holds
            // only what would otherwise be lost.
            """
            CREATE TABLE IF NOT EXISTS lyrics (
                songId INTEGER PRIMARY KEY, text TEXT NOT NULL,
                synced TEXT NOT NULL, source TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
            // Where a long recording was left. Separate from the bookmarks
            // because there is exactly one of these per file and it is
            // overwritten constantly, where a bookmark is made on purpose and
            // kept.
            """
            CREATE TABLE IF NOT EXISTS positions (
                songId INTEGER PRIMARY KEY, positionMs INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
            // A correction to what a file's own tags claim, kept beside the
            // library rather than only written into the file. Writing into
            // the file is optional and can fail - the file may be read only,
            // on a network share, or open in something else - and the repair
            // has to survive that, and survive a rescan.
            """
            CREATE TABLE IF NOT EXISTS tag_overrides (
                songId INTEGER PRIMARY KEY, title TEXT NOT NULL DEFAULT '',
                artistName TEXT NOT NULL DEFAULT '', albumName TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            "CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)",
            // What was heard near what, and what followed what. Both are
            // learned from listening alone, and both are read by the
            // recommender on every call - which is why they are kept here
            // rather than the engine being handed two empty maps. Without
            // them a radio loses its heaviest term and the sequencer has
            // nothing to order a mix by but how things sound.
            //
            // Symmetric: every pair is written in both directions, so "what
            // goes with this" has one answer whichever end it is asked from.
            """
            CREATE TABLE IF NOT EXISTS affinity (
                a INTEGER NOT NULL, b INTEGER NOT NULL,
                weight REAL NOT NULL, updatedAt INTEGER NOT NULL,
                PRIMARY KEY (a, b)
            )
            """.trimIndent(),
            // Directed, and deliberately a second table: A then B is a
            // different fact from B then A, and the penalty column is what
            // separates a transition that was listened to from one that was
            // skipped away from.
            """
            CREATE TABLE IF NOT EXISTS transitions (
                a INTEGER NOT NULL, b INTEGER NOT NULL,
                weight REAL NOT NULL, penalty REAL NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY (a, b)
            )
            """.trimIndent()
        )

        private const val KEY_FOLDERS = "folders"
        private const val KEY_SEED = "feedSeed"

        /**
         * Edges kept per table.
         *
         * These two grow with how much someone listens rather than with how
         * much music they own, so unlike everything else here they have no
         * ceiling of their own. Twenty thousand is far more than the engine
         * ever reads and about a megabyte on disk.
         */
        private const val EDGE_LIMIT = 20_000
        private const val TRIM_EVERY = 200
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

    /**
     * Records that a song was listened to.
     *
     * A play and a skip are separate events and never both, which is the
     * rule the phone measures by and the one the recommender is built on:
     * `attempts = playCount + skipCount`, and a song counted as both is a
     * song counted twice. Whether something reached this at all is
     * [com.elchanan.rhythm.desktop.countsAsPlay]'s decision, not this one.
     *
     * The time-of-day and day-of-week buckets are filled here too. The
     * engine reads them - `timeFit` and `dayFit` are two of the terms in
     * every score - and a bucket nobody fills is a term that is silently
     * always zero.
     */
    @Synchronized
    fun notePlay(songId: Long, listenedMs: Long, completed: Boolean) {
        val now = System.currentTimeMillis()
        conn.prepareStatement(
            "INSERT INTO history (songId, playedAt, completed, listenedMs) VALUES (?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, songId)
            ps.setLong(2, now)
            ps.setInt(3, if (completed) 1 else 0)
            ps.setLong(4, listenedMs)
            ps.executeUpdate()
        }
        ensureStats(songId)
        // The bucket this play lands in, by the same reckoning the engine
        // reads it back with - Recommender owns both, so they cannot drift.
        val bucket = Recommender.bucketOf(now)
        val weekend = Recommender.isWeekend(now)
        conn.prepareStatement(
            "UPDATE song_stats SET playCount = playCount + 1, " +
                "completeCount = completeCount + ?, " +
                "listenedMs = listenedMs + ?, lastPlayedAt = ?, " +
                "b0 = b0 + ?, b1 = b1 + ?, b2 = b2 + ?, b3 = b3 + ?, " +
                "dWeekend = dWeekend + ?, dWeekday = dWeekday + ? WHERE songId = ?"
        ).use { ps ->
            ps.setInt(1, if (completed) 1 else 0)
            ps.setLong(2, listenedMs.coerceAtLeast(0L))
            ps.setLong(3, now)
            ps.setInt(4, if (bucket == 0) 1 else 0)
            ps.setInt(5, if (bucket == 1) 1 else 0)
            ps.setInt(6, if (bucket == 2) 1 else 0)
            ps.setInt(7, if (bucket == 3) 1 else 0)
            ps.setInt(8, if (weekend) 1 else 0)
            ps.setInt(9, if (weekend) 0 else 1)
            ps.setLong(10, songId)
            ps.executeUpdate()
        }
    }

    /**
     * Records that a song was moved on from before it had been heard.
     *
     * No history row: the history is what the recap counts, and a track
     * someone skipped past is not a minute they listened to. The time it did
     * get is still added, because that is the number "how much of this
     * actually gets heard" is built from.
     */
    @Synchronized
    fun noteSkip(songId: Long, listenedMs: Long) {
        ensureStats(songId)
        conn.prepareStatement(
            "UPDATE song_stats SET skipCount = skipCount + 1, " +
                "listenedMs = listenedMs + ?, lastPlayedAt = ? WHERE songId = ?"
        ).use { ps ->
            ps.setLong(1, listenedMs.coerceAtLeast(0L))
            ps.setLong(2, System.currentTimeMillis())
            ps.setLong(3, songId)
            ps.executeUpdate()
        }
    }

    // ---------------------------------------------------------------------
    // What goes with what
    // ---------------------------------------------------------------------

    /**
     * Strengthens the link between two songs heard close together.
     *
     * Written in both directions by the caller, because the question this
     * answers is symmetric. The weight accumulates rather than being set:
     * a pair heard together ten times should outrank one heard together
     * once, and the engine divides the popularity back out at the far end.
     */
    @Synchronized
    fun bumpAffinity(a: Long, b: Long, weight: Double) {
        if (a == b || a <= 0L || b <= 0L) return
        conn.prepareStatement(
            "INSERT INTO affinity (a, b, weight, updatedAt) VALUES (?,?,?,?) " +
                "ON CONFLICT(a, b) DO UPDATE SET weight = affinity.weight + excluded.weight, " +
                "updatedAt = excluded.updatedAt"
        ).use { ps ->
            ps.setLong(1, a)
            ps.setLong(2, b)
            ps.setDouble(3, weight)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    /**
     * Records that [to] followed [from], and how that went.
     *
     * Two counters on one row rather than two rows. A transition that was
     * listened to and a transition that was skipped away from are the same
     * observation with opposite signs, and the engine reads them together:
     * a pair seen once scores a third of what a pair seen often does.
     */
    @Synchronized
    fun noteTransition(from: Long, to: Long, skipped: Boolean) {
        if (from == to || from <= 0L || to <= 0L) return
        conn.prepareStatement(
            "INSERT INTO transitions (a, b, weight, penalty, updatedAt) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(a, b) DO UPDATE SET " +
                "weight = transitions.weight + excluded.weight, " +
                "penalty = transitions.penalty + excluded.penalty, " +
                "updatedAt = excluded.updatedAt"
        ).use { ps ->
            ps.setLong(1, from)
            ps.setLong(2, to)
            ps.setDouble(3, if (skipped) 0.0 else 1.0)
            ps.setDouble(4, if (skipped) 1.0 else 0.0)
            ps.setLong(5, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    /** The co-occurrence edges, in the shape the recommender takes them. */
    @Synchronized
    fun affinityMap(): Map<Long, Map<Long, Double>> {
        val out = HashMap<Long, MutableMap<Long, Double>>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT a, b, weight FROM affinity")
            while (rs.next()) {
                out.getOrPut(rs.getLong("a")) { HashMap() }[rs.getLong("b")] =
                    rs.getDouble("weight")
            }
        }
        return out
    }

    @Synchronized
    fun transitionMap(): Map<Long, Map<Long, TransitionEdge>> {
        val out = HashMap<Long, MutableMap<Long, TransitionEdge>>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT a, b, weight, penalty FROM transitions")
            while (rs.next()) {
                out.getOrPut(rs.getLong("a")) { HashMap() }[rs.getLong("b")] =
                    TransitionEdge(rs.getDouble("weight"), rs.getDouble("penalty"))
            }
        }
        return out
    }

    /**
     * Drops the weakest edges once either table has grown past what the
     * engine can use.
     *
     * Called every so often rather than on every play: the count is not free
     * and the tables only ever grow slowly. An edge seen once years ago
     * carries no signal anything would miss; the strong ones are the point.
     */
    @Synchronized
    fun trimEdges() {
        conn.createStatement().use { st ->
            st.execute(
                "DELETE FROM affinity WHERE rowid NOT IN " +
                    "(SELECT rowid FROM affinity ORDER BY weight DESC LIMIT $EDGE_LIMIT)"
            )
            st.execute(
                "DELETE FROM transitions WHERE rowid NOT IN " +
                    "(SELECT rowid FROM transitions ORDER BY weight DESC LIMIT $EDGE_LIMIT)"
            )
        }
    }

    /** How many plays since the tables were last trimmed. */
    private var playsSinceTrim = 0

    /** True once enough has been written that a trim is worth the query. */
    @Synchronized
    fun dueForTrim(): Boolean {
        playsSinceTrim++
        if (playsSinceTrim < TRIM_EVERY) return false
        playsSinceTrim = 0
        return true
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

    /**
     * The same rating and style words across a group of artists.
     *
     * A null leaves that field as it was: the group dialog can set a rating
     * without touching anyone's tags, or tag without re-rating, and picking
     * neither is a no-op rather than a wipe. [replaceStyles] is the
     * difference between "these are also this" and "these are only this".
     *
     * One transaction, because forty artists half updated is a worse state
     * than forty not updated at all.
     *
     * Unlike [setArtistRating] the rating here does not toggle off when it
     * matches: applying four stars to a group means all of them end on four,
     * which is the whole point of doing it as a group.
     */
    @Synchronized
    fun bulkUpdateArtists(
        keys: List<String>,
        names: Map<String, String>,
        rating: Int?,
        styles: List<String>?,
        replaceStyles: Boolean
    ) {
        if (keys.isEmpty() || (rating == null && styles == null)) return
        val previous = conn.autoCommit
        conn.autoCommit = false
        try {
            for (key in keys) {
                val name = names[key] ?: key
                if (rating != null) {
                    conn.prepareStatement(
                        "INSERT INTO artists (artistKey, displayName, rating, updatedAt) " +
                            "VALUES (?,?,?,?) ON CONFLICT(artistKey) DO UPDATE SET " +
                            "rating = excluded.rating, updatedAt = excluded.updatedAt"
                    ).use { ps ->
                        ps.setString(1, key)
                        ps.setString(2, name)
                        ps.setInt(3, rating)
                        ps.setLong(4, System.currentTimeMillis())
                        ps.executeUpdate()
                    }
                }
                if (styles != null) {
                    val merged = if (replaceStyles) {
                        Styles.join(styles)
                    } else {
                        Styles.join(Styles.parse(artistStyles(key)) + styles)
                    }
                    setArtistStyles(key, name, merged)
                }
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = previous
        }
    }

    /** The style words already on an artist, or empty when there is no row. */
    @Synchronized
    fun artistStyles(artistKey: String): String {
        conn.prepareStatement("SELECT styles FROM artists WHERE artistKey = ?").use { ps ->
            ps.setString(1, artistKey)
            val rs = ps.executeQuery()
            return if (rs.next()) rs.getString("styles").orEmpty() else ""
        }
    }

    /**
     * Artists typed in one per line as "name | rating | styles".
     *
     * For the case the group dialog cannot serve: a list written elsewhere,
     * or dictated, and pasted in whole. A blank field leaves what was there,
     * so the same list can be pasted twice without the second paste undoing
     * anything, and a line starting with # is a comment.
     *
     * Returns how many lines were taken, which is the only honest way to
     * report on free text: it says nothing about whether the names matched
     * anything in the library, because an artist can be rated before their
     * music is scanned.
     */
    @Synchronized
    fun importArtistLines(text: String): Int {
        var count = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('|').map { it.trim() }
            val name = parts.getOrNull(0).orEmpty()
            if (name.isEmpty()) continue
            val rating = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 5) ?: 0
            val styles = parts.getOrNull(2).orEmpty()
            val key = Names.normalizeKey(name)
            if (rating > 0) {
                conn.prepareStatement(
                    "INSERT INTO artists (artistKey, displayName, rating, updatedAt) " +
                        "VALUES (?,?,?,?) ON CONFLICT(artistKey) DO UPDATE SET " +
                        "rating = excluded.rating, updatedAt = excluded.updatedAt"
                ).use { ps ->
                    ps.setString(1, key)
                    ps.setString(2, name)
                    ps.setInt(3, rating)
                    ps.setLong(4, System.currentTimeMillis())
                    ps.executeUpdate()
                }
            }
            if (styles.isNotBlank()) setArtistStyles(key, name, styles)
            count++
        }
        return count
    }

    /**
     * The words someone wrote for a song, or null when nobody has.
     *
     * Only the manual ones live here. What is in the file's tags, or in a
     * .lrc beside it, is read from the file every time [SongLyrics] is asked
     * - copying it into the database would mean a correction made in a tag
     * editor silently having no effect.
     */
    @Synchronized
    fun lyrics(songId: Long): Pair<String, String>? {
        conn.prepareStatement("SELECT text, synced FROM lyrics WHERE songId = ?").use { ps ->
            ps.setLong(1, songId)
            val rs = ps.executeQuery()
            if (!rs.next()) return null
            val text = rs.getString("text").orEmpty()
            val synced = rs.getString("synced").orEmpty()
            return if (text.isBlank() && synced.isBlank()) null else text to synced
        }
    }

    /**
     * Saves words for a song, or clears them when both halves are empty.
     *
     * Clearing rather than storing a blank row, so that emptying the editor
     * puts the song back to whatever its file says rather than pinning it to
     * nothing - which is what someone who cleared the box is asking for.
     */
    @Synchronized
    fun setLyrics(songId: Long, text: String, synced: String) {
        if (text.isBlank() && synced.isBlank()) {
            conn.prepareStatement("DELETE FROM lyrics WHERE songId = ?").use { ps ->
                ps.setLong(1, songId)
                ps.executeUpdate()
            }
            return
        }
        conn.prepareStatement(
            "INSERT INTO lyrics (songId, text, synced, source, updatedAt) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(songId) DO UPDATE SET text = excluded.text, " +
                "synced = excluded.synced, source = excluded.source, " +
                "updatedAt = excluded.updatedAt"
        ).use { ps ->
            ps.setLong(1, songId)
            ps.setString(2, text)
            ps.setString(3, synced)
            ps.setString(4, "manual")
            ps.setLong(5, System.currentTimeMillis())
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
    // History, bookmarks and where a recording was left
    // ---------------------------------------------------------------------

    /**
     * The play history, newest first, capped.
     *
     * Capped because the recap reads all of it into memory at once and a
     * library played for years would otherwise grow that read without bound.
     * Twenty thousand plays is several years of heavy listening.
     */
    @Synchronized
    fun history(limit: Int = 20_000): List<HistoryEntity> {
        val out = ArrayList<HistoryEntity>()
        conn.prepareStatement(
            "SELECT * FROM history ORDER BY playedAt DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            val rs = ps.executeQuery()
            while (rs.next()) {
                out.add(
                    HistoryEntity(
                        id = rs.getLong("id"),
                        songId = rs.getLong("songId"),
                        playedAt = rs.getLong("playedAt"),
                        completed = rs.getInt("completed") == 1,
                        listenedMs = rs.getLong("listenedMs")
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun bookmarks(): List<BookmarkEntity> {
        val out = ArrayList<BookmarkEntity>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM bookmarks ORDER BY songId, positionMs")
            while (rs.next()) {
                out.add(
                    BookmarkEntity(
                        id = rs.getLong("id"),
                        songId = rs.getLong("songId"),
                        positionMs = rs.getLong("positionMs"),
                        label = rs.getString("label"),
                        createdAt = rs.getLong("createdAt")
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun addBookmark(songId: Long, positionMs: Long, label: String) {
        conn.prepareStatement(
            "INSERT INTO bookmarks (songId, positionMs, label, createdAt) VALUES (?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, songId)
            ps.setLong(2, positionMs)
            ps.setString(3, label)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun deleteBookmark(id: Long) {
        conn.prepareStatement("DELETE FROM bookmarks WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    /** Where each long recording was left, by song id. */
    @Synchronized
    fun positions(): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT songId, positionMs FROM positions")
            while (rs.next()) out[rs.getLong("songId")] = rs.getLong("positionMs")
        }
        return out
    }

    /**
     * Remembers, or forgets, where a recording was left.
     *
     * Near the start or near the end is forgotten rather than stored. Offering
     * to resume something forty seconds in is noise, and offering to resume
     * something that finished is worse than noise.
     */
    @Synchronized
    fun setPosition(songId: Long, positionMs: Long, durationMs: Long) {
        val tooEarly = positionMs < 60_000
        val tooLate = durationMs > 0 && positionMs > durationMs - 60_000
        if (tooEarly || tooLate) {
            conn.prepareStatement("DELETE FROM positions WHERE songId = ?").use { ps ->
                ps.setLong(1, songId)
                ps.executeUpdate()
            }
            return
        }
        conn.prepareStatement(
            "INSERT INTO positions (songId, positionMs, updatedAt) VALUES (?,?,?) " +
                "ON CONFLICT(songId) DO UPDATE SET positionMs = excluded.positionMs, " +
                "updatedAt = excluded.updatedAt"
        ).use { ps ->
            ps.setLong(1, songId)
            ps.setLong(2, positionMs)
            ps.setLong(3, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    /** Throws the measured features away, so the next pass measures again. */
    @Synchronized
    fun clearFeatures() {
        conn.createStatement().use { it.execute("DELETE FROM audio_features") }
    }

    /** Throws the listening history away: plays, likes, ratings, everything. */
    @Synchronized
    fun clearStats() {
        conn.autoCommit = false
        try {
            conn.createStatement().use { st ->
                st.execute("DELETE FROM song_stats")
                st.execute("DELETE FROM history")
                st.execute("DELETE FROM positions")
                // What goes with what was learned entirely from those plays,
                // so it goes with them. Leaving it would keep recommending
                // out of a history the user has just asked to be forgotten.
                st.execute("DELETE FROM affinity")
                st.execute("DELETE FROM transitions")
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * The same rating, or the same like, on many songs at once.
     *
     * One transaction rather than one per song: rating twenty songs is the
     * whole point of selecting twenty songs, and twenty separate commits on a
     * spinning disk is a visible pause.
     */
    @Synchronized
    fun bulkSetRating(songIds: List<Long>, rating: Int) {
        if (songIds.isEmpty()) return
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT INTO song_stats (songId, rating) VALUES (?,?) " +
                    "ON CONFLICT(songId) DO UPDATE SET rating = excluded.rating"
            ).use { ps ->
                for (id in songIds) {
                    ps.setLong(1, id)
                    ps.setInt(2, rating)
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
    fun bulkSetLike(songIds: List<Long>, value: Int) {
        if (songIds.isEmpty()) return
        val now = if (value != 0) System.currentTimeMillis() else 0L
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT INTO song_stats (songId, liked, likedAt) VALUES (?,?,?) " +
                    "ON CONFLICT(songId) DO UPDATE SET liked = excluded.liked, " +
                    "likedAt = excluded.likedAt"
            ).use { ps ->
                for (id in songIds) {
                    ps.setLong(1, id)
                    ps.setInt(2, value)
                    ps.setLong(3, now)
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

    /**
     * The style words on one song, and whether the app guessed them.
     *
     * The flag is the whole point of storing them separately from the
     * artist's. What the user typed is ground truth and must never be
     * overwritten by something derived from it; what the learner guessed is
     * only as good as the model that produced it, and that model gets better
     * every time more artists are tagged.
     */
    @Synchronized
    fun setSongStyles(songId: Long, styles: String, auto: Boolean) {
        conn.prepareStatement(
            "INSERT INTO song_stats (songId, styles, stylesAuto) VALUES (?,?,?) " +
                "ON CONFLICT(songId) DO UPDATE SET styles = excluded.styles, " +
                "stylesAuto = excluded.stylesAuto"
        ).use { ps ->
            ps.setLong(1, songId)
            ps.setString(2, styles)
            ps.setInt(3, if (auto) 1 else 0)
            ps.executeUpdate()
        }
    }

    /**
     * Puts one set of style tags on many songs at once, for a folder tag.
     *
     * What may be overwritten is [BulkTagging]'s decision, shared with the
     * phone, because it is the one operation here that can destroy tagging the
     * user cannot get back.
     *
     * @return how many songs actually changed.
     */
    @Synchronized
    fun setStylesForSongs(songIds: List<Long>, styles: List<String>, replace: Boolean): Int {
        if (songIds.isEmpty()) return 0
        var changed = 0
        val read = conn.prepareStatement(
            "SELECT styles, stylesAuto FROM song_stats WHERE songId = ?"
        )
        read.use { ps ->
            for (id in songIds) {
                ps.setLong(1, id)
                val rs = ps.executeQuery()
                val current = if (rs.next()) {
                    SongStatsEntity(
                        songId = id,
                        styles = rs.getString("styles").orEmpty(),
                        stylesAuto = rs.getInt("stylesAuto")
                    )
                } else {
                    null
                }
                rs.close()
                val next = BulkTagging.tagsFor(current, styles, replace) ?: continue
                setSongStyles(id, next, auto = false)
                changed++
            }
        }
        return changed
    }

    /**
     * A genre the user set, replacing whatever the file said.
     *
     * The genre in a downloaded file is whoever tagged it's opinion, and on a
     * library built from downloads it is usually blank, wrong, or the name of
     * the site it came from. Empty means "use the file's".
     */
    @Synchronized
    fun setGenre(songIds: List<Long>, genre: String) {
        if (songIds.isEmpty()) return
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT INTO song_stats (songId, genre) VALUES (?,?) " +
                    "ON CONFLICT(songId) DO UPDATE SET genre = excluded.genre"
            ).use { ps ->
                for (id in songIds) {
                    ps.setLong(1, id)
                    ps.setString(2, genre.trim())
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

    /** The user overruling the speech detector, either way. */
    @Synchronized
    fun setSpoken(songId: Long, spoken: Boolean) {
        conn.prepareStatement(
            "INSERT INTO song_stats (songId, spoken) VALUES (?,?) " +
                "ON CONFLICT(songId) DO UPDATE SET spoken = excluded.spoken"
        ).use { ps ->
            ps.setLong(1, songId)
            ps.setInt(2, if (spoken) 1 else 0)
            ps.executeUpdate()
        }
    }

    /**
     * Forgets that one song was ever played, keeping what the user said.
     *
     * Counts get inflated by things that were not really listening - a song
     * left on repeat overnight, a machine lent to someone - and once they are
     * wrong there is no arguing with the shelves built on top of them. This
     * is the way to argue with them. The like, the rating and the tags stay:
     * those were said on purpose.
     */
    @Synchronized
    fun resetPlayCount(songId: Long) {
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                """
                UPDATE song_stats
                SET playCount = 0, skipCount = 0, completeCount = 0, listenedMs = 0,
                    lastPlayedAt = 0, b0 = 0, b1 = 0, b2 = 0, b3 = 0,
                    dWeekend = 0, dWeekday = 0
                WHERE songId = ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, songId)
                ps.executeUpdate()
            }
            // The history rows too, or "recently played" would still show it.
            conn.prepareStatement("DELETE FROM history WHERE songId = ?").use { ps ->
                ps.setLong(1, songId)
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * Drops everything the app knew about a song whose file is gone.
     *
     * A rescan removes the song row on its own but not the stats, the
     * position, the bookmarks or the learned edges - those are keyed on an id
     * derived from the path, and a file written to that same path later would
     * inherit a stranger's history.
     */
    @Synchronized
    fun forget(songId: Long) {
        conn.autoCommit = false
        try {
            for (sql in listOf(
                "DELETE FROM song_stats WHERE songId = ?",
                "DELETE FROM history WHERE songId = ?",
                "DELETE FROM positions WHERE songId = ?",
                "DELETE FROM bookmarks WHERE songId = ?",
                "DELETE FROM playlist_items WHERE songId = ?",
                "DELETE FROM tag_overrides WHERE songId = ?",
                "DELETE FROM audio_features WHERE songId = ?",
                "DELETE FROM lyrics WHERE songId = ?",
                "DELETE FROM affinity WHERE a = ? OR b = ?",
                "DELETE FROM transitions WHERE a = ? OR b = ?"
            )) {
                conn.prepareStatement(sql).use { ps ->
                    ps.setLong(1, songId)
                    if (sql.contains("OR b = ?")) ps.setLong(2, songId)
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("DELETE FROM songs WHERE id = ?").use { ps ->
                ps.setLong(1, songId)
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Throws away every tag the app guessed, keeping every one that was typed. */
    @Synchronized
    fun clearLearnedStyles() {
        conn.createStatement().use {
            it.execute("UPDATE song_stats SET styles = '', stylesAuto = 0 WHERE stylesAuto = 1")
        }
    }

    // ---------------------------------------------------------------------
    // Tag corrections
    // ---------------------------------------------------------------------

    @Synchronized
    fun overrides(): Map<Long, TagOverrideEntity> {
        val out = HashMap<Long, TagOverrideEntity>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM tag_overrides")
            while (rs.next()) {
                out[rs.getLong("songId")] = TagOverrideEntity(
                    songId = rs.getLong("songId"),
                    title = rs.getString("title"),
                    artistName = rs.getString("artistName"),
                    albumName = rs.getString("albumName")
                )
            }
        }
        return out
    }

    @Synchronized
    fun saveOverrides(rows: List<TagOverrideEntity>) {
        if (rows.isEmpty()) return
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT INTO tag_overrides VALUES (?,?,?,?) ON CONFLICT(songId) DO UPDATE SET " +
                    "title = excluded.title, artistName = excluded.artistName, " +
                    "albumName = excluded.albumName"
            ).use { ps ->
                for (row in rows) {
                    ps.setLong(1, row.songId)
                    ps.setString(2, row.title)
                    ps.setString(3, row.artistName)
                    ps.setString(4, row.albumName)
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
    fun clearOverrides() {
        conn.createStatement().use { it.execute("DELETE FROM tag_overrides") }
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
            acousticWeight = setting("tune.acoustic")?.toFloatOrNull() ?: 1.0f,
            // Part of the tuning rather than a setting beside it, because the
            // recommender reads it from here and there is no second copy.
            separations = setting("tune.separations").orEmpty()
        )
        @Synchronized
        set(value) {
            putSetting("tune.discovery", value.discovery.toString())
            putSetting("tune.artist", value.artistWeight.toString())
            putSetting("tune.style", value.styleWeight.toString())
            putSetting("tune.repeat", value.repeatGuard.toString())
            putSetting("tune.acoustic", value.acousticWeight.toString())
            putSetting("tune.separations", value.separations)
        }

    /** Reads one stored setting. Public so [com.elchanan.rhythm.desktop.Prefs] can sit on it. */
    @Synchronized
    fun get(key: String): String? = setting(key)

    @Synchronized
    fun put(key: String, value: String) = putSetting(key, value)

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
