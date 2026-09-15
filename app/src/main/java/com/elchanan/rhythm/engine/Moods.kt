package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity

/**
 * Quick filters over the measured audio features. These are deliberately
 * coarse buckets rather than a classifier: everything here comes from numbers
 * the analyser already produced, so a mood chip is just a predicate.
 */
enum class Mood(val label: String, val subtitle: String) {
    CALM("רגוע", "איטי, פחות הקשה"),
    ENERGETIC("קצבי", "מהיר ועם דופק"),
    BRIGHT("שמח", "מז'ור וקצב בינוני ומעלה"),
    DEEP("מרגש", "מינור, דינמיקה רחבה"),
    FOCUS("ריכוז", "אחיד, בלי קפיצות"),
    WORKOUT("אימון", "הכי מהיר והכי חזק"),
    NIGHT("לילה", "שקט וכהה");

    fun matches(feature: AudioFeatureEntity?): Boolean {
        val f = feature ?: return false
        if (f.energy <= 0f) return false
        return when (this) {
            CALM -> (f.bpm in 1f..96f || f.onsetRate < 0.9f) && f.dynamics < 1.4f
            ENERGETIC -> f.bpm >= 110f && f.onsetRate >= 1.1f
            BRIGHT -> f.mode == 1 && f.bpm >= 95f
            DEEP -> f.mode == 0 && f.dynamics >= 0.7f
            FOCUS -> f.dynamics < 0.75f && f.bpm in 70f..125f
            WORKOUT -> f.bpm >= 125f && f.onsetRate >= 1.4f
            NIGHT -> f.brightness < 0.14f && f.bpm <= 105f
        }
    }

    companion object {
        fun filter(
            songs: List<SongEntity>,
            features: Map<Long, AudioFeatureEntity>,
            mood: Mood
        ): List<SongEntity> = songs.filter { mood.matches(features[it.id]) }
    }
}
