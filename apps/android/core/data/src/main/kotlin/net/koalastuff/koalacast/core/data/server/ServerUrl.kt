package net.koalastuff.koalacast.core.data.server

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import net.koalastuff.koalacast.core.network.TransportSecurityPolicy

/**
 * Turning what a listener types into something OkHttp can use. People paste
 * `cast.koalastuff.net` or `https://cast.koalastuff.net/`. Release builds accept
 * HTTPS only; debug builds additionally accept the three explicit loopback hosts.
 */
object ServerUrl {

    data class StoredValue(val value: String, val resetFromCleartext: Boolean)

    /** Returns a normalised absolute URL, or `null` if the input cannot be one. */
    fun normalise(
        raw: String,
        debugBuild: Boolean = TransportSecurityPolicy.allowsDebugCleartext,
    ): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // A missing scheme is always upgraded to TLS.
            else -> "https://$trimmed"
        }

        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (
            url.host.isBlank() ||
            url.username.isNotEmpty() ||
            url.password.isNotEmpty() ||
            url.query != null ||
            url.fragment != null ||
            !TransportSecurityPolicy.permits(url, debugBuild)
        ) return null
        return url.toString().trimEnd('/')
    }

    fun parse(raw: String): HttpUrl? = normalise(raw)?.toHttpUrlOrNull()

    /** True for an explicit HTTP scheme, including addresses this build rejects. */
    fun isCleartext(raw: String): Boolean =
        raw.trim().startsWith("http://", ignoreCase = true)

    fun rejectsCleartext(
        raw: String,
        debugBuild: Boolean = TransportSecurityPolicy.allowsDebugCleartext,
    ): Boolean = isCleartext(raw) && normalise(raw, debugBuild) == null

    val supportsEmulatorLoopback: Boolean
        get() = TransportSecurityPolicy.allowsDebugCleartext

    fun sanitizeStored(
        raw: String,
        fallback: String,
        debugBuild: Boolean = TransportSecurityPolicy.allowsDebugCleartext,
    ): StoredValue = when {
        rejectsCleartext(raw, debugBuild) -> StoredValue(fallback, resetFromCleartext = true)
        else -> StoredValue(normalise(raw, debugBuild) ?: fallback, resetFromCleartext = false)
    }

    const val CLEARTEXT_REJECTED = "cleartext transport not permitted"
}
