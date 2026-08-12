package net.koalastuff.koalacast.core.model

/** Provider metadata is intentionally tri-state: missing is not the same as clean. */
enum class ExplicitRating { CLEAN, EXPLICIT, UNKNOWN }

val Boolean?.explicitRating: ExplicitRating
    get() = when (this) {
        true -> ExplicitRating.EXPLICIT
        false -> ExplicitRating.CLEAN
        null -> ExplicitRating.UNKNOWN
    }

/** Only content positively marked explicit is blocked. Unknown remains available. */
fun Boolean?.isAllowedByExplicitPreference(includeExplicit: Boolean): Boolean =
    includeExplicit || this != true
