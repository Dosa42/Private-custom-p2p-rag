package com.example.trinity.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OpenAIOAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val accountId: String,
    val expiresAtEpochSeconds: Long
) {
    fun encodeForStorage(): String = JSONObject().apply {
        put("type", "codex_oauth")
        put("access_token", accessToken)
        put("refresh_token", refreshToken)
        put("id_token", idToken)
        put("account_id", accountId)
        put("expires_at", expiresAtEpochSeconds)
    }.toString()

    val isExpired: Boolean
        get() {
            val now = System.currentTimeMillis() / 1000L
            return expiresAtEpochSeconds in 1..now
        }

    val expiresInSeconds: Long
        get() {
            val now = System.currentTimeMillis() / 1000L
            return (expiresAtEpochSeconds - now).coerceAtLeast(0)
        }
}

/**
 * ChatGPT / OpenAI OAuth 2.0 with PKCE session manager for Trinity Core.
 * Uses system browser login and local loopback callback server on 127.0.0.1:1455.
 */
object OpenAIOAuthManager {
    private val refreshMutex = Mutex()
    private val sessionLock = Any()
    const val DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val AUTH_ENDPOINT = "https://auth.openai.com/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
    const val REDIRECT_URI = "http://localhost:1455/auth/callback"
    const val CALLBACK_PORT = 1455

    private const val PREFS_NAME = "trinity_openai_oauth"
    private const val KEY_SESSION = "stored_session"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    suspend fun authenticate(
        context: Context,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Result<OpenAIOAuthSession> = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            val codeVerifier = generateCodeVerifier()
            val state = UUID.randomUUID().toString()

            val authUrl = Uri.parse(AUTH_ENDPOINT).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", DEFAULT_CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter(
                    "scope",
                    "openid profile email offline_access api.connectors.read api.connectors.invoke"
                )
                .appendQueryParameter("code_challenge", generateCodeChallenge(codeVerifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("id_token_add_organizations", "true")
                .appendQueryParameter("codex_cli_simplified_flow", "true")
                .appendQueryParameter("originator", "codex_cli_rs")
                .appendQueryParameter("state", state)
                .build()

            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", CALLBACK_PORT))
                soTimeout = 180_000 // 3 minutes timeout
            }

            onStatusUpdate?.invoke("Launching system browser for ChatGPT PKCE login...")

            context.startActivity(
                Intent(Intent.ACTION_VIEW, authUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            onStatusUpdate?.invoke("Waiting for OAuth authorization callback on 127.0.0.1:$CALLBACK_PORT...")

            val socket = serverSocket.accept()
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val requestLine = reader.readLine().orEmpty()
            while (!reader.readLine().isNullOrEmpty()) Unit

            val requestTarget = requestLine.split(' ').getOrNull(1).orEmpty()
            val callbackUri = Uri.parse("http://localhost$requestTarget")
            val returnedState = callbackUri.getQueryParameter("state")
            val code = callbackUri.getQueryParameter("code")
            val oauthError = callbackUri.getQueryParameter("error_description")
                ?: callbackUri.getQueryParameter("error")

            val validState = returnedState == state
            val success = validState && !code.isNullOrBlank() && oauthError.isNullOrBlank()

            val responseHtml = if (success) {
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Trinity Core - ChatGPT OAuth Success</title>
                </head>
                <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0A0D14; color: #00F0FF; text-align: center; padding: 48px 24px;">
                    <div style="max-width: 420px; margin: 0 auto; background: #131A24; border: 1px solid #1E293B; border-radius: 16px; padding: 32px 24px; box-shadow: 0 12px 30px rgba(0,0,0,0.5);">
                        <h2 style="color: #00F0FF; margin-top: 0;">✓ ChatGPT OAuth PKCE Verified</h2>
                        <p style="color: #94A3B8; font-size: 14px; line-height: 1.6;">OAuth PKCE token exchange successful. You can safely close this browser tab and return to <strong>Trinity Core</strong>.</p>
                        <div style="margin-top: 24px; display: inline-block; padding: 8px 16px; background: rgba(0, 240, 255, 0.1); border-radius: 8px; font-family: monospace; font-size: 12px; color: #00F0FF;">127.0.0.1:1455 OK</div>
                    </div>
                </body>
                </html>
                """.trimIndent()
            } else {
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Trinity Core - OAuth Failed</title>
                </head>
                <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0A0D14; color: #FF4757; text-align: center; padding: 48px 24px;">
                    <div style="max-width: 420px; margin: 0 auto; background: #131A24; border: 1px solid #1E293B; border-radius: 16px; padding: 32px 24px;">
                        <h2 style="color: #FF4757; margin-top: 0;">✗ Login Failed</h2>
                        <p style="color: #94A3B8; font-size: 14px;">Authorization could not be completed (${oauthError ?: "Cancelled"}). Please return to Trinity Core and retry.</p>
                    </div>
                </body>
                </html>
                """.trimIndent()
            }

            val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: ${responseHtml.toByteArray().size}\r\n\r\n$responseHtml"
            socket.getOutputStream().use { output ->
                output.write(httpResponse.toByteArray())
                output.flush()
            }
            socket.close()
            serverSocket.close()

            when {
                !validState -> Result.failure(Exception("OAuth state mismatch; rejected for CSRF safety."))
                !oauthError.isNullOrBlank() -> Result.failure(Exception("ChatGPT login rejected: $oauthError"))
                code.isNullOrBlank() -> Result.failure(Exception("No authorization code received."))
                else -> {
                    onStatusUpdate?.invoke("Exchanging authorization code for tokens...")
                    val tokenResult = exchangeCodeForTokens(code, codeVerifier)
                    tokenResult.onSuccess { session ->
                        saveSession(context, session)
                        onStatusUpdate?.invoke("ChatGPT OAuth session active.")
                    }
                    tokenResult
                }
            }
        } catch (e: Exception) {
            try { serverSocket?.close() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    fun saveSession(context: Context, session: OpenAIOAuthSession) = synchronized(sessionLock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SESSION, session.encodeForStorage()).apply()
    }

    fun loadSession(context: Context): OpenAIOAuthSession? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        return decodeStoredSession(stored)
    }

    fun clearSession(context: Context) = synchronized(sessionLock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SESSION).apply()
    }

    fun decodeStoredSession(stored: String): OpenAIOAuthSession? {
        return try {
            val obj = JSONObject(stored)
            if (obj.optString("type") != "codex_oauth") return null
            val accessToken = obj.optString("access_token")
            val idToken = obj.optString("id_token")
            val accountId = obj.optString("account_id").ifBlank {
                extractAccountId(idToken).orEmpty()
            }
            if (accessToken.isBlank() || accountId.isBlank()) return null
            OpenAIOAuthSession(
                accessToken = accessToken,
                refreshToken = obj.optString("refresh_token"),
                idToken = idToken,
                accountId = accountId,
                expiresAtEpochSeconds = obj.optLong("expires_at", jwtExpiry(accessToken) ?: 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun refreshIfNeeded(
        context: Context,
        rejectedAccessToken: String? = null
    ): Result<OpenAIOAuthSession> = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val session = loadSession(context)
                    ?: return@withContext Result.failure(Exception("No stored ChatGPT session. Please log in."))
                val now = System.currentTimeMillis() / 1000L
                val force = rejectedAccessToken != null && rejectedAccessToken == session.accessToken
                if (!force && (session.expiresAtEpochSeconds == 0L || session.expiresAtEpochSeconds > now + 300L)) {
                    return@withContext Result.success(session)
                }
                if (session.refreshToken.isBlank()) {
                    return@withContext Result.failure(Exception("ChatGPT session expired. Please log in again."))
                }
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", DEFAULT_CLIENT_ID)
                    .add("refresh_token", session.refreshToken).build()
                val updated = exchangeTokenRequest(body, previous = session).getOrThrow()
                synchronized(sessionLock) {
                    if (loadSession(context) != session) {
                        Result.failure(Exception("ChatGPT session changed during refresh. Please retry."))
                    } else {
                        saveSession(context, updated)
                        Result.success(updated)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun exchangeCodeForTokens(
        code: String,
        verifier: String
    ): Result<OpenAIOAuthSession> {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", DEFAULT_CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()
        return exchangeTokenRequest(body, previous = null)
    }

    private fun exchangeTokenRequest(
        body: FormBody,
        previous: OpenAIOAuthSession?
    ): Result<OpenAIOAuthSession> {
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    val json = JSONObject(rawBody)
                    json.optString("error_description").ifBlank { json.optString("error") }
                } catch (_: Exception) { "" }
                return Result.failure(
                    Exception("OAuth token request failed (HTTP ${response.code})${if (detail.isNotBlank()) ": $detail" else ""}")
                )
            }

            return try {
                val obj = JSONObject(rawBody)
                val accessToken = obj.optString("access_token")
                val refreshToken = obj.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() }
                val idToken = obj.optString("id_token").ifBlank { previous?.idToken.orEmpty() }
                val accountId = extractAccountId(idToken)
                    ?: extractAccountId(accessToken)
                    ?: previous?.accountId
                    ?: ""
                val expiresAt = jwtExpiry(accessToken)
                    ?: ((System.currentTimeMillis() / 1000L) + obj.optLong("expires_in", 3600L))

                if (accessToken.isBlank() || accountId.isBlank()) {
                    Result.failure(Exception("OAuth response missing access_token or ChatGPT account ID."))
                } else {
                    Result.success(OpenAIOAuthSession(accessToken, refreshToken, idToken, accountId, expiresAt))
                }
            } catch (e: Exception) {
                Result.failure(Exception("OAuth token response could not be parsed: ${e.message}"))
            }
        }
    }

    internal fun extractAccountId(jwt: String): String? {
        return jwtPayload(jwt)?.optJSONObject("https://api.openai.com/auth")
            ?.optString("chatgpt_account_id")
            ?.takeIf { it.isNotBlank() }
    }

    private fun jwtExpiry(jwt: String): Long? = jwtPayload(jwt)?.optLong("exp")?.takeIf { it > 0L }

    private fun jwtPayload(jwt: String): JSONObject? {
        return try {
            val payload = jwt.split('.').getOrNull(1) ?: return null
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(String(decoded, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
