package net.koalastuff.koalacast.feature.episode

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFormatterTest {
    @Test
    fun `vtt removes metadata and preserves spoken text`() {
        val input = """
            WEBVTT

            1
            00:00:01.000 --> 00:00:03.000
            <v Host>Hello there</v>

            2
            00:00:04.000 --> 00:00:05.000
            General Kenobi
        """.trimIndent()

        assertEquals("Hello there\nGeneral Kenobi", TranscriptFormatter.format("text/vtt", input))
    }

    @Test
    fun `html becomes readable plain text`() {
        assertEquals(
            "First & foremost\nSecond",
            TranscriptFormatter.format("text/html", "<p>First &amp; foremost</p><p>Second</p>"),
        )
    }

    @Test
    fun `podcasting json extracts segment bodies`() {
        assertEquals(
            "Opening\nClosing",
            TranscriptFormatter.format(
                "application/json",
                """[{"startTime":0,"body":"Opening"},{"startTime":1,"body":"Closing"}]""",
            ),
        )
    }
}
