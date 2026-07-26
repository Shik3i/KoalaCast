package net.koalastuff.koalacast.core.model

/**
 * Repositories never throw for expected failures. A screen renders exactly one of
 * these, which is why every error carries a machine-readable [DataError] rather than
 * a pre-baked message string — the UI layer owns the wording (and its translation).
 */
sealed interface DataResult<out T> {
    data class Success<T>(val data: T) : DataResult<T>
    data class Failure(val error: DataError) : DataResult<Nothing>
}

sealed interface DataError {
    /** No route to the server: airplane mode, wrong host, DNS, TLS. */
    data class Network(val cause: String) : DataError

    /** The server answered, but not with a 2xx. */
    data class Http(val code: Int, val body: String?) : DataError

    /** 2xx with a body we could not read — usually a proxy or captive portal. */
    data class Malformed(val cause: String) : DataError

    /** No server has been chosen yet. */
    data object NoServerConfigured : DataError
}

inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(data))
    is DataResult.Failure -> this
}

fun <T> DataResult<T>.getOrNull(): T? = (this as? DataResult.Success)?.data
