package com.speedvpn.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val JSON = "application/json".toMediaType()
val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

class ApiException(val code: Int, msg: String) : Exception(msg)

private object AuthStateLock {
    // Serializes generation check + token persistence with sign-out.
    // Never hold this lock across network I/O.
    val lock = ReentrantLock()
}

private object SecureTokenStore {
    private const val PREFS = "auth"
    private const val KEY_ALIAS = "SpeedVPN.AuthTokenKey.v1"
    private const val ENC_PREFIX = "v1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
        return (ks.getKey(KEY_ALIAS, null) as SecretKey)
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(encrypted, 0, payload, iv.size, encrypted.size)
        return ENC_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        require(value.startsWith(ENC_PREFIX)) { "Unsupported token format" }
        val payload = Base64.decode(value.removePrefix(ENC_PREFIX), Base64.DEFAULT)
        require(payload.size > 12) { "Invalid encrypted token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, payload, 0, 12))
        return cipher.doFinal(payload, 12, payload.size - 12).toString(Charsets.UTF_8)
    }

    fun read(prefs: android.content.SharedPreferences, encryptedKey: String, legacyKey: String): String? {
        // Serialize both reads and legacy migration with sign-out/token persistence.
        // This avoids returning a token that was concurrently invalidated after the
        // initial SharedPreferences read but before decrypt/migration completed.
        return AuthStateLock.lock.withLock {
            val encrypted = prefs.getString(encryptedKey, null)
            if (!encrypted.isNullOrBlank()) {
                return@withLock runCatching { decrypt(encrypted) }.getOrNull()
            }

            // Migration must share the same lock as signOut() and token persistence.
            // Otherwise signOut can delete the legacy value while this thread later
            // recreates the encrypted token from its stale read.

            val legacy = prefs.getString(legacyKey, null)
            if (legacy.isNullOrBlank()) return@withLock null

            try {
                val ciphertext = encrypt(legacy)
                val committed = prefs.edit()
                    .putString(encryptedKey, ciphertext)
                    .remove(legacyKey)
                    .commit()
                if (!committed) {
                    android.util.Log.e("SecureTokenStore", "Legacy migration commit failed for $legacyKey")
                }
                legacy
            } catch (t: Throwable) {
                android.util.Log.e("SecureTokenStore", "Legacy migration failed for $legacyKey", t)
                null
            }
        }
    }
}

/** Sign-in with the same account as the website (email + password). */
object Auth {
    private val tokenRefreshLock = Mutex()
    // Invalidates an in-flight sign-in/refresh when the user explicitly signs out.
    // The network call may still finish, but its response must not repopulate tokens.
    private val authGeneration = AtomicLong(0L)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun isSignedIn(ctx: Context) = SecureTokenStore.read(prefs(ctx), "refresh_e", "refresh") != null
    fun email(ctx: Context) = prefs(ctx).getString("email", null)

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("device_id", null) ?: ("android-" + UUID.randomUUID()).also {
            p.edit().putString("device_id", it).commit()
        }
    }

    suspend fun signIn(ctx: Context, email: String, password: String) =
        tokenRefreshLock.withLock {
            token(ctx, "password", JSONObject().put("email", email).put("password", password), email)
        }

    suspend fun signOut(ctx: Context) = withContext(Dispatchers.IO) {
        AuthStateLock.lock.lock()
        try {
            // Invalidate in-flight sign-in/refresh while holding the same lock used
            // by the response commit, so generation check + persistence are atomic.
            authGeneration.incrementAndGet()
            val ok = prefs(ctx).edit()
                .remove("access_e").remove("refresh_e")
                .remove("access").remove("refresh")
                .remove("exp").remove("email")
                .commit()
            if (!ok) throw IllegalStateException("Failed to persist sign-out")
        } finally {
            AuthStateLock.lock.unlock()
        }
    }

    suspend fun accessToken(ctx: Context): String = tokenRefreshLock.withLock {
        val p = prefs(ctx)
        val access = SecureTokenStore.read(p, "access_e", "access")
        if (access != null && p.getLong("exp", 0) - System.currentTimeMillis() / 1000 > 60) return@withLock access
        val refresh = SecureTokenStore.read(p, "refresh_e", "refresh") ?: throw ApiException(401, "Not signed in")
        token(ctx, "refresh_token", JSONObject().put("refresh_token", refresh), null)
        SecureTokenStore.read(p, "access_e", "access") ?: throw ApiException(401, "Token refresh returned no access token")
    }

    private suspend fun token(ctx: Context, grant: String, body: JSONObject, email: String?) = withContext(Dispatchers.IO) {
        val requestGeneration = authGeneration.get()
        val req = Request.Builder()
            .url("${BuildConfig.AUTH_URL}/token?grant_type=$grant")
            .header("apikey", BuildConfig.AUTH_KEY)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                if (grant == "refresh_token" && (res.code == 400 || res.code == 401 || res.code == 403)) signOut(ctx)
                val msg = runCatching { JSONObject(text).optString("error_description", JSONObject(text).optString("msg")) }.getOrNull()
                throw ApiException(res.code, msg?.ifBlank { null } ?: "Sign-in failed (${res.code})")
            }
            val j = JSONObject(text)
            AuthStateLock.lock.lock()
            try {
                // The generation check and the persistence commit must be in the
                // same critical section as signOut(), otherwise signOut can race
                // between this check and editor.apply()/commit().
                if (authGeneration.get() != requestGeneration) {
                    throw ApiException(401, "Authentication changed while the request was in flight")
                }
                val editor = prefs(ctx).edit()
                    .putString("access_e", SecureTokenStore.encrypt(j.getString("access_token")))
                    .putString("refresh_e", SecureTokenStore.encrypt(j.getString("refresh_token")))
                    .remove("access").remove("refresh")
                    .putLong("exp", System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600))
                if (email != null) editor.putString("email", email)
                if (!editor.commit()) {
                    throw ApiException(500, "Failed to persist authentication tokens")
                }
            } finally {
                AuthStateLock.lock.unlock()
            }
        }
    }
}

/** Dashboard backend (docs/ANDROID_CLIENT_CONTRACT.md). */
class Api(private val ctx: Context) {
    suspend fun get(path: String): JSONObject = call(path, null)
    suspend fun post(path: String, body: JSONObject): JSONObject = call(path, body)

    private suspend fun call(path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val token = Auth.accessToken(ctx)
        val b = Request.Builder().url(BuildConfig.API_BASE + path).header("Authorization", "Bearer $token")
        if (body != null) b.post(body.toString().toRequestBody(JSON))
        http.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, "$path -> ${res.code} $text".take(300))
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
