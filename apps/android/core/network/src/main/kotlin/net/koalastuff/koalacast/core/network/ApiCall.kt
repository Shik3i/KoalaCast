package net.koalastuff.koalacast.core.network

import kotlinx.serialization.SerializationException
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import retrofit2.Response
import java.io.IOException

/**
 * Turns a Retrofit call into a [DataResult]. Every repository goes through here, so an
 * unreachable server, a 429 from the discovery rate limiter and a truncated body all
 * arrive at the UI as data instead of as a crash.
 */
suspend fun <T : Any> apiCall(block: suspend () -> Response<T>): DataResult<T> = try {
    val response = block()
    if (response.isSuccessful) {
        val body = response.body()
        if (body != null) {
            DataResult.Success(body)
        } else {
            DataResult.Failure(DataError.Malformed("empty body"))
        }
    } else {
        DataResult.Failure(
            DataError.Http(
                code = response.code(),
                body = runCatching { response.errorBody()?.string() }.getOrNull(),
            ),
        )
    }
} catch (e: IOException) {
    DataResult.Failure(DataError.Network(e.message ?: e.javaClass.simpleName))
} catch (e: SerializationException) {
    DataResult.Failure(DataError.Malformed(e.message ?: e.javaClass.simpleName))
}
