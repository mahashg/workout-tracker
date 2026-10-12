package com.mahesh.workouttracker

import android.accounts.Account
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.util.concurrent.TimeUnit
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
 * Sign in with Google via the Google Identity Services Authorization API
 * (play-services-auth), client-side access only — no offline access, no
 * server client ID, no browser redirect. v2.8's browser Authorization Code +
 * PKCE flow with a custom URI scheme redirect is rejected by Google on
 * Android and was removed in v2.9; the SDK identifies the app through the
 * Android OAuth client registered in Mahesh's Google Cloud project for this
 * package + signing-certificate SHA-1 (see README V2.9). No client ID string
 * is entered or stored anywhere in the app.
 *
 * Token model: the SDK hands out short-lived access tokens (~1h). The latest
 * one is cached AES/GCM-encrypted (Keystore alias wt_google_key, dedicated
 * google_auth prefs) and treated as expired after 50 minutes; past that,
 * getAccessToken silently re-authorizes through the SDK (no UI) and only
 * gives up when Google demands interaction — Settings then runs the
 * interactive flow. No refresh token exists or is stored. Signed-in state is
 * the gis_signed_in flag + stored identity, never a leftover v2.8 token:
 * upgrading users sign in once more with the new flow.
 */
object GoogleAuth {
    private const val PREFS = "google_auth"
    private const val KEY_ALIAS = "wt_google_key"
    private const val TOKEN_TTL_MS = 50L * 60L * 1000L

    val SCOPES: List<Scope> = listOf(
        Scope("https://www.googleapis.com/auth/drive.appdata"),
        Scope("https://www.googleapis.com/auth/userinfo.email"),
        Scope("https://www.googleapis.com/auth/userinfo.profile"),
        Scope("openid")
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun client(c: Context) = Identity.getAuthorizationClient(c.applicationContext)

    /** The one authorization request used everywhere (interactive + silent).
     *  Client-side only: requestOfflineAccess is deliberately NOT called. */
    fun authorizationRequest(c: Context): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(SCOPES)
        val em = email(c)
        if (em.isNotBlank()) b.setAccount(Account(em, "com.google"))
        return b.build()
    }

    // ----- Display identity + bookkeeping (plain, not sensitive) -----
    fun email(c: Context): String = prefs(c).getString("email", "") ?: ""
    fun displayName(c: Context): String = prefs(c).getString("name", "") ?: ""
    fun lastBackupMs(c: Context): Long = prefs(c).getLong("last_backup_ms", 0L)
    fun setLastBackupMs(c: Context, ms: Long) { if (ms > 0) prefs(c).edit().putLong("last_backup_ms", ms).apply() }

    fun isSignedIn(c: Context): Boolean {
        scrubLegacy(c)
        return prefs(c).getBoolean("gis_signed_in", false)
    }

    /** v2.8 leftovers (browser-flow tokens, the pasted Client ID, pending
     *  PKCE values) must never count as signed-in state under the SDK model.
     *  Drop them on sight; a cached access token without the GIS flag goes
     *  too, so an upgrade always starts from one fresh GIS sign-in. */
    private fun scrubLegacy(c: Context) {
        val p = prefs(c)
        val e = p.edit()
        var dirty = false
        for (k in listOf("rt", "client_id", "pending_verifier", "pending_state")) {
            if (p.contains(k)) { e.remove(k); dirty = true }
        }
        if (!p.getBoolean("gis_signed_in", false)) {
            for (k in listOf("at", "exp_ms")) {
                if (p.contains(k)) { e.remove(k); dirty = true }
            }
        }
        if (dirty) e.apply()
    }

    // ----- Keystore-backed AES/GCM encryption for the cached access token -----
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

    /**
     * Persists a granted AuthorizationResult: the encrypted access token
     * (50-minute TTL), the GIS signed-in flag, and the display identity when
     * the result's Google account carries one. Callers verify the token is
     * non-blank before relying on this for an interactive sign-in.
     */
    fun storeResult(c: Context, result: AuthorizationResult) {
        val ctx = c.applicationContext
        val e = prefs(ctx).edit()
        val token = result.accessToken
        if (!token.isNullOrBlank()) {
            putEnc(e, "at", token)
            e.putLong("exp_ms", System.currentTimeMillis() + TOKEN_TTL_MS)
        }
        e.putBoolean("gis_signed_in", true)
        e.apply()
        try {
            val acct = result.toGoogleSignInAccount()
            val em = acct?.email ?: ""
            val nm = acct?.displayName ?: ""
            if (em.isNotBlank() || nm.isNotBlank()) {
                prefs(ctx).edit().apply {
                    if (em.isNotBlank()) putString("email", em)
                    if (nm.isNotBlank()) putString("name", nm)
                }.apply()
            }
        } catch (e2: Exception) { /* identity is display-only; the token is what matters */ }
    }

    /** Blocking: fills a missing display identity from the OIDC userinfo
     *  endpoint using the just-granted access token. Best-effort. */
    fun fetchIdentityBlocking(c: Context, token: String) {
        try {
            val body = Net.request("GET", "https://openidconnect.googleapis.com/v1/userinfo", mapOf("Authorization" to "Bearer $token"))
            val json = JSONObject(body)
            val em = json.optString("email", "")
            val nm = json.optString("name", "")
            if (em.isNotBlank() || nm.isNotBlank()) {
                prefs(c.applicationContext).edit().apply {
                    if (em.isNotBlank()) putString("email", em)
                    if (nm.isNotBlank()) putString("name", nm)
                }.apply()
            }
        } catch (e: Exception) { /* display-only; ignore */ }
    }

    /**
     * Blocking — background threads ONLY (Tasks.await). Returns a usable
     * access token: the cached one while fresh, otherwise a silent SDK
     * re-authorization. Null when signed out, when Google requires
     * interaction (hasResolution), or when the silent call fails — callers
     * surface that as "session expired, sign in again".
     */
    fun getAccessToken(context: Context): String? {
        val ctx = context.applicationContext
        scrubLegacy(ctx)
        if (!prefs(ctx).getBoolean("gis_signed_in", false)) return null
        val at = getEnc(ctx, "at")
        val exp = prefs(ctx).getLong("exp_ms", 0L)
        if (!at.isNullOrBlank() && System.currentTimeMillis() < exp - 60_000L) return at
        return try {
            val result = Tasks.await(client(ctx).authorize(authorizationRequest(ctx)), 30, TimeUnit.SECONDS)
            if (result == null || result.hasResolution()) return null
            val fresh = result.accessToken
            if (fresh.isNullOrBlank()) null else { storeResult(ctx, result); fresh }
        } catch (e: Exception) { null }
    }

    /** Blocking, best-effort: revokes this app's access for the account and
     *  clears the cached token at Google, then wipes ALL local auth state
     *  (tokens, identity, GIS flag, last-backup). Local workout data is
     *  never touched. */
    fun signOut(context: Context) {
        val ctx = context.applicationContext
        val cl = client(ctx)
        val token = getEnc(ctx, "at")
        try {
            if (!token.isNullOrBlank()) {
                Tasks.await(cl.clearToken(ClearTokenRequest.builder().setToken(token).build()), 15, TimeUnit.SECONDS)
            }
        } catch (e: Exception) { /* best effort */ }
        try {
            val em = email(ctx)
            if (em.isNotBlank()) {
                val req = RevokeAccessRequest.builder()
                    .setAccount(Account(em, "com.google"))
                    .setScopes(SCOPES)
                    .build()
                Tasks.await(cl.revokeAccess(req), 15, TimeUnit.SECONDS)
            }
        } catch (e: Exception) { /* best effort */ }
        prefs(ctx).edit().clear().apply()
    }
}
