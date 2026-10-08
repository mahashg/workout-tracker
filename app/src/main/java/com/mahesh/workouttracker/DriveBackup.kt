package com.mahesh.workouttracker

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Google Drive backup/restore over the Drive REST v3 API (HttpURLConnection,
 * no dependencies). The backup lives in the app-private appDataFolder, which
 * Google only ever shows to this app — it never appears in the user's Drive.
 *
 * All functions BLOCK; call them from a background thread (see Bg.run).
 * One JSON snapshot file: workout-tracker-backup.json (format in DbHelper).
 */
object DriveBackup {
    const val FILE_NAME = "workout-tracker-backup.json"
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

    data class Meta(val id: String, val modifiedTime: String, val sizeBytes: Long)

    private fun token(context: Context): String =
        GoogleAuth.getAccessToken(context.applicationContext) ?: throw Exception("Not signed in to Google")

    private fun auth(t: String) = mapOf("Authorization" to "Bearer $t")

    /** Finds the existing backup file (name match inside appDataFolder), or null. */
    fun findMeta(context: Context): Meta? = findMeta(token(context))

    private fun findMeta(t: String): Meta? {
        val q = Net.urlEncode("name = '$FILE_NAME'")
        val fields = Net.urlEncode("files(id,name,modifiedTime,size)")
        val body = Net.request("GET", "$FILES_URL?spaces=appDataFolder&q=$q&fields=$fields", auth(t))
        val files = JSONObject(body).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val f = files.getJSONObject(0)
        return Meta(f.getString("id"), f.optString("modifiedTime", ""), f.optString("size", "0").toLongOrNull() ?: 0L)
    }

    /** Uploads a fresh snapshot, updating the existing backup file in place
     *  (PATCH) or creating it in appDataFolder (multipart POST) the first time. */
    fun backupNow(context: Context, db: DbHelper): JSONObject {
        val ctx = context.applicationContext
        val t = token(ctx)
        val bytes = db.exportBackupJson(ctx).toString().toByteArray(Charsets.UTF_8)
        val existing = findMeta(t)
        val result = if (existing != null) {
            JSONObject(Net.request("PATCH", "$UPLOAD_URL/${existing.id}?uploadType=media", auth(t), bytes, "application/json"))
        } else {
            val boundary = "wt-boundary-" + System.currentTimeMillis()
            val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                "{\"name\":\"$FILE_NAME\",\"parents\":[\"appDataFolder\"]}\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n").toByteArray(Charsets.UTF_8)
            val tail = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)
            JSONObject(Net.request("POST", "$UPLOAD_URL?uploadType=multipart", auth(t), head + bytes + tail, "multipart/related; boundary=$boundary"))
        }
        GoogleAuth.setLastBackupMs(ctx, System.currentTimeMillis())
        return result
    }

    /** Downloads the backup and FULL-REPLACES local data with it
     *  (transactional inside DbHelper — a bad file changes nothing). */
    fun restoreNow(context: Context, db: DbHelper): JSONObject {
        val ctx = context.applicationContext
        val t = token(ctx)
        val meta = findMeta(t) ?: throw Exception("No backup found in Drive")
        val body = Net.request("GET", "$FILES_URL/${meta.id}?alt=media", auth(t))
        return db.importBackupJson(ctx, JSONObject(body))
    }

    /** Fire-and-forget backup after a finished workout: signed-in only,
     *  completely silent on failure (the next manual or automatic backup
     *  simply overwrites). Uses the caller's DbHelper so DB access stays on
     *  the app's existing connection. */
    fun backupSilently(context: Context, db: DbHelper) {
        val app = context.applicationContext
        Thread {
            try {
                if (GoogleAuth.isSignedIn(app)) backupNow(app, db)
            } catch (e: Exception) { /* silent by design */ }
        }.start()
    }

    /** Drive's modifiedTime is RFC 3339; parse it for the "Last backup" label. */
    fun parseModified(s: String): Long {
        val utc = TimeZone.getTimeZone("UTC")
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) sdf.timeZone = utc
                return sdf.parse(s)?.time ?: 0L
            } catch (e: Exception) { /* try the next pattern */ }
        }
        return 0L
    }
}
