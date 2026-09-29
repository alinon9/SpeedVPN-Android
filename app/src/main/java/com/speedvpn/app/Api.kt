package com.speedvpn.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private val JSON = "application/json".toMediaType()
val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

class ApiException(val code: Int, msg: String) : Exception(msg)

/** Sign-in with the same account as the website (email + password). */
object Auth {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun isSignedIn(ctx: Context) = prefs(ctx).getString("refresh", null) != null
    fun email(ctx: Context) = prefs(ctx).getString("email", null)

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("device_id", null) ?: ("android-" + UUID.randomUUID()).also {
            p.edit().putString("device_id", it).apply()
        }
    }

    suspend fun signIn(ctx: Context, email: String, password: String) =
        token(ctx, "password", JSONObject().put("email", email).put("password", password), email)

    fun signOut(ctx: Context) = prefs(ctx).edit().remove("access").remove("refresh").remove("exp").apply()

    suspend fun accessToken(ctx: Context): String {
        val p = prefs(ctx)
        val access = p.getString("access", null)
        if (access != null && p.getLong("exp", 0) - System.currentTimeMillis() / 1000 > 60) return access
        val refresh = p.getString("refresh", null) ?: throw ApiException(401, "Not signed in")
        token(ctx, "refresh_token", JSONObject().put("refresh_token", refresh), null)
        return p.getString("access", null)!!
    }

    private suspend fun token(ctx: Context, grant: String, body: JSONObject, email: String?) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${BuildConfig.AUTH_URL}/token?grant_type=$grant")
            .header("apikey", BuildConfig.AUTH_KEY)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                if (grant == "refresh_token") signOut(ctx)
                val msg = runCatching { JSONObject(text).optString("error_description", JSONObject(text).optString("msg")) }.getOrNull()
                throw ApiException(res.code, msg?.ifBlank { null } ?: "Sign-in failed (${res.code})")
            }
            val j = JSONObject(text)
            prefs(ctx).edit()
                .putString("access", j.getString("access_token"))
                .putString("refresh", j.getString("refresh_token"))
                .putLong("exp", System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600))
                .apply { if (email != null) putString("email", email) }
                .apply()
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
