package net.koalastuff.koalacast.core.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import net.koalastuff.koalacast.core.model.Account
import net.koalastuff.koalacast.core.network.AuthTokenProvider
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device token is encrypted with a non-exportable Android Keystore AES key.
 * Only non-secret account metadata and the sync cursor remain plain preferences.
 */
@Singleton
class SecureAccountStore @Inject constructor(
    @ApplicationContext context: Context,
) : AuthTokenProvider {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val initialToken = decryptToken()
    private val _account = MutableStateFlow(initialToken?.let { readAccount() })
    val account: StateFlow<Account?> = _account
    private val _generation = MutableStateFlow(prefs.getLong(KEY_ACCOUNT_GENERATION, 0))
    val generation: StateFlow<Long> = _generation

    @Volatile
    private var cachedToken: String? = initialToken
    @Volatile
    private var serverOrigin: String = prefs.getString(KEY_SERVER_ORIGIN, "").orEmpty()

    init {
        // A restored/corrupted ciphertext without its non-exportable Keystore key
        // must not leave the UI in a ghost signed-in state.
        if (initialToken == null && prefs.contains(KEY_TOKEN)) {
            clearStoredAccount()
        }
    }

    override fun token(): String? = cachedToken

    fun installationId(): String {
        prefs.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_INSTALLATION_ID, value) }
        return value
    }

    fun save(account: Account, token: String) {
        beginAccountTransition()
        val encrypted = encrypt(token)
        prefs.edit {
            putString(KEY_USER_ID, account.userId)
            putString(KEY_USERNAME, account.username)
            putString(KEY_ROLE, account.role)
            putString(KEY_DEVICE_ID, account.deviceId)
            putString(KEY_TOKEN, encrypted)
        }
        cachedToken = token
        _account.value = account
    }

    fun setServerOrigin(origin: String) {
        serverOrigin = origin.trimEnd('/')
        prefs.edit(commit = true) { putString(KEY_SERVER_ORIGIN, serverOrigin) }
    }

    fun migrateLegacySyncState(userId: String) {
        if (userId.isBlank()) return
        val legacyValues = listOf("cursor", "push_watermark").mapNotNull { prefix ->
            val legacy = "${prefix}_$userId"
            val target = scopedKey(prefix, userId)
            if (!prefs.contains(target) && prefs.contains(legacy)) {
                target to prefs.getLong(legacy, 0)
            } else {
                null
            }
        }
        val legacyQueue = "${KEY_QUEUE_UPDATED_AT}_$userId"
        val targetQueue = "${KEY_QUEUE_UPDATED_AT}_${ownerIdFor(userId)}"
        val queueValue = prefs.getLong(legacyQueue, 0)
            .takeIf { !prefs.contains(targetQueue) && prefs.contains(legacyQueue) }
        prefs.edit(commit = true) {
            legacyValues.forEach { (target, value) -> putLong(target, value) }
            for (prefix in listOf("cursor", "push_watermark")) {
                remove("${prefix}_$userId")
            }
            queueValue?.let { putLong(targetQueue, it) }
            remove(legacyQueue)
        }
    }

    fun clear() {
        beginAccountTransition()
        cachedToken = null
        _account.value = null
        clearStoredAccount()
    }

    private fun clearStoredAccount() {
        prefs.edit {
            remove(KEY_USER_ID)
            remove(KEY_USERNAME)
            remove(KEY_ROLE)
            remove(KEY_DEVICE_ID)
            remove(KEY_TOKEN)
        }
    }

    fun cursor(userId: String): Long = scopedLong("cursor", userId)

    fun setCursor(userId: String, cursor: Long) {
        prefs.edit { putLong(scopedKey("cursor", userId), cursor.coerceAtLeast(0)) }
    }

    fun pushWatermark(userId: String): Long = scopedLong("push_watermark", userId)

    fun setPushWatermark(userId: String, timestamp: Long) {
        prefs.edit { putLong(scopedKey("push_watermark", userId), timestamp.coerceAtLeast(0)) }
    }

    /**
     * Listening telemetry was added after the general sync watermark. Keeping a
     * separate watermark lets upgraded installations backfill older sessions
     * once instead of silently treating them as already uploaded.
     */
    fun listeningSessionPushWatermark(userId: String): Long =
        scopedLong("listening_session_push_watermark", userId)

    fun setListeningSessionPushWatermark(userId: String, timestamp: Long) {
        prefs.edit {
            putLong(
                scopedKey("listening_session_push_watermark", userId),
                timestamp.coerceAtLeast(0),
            )
        }
    }

    fun queueUpdatedAt(): Long = prefs.getLong(queueUpdatedAtKey(), 0)

    fun markQueueUpdated(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(queueUpdatedAtKey(), timestamp.coerceAtLeast(0)) }
    }

    fun resetQueueUpdatedAt(timestamp: Long = 0) {
        prefs.edit { putLong(queueUpdatedAtKey(), timestamp.coerceAtLeast(0)) }
    }

    fun activeOwnerId(): String =
        _account.value?.userId?.let { accountOwnerId(serverOrigin, it) } ?: GUEST_OWNER

    fun ownerIdFor(userId: String): String = accountOwnerId(serverOrigin, userId)

    fun accountGeneration(): Long = _generation.value

    fun beginAccountTransition(): Long {
        val next = accountGeneration() + 1
        prefs.edit(commit = true) { putLong(KEY_ACCOUNT_GENERATION, next) }
        _generation.value = next
        return next
    }

    private fun queueUpdatedAtKey(): String =
        "${KEY_QUEUE_UPDATED_AT}_${activeOwnerId()}"

    private fun scopedKey(prefix: String, userId: String) =
        "${prefix}_${accountOwnerId(serverOrigin, userId)}"

    private fun scopedLong(prefix: String, userId: String): Long =
        prefs.getLong(scopedKey(prefix, userId), 0)

    private fun readAccount(): Account? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        return Account(
            userId = userId,
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            role = prefs.getString(KEY_ROLE, "user").orEmpty(),
            deviceId = prefs.getString(KEY_DEVICE_ID, "").orEmpty(),
        )
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptToken(): String? {
        val encoded = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_BYTES)
            val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "secure_account"
        const val KEY_ALIAS = "koalacast_device_token_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_ROLE = "role"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "device_token"
        const val KEY_SERVER_ORIGIN = "server_origin"
        const val KEY_QUEUE_UPDATED_AT = "queue_updated_at"
        const val KEY_ACCOUNT_GENERATION = "account_generation"
        const val GUEST_OWNER = "guest"

        fun accountOwnerId(serverOrigin: String, userId: String): String {
            val serverHash = MessageDigest.getInstance("SHA-256")
                .digest(serverOrigin.trimEnd('/').toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "account:$serverHash:$userId"
        }
    }
}
