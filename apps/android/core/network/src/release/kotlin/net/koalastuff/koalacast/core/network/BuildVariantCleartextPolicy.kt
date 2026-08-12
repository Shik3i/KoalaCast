package net.koalastuff.koalacast.core.network

/** Release policy has no cleartext host allowlist. */
internal object BuildVariantCleartextPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun permits(host: String, debugBuild: Boolean): Boolean = false
}
