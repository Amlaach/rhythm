package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

class StyleLearningTest {
    @Test fun extraLabelsAreNotPerfectPredictions() {
        val metrics = StyleMetrics.measure(listOf(setOf("folk") to setOf("folk", "rock")))
        assertEquals(1, metrics.byStyle.getValue("rock").falsePositives)
        assertEquals(0.5, metrics.macroF1, 1e-9)
        assertEquals(0.5, metrics.macroPrecision, 1e-9)
    }

    @Test fun missingLabelsReduceRecall() {
        val metrics = StyleMetrics.measure(listOf(setOf("folk", "rock") to setOf("folk")))
        assertEquals(1, metrics.byStyle.getValue("rock").falseNegatives)
        assertEquals(0.5, metrics.macroRecall, 1e-9)
    }

    @Test fun abstainingIsNotACorrectAnswer() {
        val metrics = StyleMetrics.measure(listOf(setOf("folk") to emptySet()))
        assertEquals(0.0, metrics.macroF1, 0.0)
        assertEquals(0.0, metrics.macroPrecision, 0.0)
    }

    @Test fun majorityGuessCannotHideFailureOnRareStyle() {
        val results = List(90) { setOf("pop") to setOf("pop") } +
            List(10) { setOf("folk") to setOf("pop") }
        val metrics = StyleMetrics.measure(results)
        assertTrue(metrics.macroF1 < 0.5)
        assertFalse(StyleLearning.passesQuality(StyleValidationReport(metrics, metrics, 8, 5)))
    }

    @Test fun evenPerfectScoreMustBeatBaseline() {
        val metrics = StyleMetrics.measure(listOf(setOf("folk") to setOf("folk")))
        assertFalse(StyleLearning.passesQuality(StyleValidationReport(metrics, metrics, 8, 5)))
    }

    @Test fun wholeArtistsStayInOneFoldAndEverySongIsTestedOnce() {
        val examples = examples()
        val folds = StyleValidation.folds(examples)
        assertEquals(examples.size, folds.flatten().size)
        assertEquals(examples.map { it.songId }.toSet(), folds.flatten().map { it.songId }.toSet())
        for (artist in examples.map { it.artistKey }.distinct()) {
            assertEquals(1, folds.count { fold -> fold.any { it.artistKey == artist } })
        }
        assertEquals(
            folds.map { fold -> fold.map { it.songId } },
            StyleValidation.folds(examples.reversed()).map { fold -> fold.map { it.songId } }
        )
    }

    @Test fun validationUsesProductionPredictionRules() {
        val examples = examples()
        val folds = StyleValidation.folds(examples)
        val expected = folds.flatMapIndexed { index, heldOut ->
            val training = folds.filterIndexed { i, _ -> i != index }.flatten()
            val model = StyleLearner.fit(training.map { it.trainingRow() })!!
            // Thresholds included: the scored run uses a bar per style, so a
            // check that left them out would pass on any fixture separable
            // enough for the bar not to matter - which is no check at all.
            val bars = StyleThresholds.tune(training, StyleLearning.MIN_PRECISION)
            heldOut.map { it.labels.toSet() to model.predict(it.features, bars).toSet() }
        }
        val report = StyleValidation.evaluate(examples)!!
        assertEquals(StyleMetrics.measure(expected), report.metrics)
        assertEquals(examples.size, report.metrics.songs)
        assertTrue(StyleLearning.passesQuality(report))
    }

    @Test fun thresholdsAreChosenWithoutLookingAtTheTestFold() {
        val examples = examples()
        val folds = StyleValidation.folds(examples)
        val training = folds.drop(1).flatten()
        val bars = StyleThresholds.tune(training, StyleLearning.MIN_PRECISION)
        // Changing only the held-out fold must not move a single bar.
        val poisoned = folds[0].map { it.copy(labels = listOf("folk", "rock")) } + training
        assertEquals(bars, StyleThresholds.tune(poisoned.drop(folds[0].size), StyleLearning.MIN_PRECISION))
    }

    @Test fun eachStyleGetsItsOwnBarRatherThanOneForAll() {
        val report = StyleValidation.evaluate(mixedQuality())!!
        // A bar per style, not a single number standing in for all of them.
        assertTrue(report.thresholds.isNotEmpty())
        for (bar in report.thresholds.values) assertTrue(bar in 0.0..1.0)
    }

    @Test fun aStyleThatWorksIsTaggedEvenWhenAnotherStyleFails() {
        val examples = mixedQuality()
        val report = StyleValidation.evaluate(examples)!!

        // The whole point. "noise" is unlearnable, and under one averaged bar
        // it dragged the run down and silenced the two styles that work.
        assertFalse(StyleLearning.passesQuality(report))

        val trusted = StyleLearning.trustedStyles(report)
        assertTrue("folk should survive its own test", "folk" in trusted)
        assertTrue("rock should survive its own test", "rock" in trusted)
        assertFalse("noise cannot be learned and must not be written", "noise" in trusted)
    }

    /**
     * The mixed fixture driven through learn(), with "noise" carried on the
     * songs themselves - an artist tag cannot express a label that alternates
     * inside one artist, and routing it through the artist would quietly drop
     * the very style these tests are about.
     */
    private fun mixedLearn(extra: List<SongEntity> = emptyList()): LearnResult {
        val examples = mixedQuality()
        val songs = examples.map { song(it.songId, it.artistKey) } + extra
        val manual = examples.associate {
            it.songId to SongStatsEntity(it.songId, styles = Styles.join(it.labels), stylesAuto = 0)
        }
        val features = examples.associate { it.songId to feature(it.songId, "folk" in it.labels) } +
            extra.associate { it.id to feature(it.id, true) }
        return StyleLearning.learn(songs, manual, emptyMap(), features)
    }

    @Test fun rejectedStylesAreNeverWrittenButAcceptedOnesAre() {
        val result = mixedLearn(listOf(song(9000, "unlabelled")))
        assertEquals(LearningStatus.READY, result.status)
        val written = result.predictions.flatMap { Styles.parse(it.second) }.toSet()
        assertTrue("a style that works must still be written", written.isNotEmpty())
        assertFalse("an unlearnable style must never reach the library", "noise" in written)
        // And it was genuinely in the running, not quietly absent.
        assertTrue("noise" in result.styles.map { it.style })
        assertTrue(result.rejected.any { it.style == "noise" })
        assertTrue(result.accepted.isNotEmpty())
    }

    @Test fun aStyleOnOneArtistIsNamedAsSuchRatherThanJustScoringZero() {
        // Exactly the shape that keeps coming back: plenty of songs, all of
        // them by one singer. It cannot pass - the split is by artist - and
        // the screen used to say only "precision 0%", which reads as a bad
        // style rather than as an impossible test.
        val examples = mixedQuality().map {
            if ("rock" in it.labels) it.copy(labels = it.labels + "solo") else it
        }.map {
            if ("solo" in it.labels && it.artistKey != "artist-1") {
                it.copy(labels = it.labels - "solo")
            } else it
        }
        val songs = examples.map { song(it.songId, it.artistKey) }
        val manual = examples.associate {
            it.songId to SongStatsEntity(it.songId, styles = Styles.join(it.labels), stylesAuto = 0)
        }
        val result = StyleLearning.learn(
            songs, manual, emptyMap(),
            examples.associate { it.songId to feature(it.songId, "folk" in it.labels) }
        )
        val solo = result.styles.single { it.style == "solo" }
        assertEquals(1, solo.artists)
        assertFalse(solo.accepted)
        assertTrue(solo.reason, solo.reason.contains("אמן אחד"))
        assertTrue(StyleLearning.report(result).contains("אמנים:"))
        // And a style spread over several artists is not accused of it.
        val folk = result.styles.single { it.style == "folk" }
        assertTrue(folk.artists > 1)
        assertFalse(folk.reason.contains("אמן אחד"))
    }

    @Test fun everyStyleIsAccountedForInTheReport() {
        val result = mixedLearn()
        val text = StyleLearning.report(result)
        assertTrue("noise", text.contains("noise"))
        // Named one by one, accepted and rejected alike, with the numbers that
        // say what to do about each: a style short of examples needs tagging,
        // a style that scores badly needs rethinking.
        for (style in listOf("folk", "rock")) assertTrue(style, text.contains(style))
        assertTrue(text.contains("לפי סגנון"))
        assertTrue(text.contains("דוגמאות מתויגות"))
        assertTrue(text.contains("סף הביטחון שנמדד לסגנון"))
        assertTrue(text.contains("פוספס"))
        assertTrue(result.styles.isNotEmpty())
        assertEquals(result.styles.size, result.accepted.size + result.rejected.size)
    }

    /**
     * Two styles that track the sound exactly, and one that is noise.
     *
     * "noise" alternates inside each artist, so it is uncorrelated with
     * anything the model can measure and no split by artist can help it.
     */
    private fun mixedQuality(): List<StyleExample> = (0 until 8).flatMap { artist ->
        (0 until 16).map { index ->
            val folk = artist % 2 == 0
            StyleExample(
                (artist * 16 + index).toLong(), "artist-$artist",
                floatArrayOf(if (folk) -1f else 1f),
                listOfNotNull(if (folk) "folk" else "rock", "noise".takeIf { index % 2 == 0 })
            )
        }
    }

    @Test fun identicalSoundCannotLearnBalancedStyles() {
        val report = StyleValidation.evaluate(examples().map { it.copy(features = floatArrayOf(0f)) })!!
        assertFalse(StyleLearning.passesQuality(report))
    }

    @Test fun manySongsByOneArtistAreNotIndependentValidation() {
        assertNull(StyleValidation.evaluate(examples().map { it.copy(artistKey = "one") }))
    }

    @Test fun untrainableFoldsAreNotDroppedFromTheScore() {
        val rows = examples().map { it.copy(labels = listOf(it.artistKey)) }
        // Each training fold can fit, but the unseen artist's label is unknown:
        // every held-out label must still count as missed.
        val report = StyleValidation.evaluate(rows)!!
        assertEquals(0.0, report.metrics.macroRecall, 0.0)
        assertEquals(rows.size, report.metrics.songs)
        assertFalse(StyleLearning.passesQuality(report))
        assertNull(StyleValidation.evaluate(examples().map { it.copy(labels = listOf("same")) }))
    }

    @Test fun failedAudioAnalysisIsNotTrainingEvidence() {
        assertNull(StyleTraining.featuresFor(Analysis.blankFor(1)))
        assertNull(StyleTraining.featuresFor(null))
        assertNotNull(StyleTraining.featuresFor(Analysis.blankFor(1).copy(tags = "211:0.5")))
    }

    @Test fun rawTrainingFeaturesDoNotDependOnHeldOutLibrary() {
        val song = song(1, "artist")
        val feature = feature(1, true)
        val original = StyleTraining.rows(listOf(song), mapOf(1L to feature), mapOf("artist" to "folk"))
        val extended = StyleTraining.rows(
            listOf(song), mapOf(1L to feature, 999L to feature.copy(songId = 999, energy = 10000f)),
            mapOf("artist" to "folk")
        )
        assertArrayEquals(original.single().features, extended.single().features, 0f)
    }

    @Test fun manualSongLabelsOverrideArtistAndAutomaticLabelsNeverTrain() {
        val song = song(1, "artist")
        val features = mapOf(1L to feature(1, true))
        val manual = mapOf(1L to SongStatsEntity(1, styles = "rock", stylesAuto = 0))
        assertEquals(listOf("rock"), StyleTraining.rows(listOf(song), features, mapOf("artist" to "folk"), manual).single().labels)
        val automatic = mapOf(1L to SongStatsEntity(1, styles = "rock", stylesAuto = 1))
        assertTrue(StyleTraining.rows(listOf(song), features, emptyMap(), automatic).isEmpty())
    }

    @Test fun validLearningWithNoCandidatesIsNotReportedAsLowQuality() {
        val fixture = fixture()
        val result = StyleLearning.learn(fixture.songs, emptyMap(), fixture.styles, fixture.features)
        assertEquals(LearningStatus.NO_CANDIDATES, result.status)
        assertEquals(0, result.applied)
        assertFalse(StyleLearning.message(result).contains("נמוך"))
        assertTrue(StyleLearning.report(result).contains("F1"))
    }

    @Test fun predictsUnlabelledSongsButPreservesManualLabelsAndDoesNotCountUnchangedGuesses() {
        val fixture = fixture()
        val candidate = song(1000, "unlabelled")
        val songs = fixture.songs + candidate
        val features = fixture.features + (candidate.id to feature(candidate.id, true))
        val result = StyleLearning.learn(songs, emptyMap(), fixture.styles, features)
        assertEquals(LearningStatus.READY, result.status)
        assertEquals(listOf(candidate.id to "folk"), result.predictions)
        val own = mapOf(candidate.id to SongStatsEntity(candidate.id, styles = "rock"))
        assertTrue(StyleLearning.learn(songs, own, fixture.styles, features).predictions.isEmpty())
        val auto = mapOf(candidate.id to SongStatsEntity(candidate.id, styles = "folk", stylesAuto = 1))
        assertEquals(0, StyleLearning.learn(songs, auto, fixture.styles, features).applied)
    }

    @Test fun tooFewArtistsGetsAnActionableMessage() {
        val fixture = fixture()
        val songs = fixture.songs.map { it.copy(artistKey = "one") }
        val result = StyleLearning.learn(songs, emptyMap(), mapOf("one" to "folk"), fixture.features)
        assertEquals(LearningStatus.TOO_FEW_ARTISTS, result.status)
        assertTrue(StyleLearning.message(result).contains("4"))
    }

    private fun examples(): List<StyleExample> = (0 until 8).flatMap { artist ->
        (0 until 16).map { index ->
            StyleExample((artist * 16 + index).toLong(), "artist-$artist",
                floatArrayOf(if (artist % 2 == 0) -1f else 1f),
                listOf(if (artist % 2 == 0) "folk" else "rock"))
        }
    }

    private data class Fixture(
        val songs: List<SongEntity>, val features: Map<Long, AudioFeatureEntity>,
        val styles: Map<String, String>
    )

    private fun fixture(): Fixture {
        val examples = examples()
        return Fixture(
            examples.map { song(it.songId, it.artistKey) },
            examples.associate { it.songId to feature(it.songId, it.labels.single() == "folk") },
            examples.associate { it.artistKey to it.labels.single() }
        )
    }

    private fun feature(id: Long, folk: Boolean): AudioFeatureEntity = Analysis.blankFor(id).copy(
        energy = if (folk) 0.1f else 0.8f,
        bpm = if (folk) 70f else 150f,
        brightness = if (folk) 300f else 3000f,
        onsetRate = if (folk) 1f else 5f
    )

    private fun song(id: Long, artist: String) = SongEntity(
        id, "song-$id", "song-$id", artist, artist, "album", 1, 180000,
        1, 2026, null, "/music/$id.mp3", "/music", 0, 1000
    )
}
