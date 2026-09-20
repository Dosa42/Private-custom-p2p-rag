package com.example.trinity.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
import java.net.Socket
import java.net.SocketTimeoutException
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
    private val loginMutex = Mutex()
    private val sessionLock = Any()
    private var logoutGeneration = 0L
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
    ): Result<OpenAIOAuthSession> = loginMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val codeVerifier = generateCodeVerifier()
                val state = UUID.randomUUID().toString()
                val attemptGeneration = synchronized(sessionLock) { logoutGeneration }
                val authUrl = Uri.parse(AUTH_ENDPOINT).buildUpon()
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("client_id", DEFAULT_CLIENT_ID)
                    .appendQueryParameter("redirect_uri", REDIRECT_URI)
                    .appendQueryParameter("scope", "openid profile email offline_access api.connectors.read api.connectors.invoke")
                    .appendQueryParameter("code_challenge", generateCodeChallenge(codeVerifier))
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("id_token_add_organizations", "true")
                    .appendQueryParameter("codex_cli_simplified_flow", "true")
                    .appendQueryParameter("originator", "codex_cli_rs")
                    .appendQueryParameter("state", state)
                    .build()
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress("127.0.0.1", CALLBACK_PORT))
                    server.soTimeout = 1000
                    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
                    onStatusUpdate?.invoke("Launching system browser for ChatGPT PKCE login...")
                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onStatusUpdate?.invoke("Waiting for OAuth callback on 127.0.0.1:$CALLBACK_PORT...")
                    while (System.nanoTime() < deadline) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                        socket.use callbackSocket@ { callback ->
                            callback.soTimeout = 5000
                            val reader = BufferedReader(InputStreamReader(callback.inputStream, Charsets.US_ASCII))
                            val requestLine = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val request = requestLine.split(' ')
                            val target = request.getOrNull(1).orEmpty()
                            val uri = Uri.parse("http://localhost$target")
                            if (request.firstOrNull() != "GET" || uri.path != "/auth/callback") {
                                writeCallbackResponse(callback, 404, "Not found", "This endpoint accepts the OAuth callback only.")
                                return@callbackSocket
                            }
                            val returnedState = uri.getQueryParameter("state")
                            if (returnedState == null || !MessageDigest.isEqual(state.toByteArray(), returnedState.toByteArray())) {
                                writeCallbackResponse(callback, 400, "Login failed", "OAuth state mismatch. Return to Trinity Core and retry.")
                                return@withContext Result.failure(IllegalStateException("OAuth state mismatch."))
                            }
                            val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
                            val code = uri.getQueryParameter("code")
                            if (!error.isNullOrBlank() || code.isNullOrBlank()) {
                                writeCallbackResponse(callback, 400, "Login failed", "Authorization was not completed. Return to Trinity Core.")
                                return@withContext Result.failure(IllegalStateException(error ?: "No authorization code received."))
                            }
                            try {
                                onStatusUpdate?.invoke("Exchanging authorization code for tokens...")
                                val session = exchangeCodeForTokens(code, codeVerifier).getOrThrow()
                                synchronized(sessionLock) {
                                    check(logoutGeneration == attemptGeneration) { "Login was cancelled by disconnecting the session." }
                                    saveSession(context, session)
                                }
                                // Success is sent only after the exchange AND synchronous persistence succeed.
                                writeCallbackResponse(callback, 200, "ChatGPT login complete", "The session has been saved. Close this tab and return to Trinity Core.")
                                onStatusUpdate?.invoke("ChatGPT OAuth session active.")
                                return@withContext Result.success(session)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                try { writeCallbackResponse(callback, 502, "Login failed", "The token exchange or session storage failed. See Trinity Core for the error.") } catch (_: Exception) { }
                                return@withContext Result.failure(e)
                            }
                        }
                    }
                    Result.failure(SocketTimeoutException("ChatGPT login timed out waiting for the callback."))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun writeCallbackResponse(socket: Socket, status: Int, title: String, message: String) {
        // Only fixed application text is rendered, never authorization codes, tokens or remote HTML.
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title></head><body style="font-family:sans-serif;padding:40px"><h1>$title</h1><p>$message</p></body></html>"""
        val body = html.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Bad Gateway" }
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n"
        socket.outputStream.write(header.toByteArray(Charsets.US_ASCII))
        socket.outputStream.write(body)
        socket.outputStream.flush()
    }

    fun saveSession(context: Context, session: OpenAIOAuthSession) = synchronized(sessionLock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(prefs.edit().putString(KEY_SESSION, session.encodeForStorage()).commit()) { "Could not persist the ChatGPT session." }
    }

    fun loadSession(context: Context): OpenAIOAuthSession? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        return decodeStoredSession(stored)
    }

    fun clearSession(context: Context) = synchronized(sessionLock) {
        logoutGeneration++
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(prefs.edit().remove(KEY_SESSION).commit()) { "Could not clear the ChatGPT session." }
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
