package net.koalastuff.koalacast.core.data.server

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turning what a listener types into something OkHttp can use. People paste
 * `cast.koalastuff.net`, `https://cast.koalastuff.net/`, or `192.168.1.10:3000` —
 * all three have to work.
 */
object ServerUrl {

    /** Returns a normalised absolute URL, or `null` if the input cannot be one. */
    fun normalise(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // Default to TLS. A LAN instance that only speaks HTTP needs the
            // scheme typed out, which keeps the insecure choice explicit.
            else -> "https://$trimmed"
        }

        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        return url.toString().trimEnd('/')
    }

    fun parse(raw: String): HttpUrl? = normalise(raw)?.toHttpUrlOrNull()

    /** True when traffic to this origin would travel unencrypted. */
    fun isCleartext(raw: String): Boolean =
        normalise(raw)?.startsWith("http://", ignoreCase = true) == true
}
