package net.koalastuff.koalacast.feature.episode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object TranscriptFormatter {
    private val cueTiming = Regex(
        """^\s*(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{3}\s*-->\s*(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{3}.*$""",
    )
    private val tags = Regex("<[^>]+>")
    private val entities = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
    )

    fun format(type: String, content: String): String {
        val normalizedType = type.lowercase()
        return when {
            "json" in normalizedType -> json(content)
            "html" in normalizedType -> html(content)
            "vtt" in normalizedType || "srt" in normalizedType -> cues(content)
            else -> content.trim()
        }
    }

    private fun cues(content: String): String = content
        .lineSequence()
        .map(String::trim)
        .filterNot { line ->
            line.isBlank() ||
                line == "WEBVTT" ||
                line.all(Char::isDigit) ||
                cueTiming.matches(line) ||
                line.startsWith("NOTE")
        }
        .joinToString("\n")
        .replace(tags, "")
        .trim()

    private fun html(content: String): String {
        var result = content
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
            .replace(tags, "")
        entities.forEach { (entity, replacement) -> result = result.replace(entity, replacement) }
        return result.lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun json(content: String): String = runCatching {
        val root = Json.parseToJsonElement(content)
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["segments"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        items.mapNotNull { item ->
            when (item) {
                is JsonObject -> sequenceOf("body", "text")
                    .mapNotNull { key -> (item[key] as? JsonPrimitive)?.contentOrNull }
                    .firstOrNull(String::isNotBlank)
                is JsonPrimitive -> item.contentOrNull
                else -> null
            }
        }.joinToString("\n")
    }.getOrElse { content.trim() }
}
