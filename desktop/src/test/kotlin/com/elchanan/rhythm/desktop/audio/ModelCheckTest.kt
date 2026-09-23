package com.elchanan.rhythm.desktop.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop's models hear the test signal the way the phone's do. See [ModelCheck]. */
class ModelCheckTest {

    @Test fun theModelsHearWhatThePhoneHears() {
        val r = ModelCheck.run()
        println(r.lines.joinToString("\n"))
        assertTrue(r.lines.joinToString("\n"), r.ok)
    }

    @Test fun everyHeadIsReadFromHeadsJson() {
        val json = ModelCheck::class.java.getResourceAsStream("/models/heads.json")!!.readBytes().toString(Charsets.UTF_8)
        val heads = Models.parseHeads(json)
        assertEquals(8, heads.size)
        assertEquals("mood_happy" to listOf("happy", "non_happy"), heads.first())
    }
}
