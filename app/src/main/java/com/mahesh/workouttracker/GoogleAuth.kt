package com.mahesh.workouttracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Minimal blocking HTTP on HttpURLConnection (framework only, no deps). */
object Net {
    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Throws Exception("HTTP <code>: <excerpt>") on any non-2xx response. */
    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, contentType: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code: ${text.take(160)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun formPost(url: String, params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }.toByteArray(Charsets.UTF_8)
        return JSONObject(request("POST", url, emptyMap(), body, "application/x-www-form-urlencoded"))
    }
}

/** Tiny background runner: blocking work on a thread, result on the main thread. */
object Bg {
    private val main = Handler(Looper.getMainLooper())
    fun <T> run(work: () -> T, done: (T?, Exception?) -> Unit) {
        Thread {
            try {
                val r = work()
                main.post { done(r, null) }
            } catch (e: Exception) {
                main.post { done(null, e) }
            }
        }.start()
    }
}

/**
 * Sign in with Google via OAuth2 Authorization Code + PKCE (S256), framework
 * classes only. The app is sideloaded/debug-signed; Mahesh created an Android
 * OAuth client in his own Google Cloud project (package
 * com.mahesh.workouttracker + the debug SHA-1 from the README) and its Client
 * ID is baked in as DEFAULT_CLIENT_ID, so sign-in works with zero setup. A
 * Client ID is a public installed-app identifier, not a secret. Settings can
 * override it (a non-empty pref wins) — e.g. if the signing key ever changes
 * and a new Android OAuth client is registered.
 *
 * Tokens never touch plaintext storage or logs: access/refresh tokens are
 * AES/GCM-encrypted with a key held in the Android Keystore (alias
 * wt_google_key) and kept in a dedicated prefs file (google_auth). Only the
 * display identity (email/name) and bookkeeping values are plain.
 */
object GoogleAuth {
    private const val PREFS = "google_auth"
    private const val KEY_ALIAS = "wt_google_key"
    const val REDIRECT_URI = "com.mahesh.workouttracker:/oauth2redirect"
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    private const val SCOPES = "openid email profile https://www.googleapis.com/auth/drive.appdata"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ----- Client ID (public identifier; baked-in default + Settings override) -----
    // Default Android OAuth client from Mahesh's Google Cloud project,
    // registered for package com.mahesh.workouttracker + debug SHA-1
    // 7B:82:1B:6B:13:FD:A8:B2:2E:15:7A:EA:0B:84:BC:EE:66:DB:08:CC.
    // Changing the signing key requires registering a new Android OAuth
    // client and updating this value (or the Settings override).
    const val DEFAULT_CLIENT_ID = "746141152814-7uc1ov26tuflo5mapc010t4al6134ptf.apps.googleusercontent.com"
    /** Effective Client ID: the Settings override when non-empty, else the default. */
    fun clientId(c: Context): String = prefs(c).getString("client_id", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_CLIENT_ID
    fun setClientId(c: Context, id: String) { prefs(c).edit().putString("client_id", id.trim()).apply() }

    // ----- Display identity + bookkeeping (plain, not sensitive) -----
    fun email(c: Context): String = prefs(c).getString("email", "") ?: ""
    fun displayName(c: Context): String = prefs(c).getString("name", "") ?: ""
    fun lastBackupMs(c: Context): Long = prefs(c).getLong("last_backup_ms", 0L)
    fun setLastBackupMs(c: Context, ms: Long) { if (ms > 0) prefs(c).edit().putLong("last_backup_ms", ms).apply() }

    fun isSignedIn(c: Context): Boolean = !getEnc(c, "rt").isNullOrBlank()

    // ----- Keystore-backed AES/GCM encryption for the tokens -----
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encryptText(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun decryptText(enc: String): String? = try {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    } catch (e: Exception) { null }

    private fun getEnc(c: Context, key: String): String? {
        val v = prefs(c).getString(key, null) ?: return null
        return decryptText(v)
    }
    private fun putEnc(e: android.content.SharedPreferences.Editor, key: String, value: String) {
        e.putString(key, encryptText(value))
    }

    // ----- PKCE helpers -----
    private fun b64url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun randomB64Url(n: Int): String = b64url(ByteArray(n).also { SecureRandom().nextBytes(it) })
    private fun challengeOf(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /**
     * Opens the Google consent page in the browser (ACTION_VIEW; androidx.browser
     * is not available in this build). Returns false when no Client ID is set.
     * The browser redirects back to OauthCallbackActivity via the custom scheme.
     */
    fun startSignIn(activity: Activity): Boolean {
        val cid = clientId(activity)
        if (cid.isBlank()) return false
        val verifier = randomB64Url(64)
        val state = randomB64Url(32)
        prefs(activity).edit().putString("pending_verifier", verifier).putString("pending_state", state).apply()
        val url = AUTH_URL +
            "?client_id=" + Net.urlEncode(cid) +
            "&redirect_uri=" + Net.urlEncode(REDIRECT_URI) +
            "&response_type=code" +
            "&scope=" + Net.urlEncode(SCOPES) +
            "&access_type=offline" +
            "&prompt=consent" +
            "&code_challenge=" + Net.urlEncode(challengeOf(verifier)) +
            "&code_challenge_method=S256" +
            "&state=" + Net.urlEncode(state)
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addCategory(Intent.CATEGORY_BROWSABLE) })
        return true
    }

    fun expectedState(c: Context): String? = prefs(c).getString("pending_state", null)
    fun clearPending(c: Context) { prefs(c).edit().remove("pending_verifier").remove("pending_state").apply() }

    /** Blocking: exchanges the auth code for tokens and stores them encrypted. */
    fun exchangeCode(context: Context, code: String) {
        val ctx = context.applicationContext
        val verifier = prefs(ctx).getString("pending_verifier", null) ?: throw Exception("Missing PKCE verifier — start sign-in again")
        val tok = Net.formPost(
            TOKEN_URL, linkedMapOf(
                "code" to code,
                "client_id" to clientId(ctx),
                "code_verifier" to verifier,
                "grant_type" to "authorization_code",
                "redirect_uri" to REDIRECT_URI
            )
        )
        storeTokens(ctx, tok)
        clearPending(ctx)
    }

    private fun storeTokens(ctx: Context, tok: JSONObject) {
        val at = tok.optString("access_token")
        if (at.isBlank()) throw Exception("Google did not return an access token")
        val e = prefs(ctx).edit()
        putEnc(e, "at", at)
        val rt = tok.optString("refresh_token")
        if (rt.isNotBlank()) putEnc(e, "rt", rt)
        e.putLong("exp_ms", System.currentTimeMillis() + tok.optLong("expires_in", 3600L) * 1000L)
        e.apply()
        val idToken = tok.optString("id_token")
        if (idToken.isNotBlank()) {
            try {
                val parts = idToken.split(".")
                var payload = parts[1].replace('-', '+').replace('_', '/')
                while (payload.length % 4 != 0) payload += "="
                val json = JSONObject(String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8))
                val em = json.optString("email", "")
                val nm = json.optString("name", "")
                prefs(ctx).edit().apply {
                    if (em.isNotBlank()) putString("email", em)
                    if (nm.isNotBlank()) putString("name", nm)
                }.apply()
            } catch (e2: Exception) { /* identity is display-only; tokens are what matter */ }
        }
    }

    /**
     * Blocking: returns a valid access token, refreshing (and persisting the
     * new token) when the stored one is expired or about to expire.
     * Null when signed out or the refresh fails.
     */
    fun getAccessToken(context: Context): String? {
        val ctx = context.applicationContext
        val at = getEnc(ctx, "at")
        val exp = prefs(ctx).getLong("exp_ms", 0L)
        if (!at.isNullOrBlank() && System.currentTimeMillis() < exp - 60_000L) return at
        val rt = getEnc(ctx, "rt")
        if (rt.isNullOrBlank()) return null
        return try {
            val tok = Net.formPost(
                TOKEN_URL, linkedMapOf(
                    "client_id" to clientId(ctx),
                    "refresh_token" to rt,
                    "grant_type" to "refresh_token"
                )
            )
            storeTokens(ctx, tok)
            getEnc(ctx, "at")
        } catch (e: Exception) { null }
    }

    /** Blocking, best-effort: revokes the token at Google, then clears the
     *  local token/identity state. The Client ID is kept for the next sign-in.
     *  Local workout data is never touched. */
    fun signOut(context: Context) {
        val ctx = context.applicationContext
        try {
            val t = getEnc(ctx, "rt") ?: getEnc(ctx, "at")
            if (t != null) Net.formPost(REVOKE_URL, mapOf("token" to t))
        } catch (e: Exception) { /* best effort */ }
        prefs(ctx).edit()
            .remove("at").remove("rt").remove("exp_ms")
            .remove("email").remove("name").remove("last_backup_ms")
            .remove("pending_verifier").remove("pending_state")
            .apply()
    }
}
