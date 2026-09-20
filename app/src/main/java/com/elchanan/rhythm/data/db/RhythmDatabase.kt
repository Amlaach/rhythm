package com.elchanan.rhythm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        SongStatsEntity::class,
        ArtistEntity::class,
        AffinityEntity::class,
        TransitionEntity::class,
        AudioFeatureEntity::class,
        LyricsEntity::class,
        HistoryEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        TagOverrideEntity::class,
        PlaybackPositionEntity::class,
        BookmarkEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class RhythmDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile
        private var instance: RhythmDatabase? = null

        /**
         * v1 -> v2 adds per song ratings, the directed transition table and
         * the audio feature table. Written out by hand so that ratings a user
         * already entered survive the upgrade.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lyrics (" +
                        "songId INTEGER NOT NULL, text TEXT NOT NULL, synced TEXT NOT NULL, " +
                        "source TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(songId))"
                )
            }
        }

        /** v3 -> v4 adds the tag corrections table. Nothing else moves. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tag_overrides (" +
                        "songId INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', " +
                        "artistName TEXT NOT NULL DEFAULT '', albumName TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(songId))"
                )
            }
        }

        /**
         * v4 -> v5 records how a track moves over its length. Existing rows keep
         * an empty shape and are simply re-analysed in the background.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_features ADD COLUMN shape TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5 -> v6 records which mode a track draws on, not just major or minor. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_features ADD COLUMN scaleMode INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE audio_features ADD COLUMN scaleConfidence REAL NOT NULL DEFAULT 0")
            }
        }

        /** v6 -> v7 adds the quarter tone chroma the maqam modes need. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_features ADD COLUMN chroma24 TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v7 -> v8 stores what the tagging model heard in each track. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_features ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v8 -> v9 adds where the listener stopped, the places they marked,
         * a genre they can set themselves, and whether a track is speech.
         *
         * Written out rather than left to a destructive fallback: by this
         * point a library has months of play counts, ratings and tags behind
         * it, and none of that is recoverable from the files.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playback_positions (" +
                        "songId INTEGER NOT NULL, positionMs INTEGER NOT NULL, " +
                        "durationMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "finished INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(songId))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bookmarks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "songId INTEGER NOT NULL, positionMs INTEGER NOT NULL, " +
                        "label TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_songId ON bookmarks(songId)")
                db.execSQL("ALTER TABLE song_stats ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE song_stats ADD COLUMN spoken INTEGER NOT NULL DEFAULT -1")
            }
        }

        /**
         * v9 -> v10 splits listening by the day of the week.
         *
         * Plays recorded before this land in neither bucket, so the term stays
         * silent for a song until it is heard a few more times - which is
         * correct. Back-filling would mean inventing days for plays whose day
         * was never written down.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE song_stats ADD COLUMN dWeekend INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE song_stats ADD COLUMN dWeekday INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v10 -> v11 records which style tags the app guessed.
         *
         * Everything already there defaults to 0, which reads as "typed by
         * the user". That is the safe direction: the worst it does is protect
         * an old automatic tag from being revised, whereas the other default
         * would let the learner overwrite words the user chose.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE song_stats ADD COLUMN stylesAuto INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE song_stats ADD COLUMN rating INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE song_stats ADD COLUMN styles TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS transitions (" +
                        "a INTEGER NOT NULL, b INTEGER NOT NULL, weight REAL NOT NULL, " +
                        "penalty REAL NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(a, b))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS audio_features (" +
                        "songId INTEGER NOT NULL, analyzedAt INTEGER NOT NULL, bpm REAL NOT NULL, " +
                        "bpmConfidence REAL NOT NULL, musicalKey INTEGER NOT NULL, mode INTEGER NOT NULL, " +
                        "energy REAL NOT NULL, brightness REAL NOT NULL, flatness REAL NOT NULL, " +
                        "dynamics REAL NOT NULL, onsetRate REAL NOT NULL, chroma TEXT NOT NULL, " +
                        "timbre TEXT NOT NULL, timbreVar TEXT NOT NULL, PRIMARY KEY(songId))"
                )
            }
        }

        fun get(context: Context): RhythmDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RhythmDatabase::class.java,
                "rhythm.db"
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
