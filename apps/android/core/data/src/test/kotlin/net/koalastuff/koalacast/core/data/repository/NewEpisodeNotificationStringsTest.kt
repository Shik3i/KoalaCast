package net.koalastuff.koalacast.core.data.repository

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The new-episode notification is the one piece of text a listener sees without
 * opening the app, and it used to be built from English literals in the worker,
 * so a German listener got English there and nowhere else.
 *
 * This reads the resource XML directly rather than resolving through Android.
 * Robolectric can do the latter, but only with merged resources on the test
 * classpath, and that makes it derive its SDK from the merged manifest — which
 * pins the whole module to an API level that has to match the JDK the build
 * runs on. CI is on JDK 17 and the app targets API 37; tying these tests to
 * that relationship broke every Robolectric test in the module the moment the
 * two drifted apart. Nothing here needs an Android runtime: the two failure
 * modes worth guarding are a key that was never translated and a placeholder
 * set that disagrees between locales, and both are visible in the files.
 */
class NewEpisodeNotificationStringsTest {

    private val notificationKeys = listOf(
        "new_episodes_channel_name",
        "new_episodes_title",
        "new_episodes_single",
    )

    private fun resourceFile(qualifier: String): File {
        // Gradle runs unit tests with the module directory as the working
        // directory, so this stays correct wherever the checkout lives.
        val file = File("src/main/res/$qualifier/strings.xml")
        assertTrue("missing resource file: ${file.absolutePath}", file.isFile)
        return file
    }

    private fun strings(qualifier: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(resourceFile(qualifier))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private fun plurals(qualifier: String): Map<String, Map<String, String>> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(resourceFile(qualifier))
        val nodes = document.getElementsByTagName("plurals")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { plural ->
                val items = plural.getElementsByTagName("item")
                plural.getAttribute("name") to (0 until items.length)
                    .map { items.item(it) as Element }
                    .associate { it.getAttribute("quantity") to it.textContent }
            }
    }

    /** Every `%1$s` / `%2$d` style placeholder, in order of appearance. */
    private fun placeholders(value: String): List<String> =
        Regex("""%\d+\$[sd]""").findAll(value).map { it.value }.toList()

    @Test
    fun `german translates every notification string`() {
        val english = strings("values")
        val german = strings("values-de")
        for (key in notificationKeys) {
            assertTrue("English is missing $key", key in english)
            assertTrue("German never translated $key", key in german)
        }
        // A translation that is byte-identical to English is the fallback in
        // disguise. The one exception is the line that is only placeholders.
        assertTrue(
            "new_episodes_title was left in English",
            german.getValue("new_episodes_title") != english.getValue("new_episodes_title"),
        )
        assertTrue(
            "new_episodes_channel_name was left in English",
            german.getValue("new_episodes_channel_name") !=
                english.getValue("new_episodes_channel_name"),
        )
    }

    @Test
    fun `placeholders agree between locales`() {
        val english = strings("values")
        val german = strings("values-de")
        for (key in notificationKeys) {
            assertEquals(
                "placeholder mismatch in $key would throw while building the notification",
                placeholders(english.getValue(key)),
                placeholders(german.getValue(key)),
            )
        }
    }

    @Test
    fun `the show summary plural is complete and takes both arguments`() {
        val englishPlural = plurals("values").getValue("new_episodes_shows")
        val germanPlural = plurals("values-de").getValue("new_episodes_shows")

        // getQuantityString is called with (episodeCount, showCount); "one" is
        // selected by the show count, so both forms must accept both arguments.
        for ((quantity, value) in englishPlural) {
            assertTrue("English $quantity form must use the episode count", "%1\$d" in value)
        }
        for (quantity in listOf("one", "other")) {
            assertTrue("German is missing the $quantity form", quantity in germanPlural)
            assertTrue(
                "German $quantity form must use the episode count",
                "%1\$d" in germanPlural.getValue(quantity),
            )
            assertEquals(
                "placeholder mismatch in the $quantity form",
                placeholders(englishPlural.getValue(quantity)),
                placeholders(germanPlural.getValue(quantity)),
            )
        }
        assertTrue(
            "the German plural was left in English",
            germanPlural.getValue("other") != englishPlural.getValue("other"),
        )
    }
}
