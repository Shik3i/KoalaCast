package net.koalastuff.koalacast.core.network

/** Debug-only loopback exception. This source set is absent from release artifacts. */
internal object BuildVariantCleartextPolicy {
    private val hosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun permits(host: String, debugBuild: Boolean): Boolean =
        debugBuild && host.lowercase() in hosts
}
