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
        TagOverrideEntity::class
    ],
    version = 5,
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
