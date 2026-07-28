package net.koalastuff.koalacast.core.data.repository

import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.koalastuff.koalacast.core.data.auth.SecureAccountStore
import net.koalastuff.koalacast.core.data.di.IoDispatcher
import net.koalastuff.koalacast.core.data.util.OpmlParser
import net.koalastuff.koalacast.core.model.Account
import net.koalastuff.koalacast.core.model.AccountSession
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DataResult
import net.koalastuff.koalacast.core.model.OpmlReport
import net.koalastuff.koalacast.core.network.KoalaCastApi
import net.koalastuff.koalacast.core.network.apiCall
import net.koalastuff.koalacast.core.network.dto.DeviceLoginRequest
import net.koalastuff.koalacast.core.network.dto.GlobalStatsPreference
import net.koalastuff.koalacast.core.network.dto.RecoveryRequest
import net.koalastuff.koalacast.core.network.dto.RegisterRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val api: KoalaCastApi,
    private val store: SecureAccountStore,
    private val library: LibraryRepository,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    val account: Flow<Account?> = store.account

    suspend fun register(username: String, password: String): DataResult<String> =
        withContext(dispatcher) {
            when (val result = apiCall { api.register(RegisterRequest(username.trim(), password)) }) {
                is DataResult.Success -> DataResult.Success(result.data.recoveryCode)
                is DataResult.Failure -> result
            }
        }

    suspend fun login(username: String, password: String): DataResult<Account> =
        withContext(dispatcher) {
            when (
                val result = apiCall {
                    api.deviceLogin(
                        DeviceLoginRequest(
                            username = username.trim(),
                            password = password,
                            deviceId = store.installationId(),
                            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        ),
                    )
                }
            ) {
                is DataResult.Failure -> result
                is DataResult.Success -> {
                    val response = result.data
                    if (response.deviceToken.isBlank() || response.userId.isBlank()) {
                        DataResult.Failure(DataError.Malformed("device login omitted credentials"))
                    } else {
                        val account = Account(
                            userId = response.userId,
                            username = response.username,
                            deviceId = response.deviceId,
                        )
                        store.save(account, response.deviceToken)
                        DataResult.Success(account)
                    }
                }
            }
        }

    suspend fun recover(
        username: String,
        recoveryCode: String,
        newPassword: String,
    ): DataResult<Unit> = withContext(dispatcher) {
        when (
            val result = apiCall {
                api.recover(RecoveryRequest(username.trim(), recoveryCode.trim(), newPassword))
            }
        ) {
            is DataResult.Success -> DataResult.Success(Unit)
            is DataResult.Failure -> result
        }
    }

    suspend fun validate(): Boolean = withContext(dispatcher) {
        val response = runCatching { api.authStatus() }.getOrNull() ?: return@withContext false
        val authenticated = response.isSuccessful && response.body()?.authenticated == true
        if (!authenticated) store.clear()
        authenticated
    }

    suspend fun logout() = withContext(dispatcher) {
        runCatching { api.logout() }
        store.clear()
    }

    suspend fun sessions(): DataResult<List<AccountSession>> = withContext(dispatcher) {
        when (val result = apiCall { api.sessions() }) {
            is DataResult.Failure -> result
            is DataResult.Success -> DataResult.Success(
                result.data.sessions.map {
                    AccountSession(
                        id = it.id,
                        kind = it.kind,
                        deviceName = it.deviceName,
                        deviceType = it.deviceType,
                        truncatedIp = it.truncatedIp,
                        userAgent = it.sanitizedUserAgent,
                        createdAtMs = it.createdAt,
                        lastUsedAtMs = it.lastUsedAt,
                        isCurrent = it.isCurrent,
                    )
                },
            )
        }
    }

    suspend fun revokeSession(id: String): DataResult<Unit> = withContext(dispatcher) {
        when (val result = apiCall { api.revokeSession(id) }) {
            is DataResult.Success -> DataResult.Success(Unit)
            is DataResult.Failure -> result
        }
    }

    suspend fun globalStatsPreference(): DataResult<Boolean> = withContext(dispatcher) {
        when (val result = apiCall { api.statsPreference() }) {
            is DataResult.Success -> DataResult.Success(result.data.enabled)
            is DataResult.Failure -> result
        }
    }

    suspend fun setGlobalStatsPreference(enabled: Boolean): DataResult<Boolean> =
        withContext(dispatcher) {
            when (
                val result = apiCall {
                    api.updateStatsPreference(GlobalStatsPreference(enabled))
                }
            ) {
                is DataResult.Success -> DataResult.Success(result.data.enabled)
                is DataResult.Failure -> result
            }
        }

    suspend fun importOpml(xml: String): DataResult<OpmlReport> = withContext(dispatcher) {
        val parsedFeeds = OpmlParser.parse(xml).distinctBy { it.feedUrl }
        if (parsedFeeds.isEmpty()) {
            return@withContext DataResult.Failure(
                DataError.Malformed("OPML: no feed outlines found"),
            )
        }
        importResolvedOpml(
            xml = buildOpml(parsedFeeds.map { it.feedUrl to it.title }),
            totalFound = parsedFeeds.size,
        )
    }

    suspend fun resolvePendingSubscriptions(): DataResult<OpmlReport?> = withContext(dispatcher) {
        val pending = library.subscriptionsSnapshot()
            .filter { it.podcastId == it.feedUrl }
        if (pending.isEmpty()) return@withContext DataResult.Success(null)
        when (val result = importResolvedOpml(buildOpml(pending.map { it.feedUrl to it.title }), pending.size)) {
            is DataResult.Success -> DataResult.Success(result.data)
            is DataResult.Failure -> result
        }
    }

    suspend fun exportOpml(): DataResult<String> = withContext(dispatcher) {
        val subscriptions = library.subscriptionsSnapshot().filter { it.feedUrl.isNotBlank() }
        if (subscriptions.isEmpty()) {
            return@withContext DataResult.Failure(DataError.Malformed("no subscriptions"))
        }
        val outlines = subscriptions.joinToString("\n") {
            val title = escapeXml(it.title)
            """    <outline type="rss" text="$title" title="$title" xmlUrl="${escapeXml(it.feedUrl)}" />"""
        }
        DataResult.Success(
            """<?xml version="1.0" encoding="UTF-8"?>
                |<opml version="2.0">
                |  <head><title>KoalaCast Subscriptions</title></head>
                |  <body>
                |$outlines
                |  </body>
                |</opml>
                |""".trimMargin(),
        )
    }

    private fun escapeXml(value: String): String = buildString {
        value.forEach {
            append(
                when (it) {
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '&' -> "&amp;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> it
                },
            )
        }
    }

    private suspend fun importResolvedOpml(xml: String, totalFound: Int): DataResult<OpmlReport> {
        val existingByFeed = library.subscriptionsSnapshot().associateBy { it.feedUrl }
        val body = xml.toRequestBody("application/xml".toMediaType())
        return when (val result = apiCall { api.importOpml(body) }) {
            is DataResult.Failure -> result
            is DataResult.Success -> {
                library.subscribeResolvedImports(result.data.podcasts)
                val imported = result.data.podcasts.count { podcast ->
                    (
                        existingByFeed[podcast.sourceUrl.ifBlank { podcast.feedUrl }]
                            ?: existingByFeed[podcast.feedUrl]
                    )
                        ?.let { it.podcastId != it.feedUrl } != true
                }
                DataResult.Success(
                    OpmlReport(
                        totalFound = totalFound,
                        imported = imported,
                        skipped = result.data.skipped + result.data.podcasts.size - imported,
                        failures = result.data.failures.map { "${it.url}: ${it.reason}" },
                    ),
                )
            }
        }
    }

    private fun buildOpml(feeds: List<Pair<String, String>>): String {
        val outlines = feeds.joinToString("\n") { (feedUrl, title) ->
            val escapedTitle = escapeXml(title)
            """    <outline type="rss" text="$escapedTitle" title="$escapedTitle" xmlUrl="${escapeXml(feedUrl)}" />"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
            |<opml version="2.0">
            |  <body>
            |$outlines
            |  </body>
            |</opml>
            |""".trimMargin()
    }
}
