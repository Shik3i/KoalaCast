package net.koalastuff.koalacast.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import net.koalastuff.koalacast.core.data.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The new-episode notification is the one part of the app a listener sees
 * without opening it, and it used to be the one part that ignored their
 * language: every string was an English literal in the worker.
 *
 * These also pin the format arguments. A plural whose placeholders disagree with
 * the call site does not fail to compile — it throws while building the
 * notification, in a background worker, where nobody sees it.
 */
@RunWith(RobolectricTestRunner::class)
class NewEpisodeNotificationStringsTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "en")
    fun `english strings resolve and format`() {
        val context = context()
        assertEquals("New in KoalaCast", context.getString(R.string.new_episodes_title))
        assertEquals(
            "The Vergecast: Episode 1",
            context.getString(R.string.new_episodes_single, "The Vergecast", "Episode 1"),
        )
        assertEquals(
            "5 new episodes from 2 shows",
            context.resources.getQuantityString(R.plurals.new_episodes_shows, 2, 5, 2),
        )
    }

    @Test
    @Config(qualifiers = "de")
    fun `german strings are translated, not the english fallback`() {
        val context = context()
        val title = context.getString(R.string.new_episodes_title)
        assertEquals("Neu in KoalaCast", title)
        assertEquals(
            "Neue Podcast-Folgen",
            context.getString(R.string.new_episodes_channel_name),
        )

        val summary = context.resources.getQuantityString(R.plurals.new_episodes_shows, 2, 5, 2)
        assertEquals("5 neue Folgen von 2 Podcasts", summary)
        assertTrue(
            "the German summary must not fall back to English",
            !summary.contains("new episodes"),
        )
    }

    @Test
    @Config(qualifiers = "de")
    fun `the single-episode line carries both arguments in german`() {
        val line = context().getString(R.string.new_episodes_single, "Crime Junkie", "Folge 12")
        assertEquals("Crime Junkie: Folge 12", line)
    }

    @Test
    @Config(qualifiers = "de")
    fun `one show still reads correctly`() {
        val summary = context().resources.getQuantityString(R.plurals.new_episodes_shows, 1, 3, 1)
        assertEquals("3 neue Folgen von einem Podcast", summary)
        assertEquals(Locale.GERMAN.language, context().resources.configuration.locales[0].language)
    }
}
