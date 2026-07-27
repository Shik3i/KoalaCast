package net.koalastuff.koalacast.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptContentDto(
    val type: String = "text/plain",
    val content: String,
)
