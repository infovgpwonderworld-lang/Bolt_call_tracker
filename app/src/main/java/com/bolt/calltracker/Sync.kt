package com.bolt.calltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads the phone's call history and uploads new calls to the Google Sheet. */
object Sync {
    const val PREFS = "cfg"
    private const val FIRST_RUN_DAYS = 30L
    private const val BATCH = 150

    fun run(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val employee = p.getString("employee", "").orEmpty()
        val url = p.getString("url", "").orEmpty().ifBlank { Config.URL }
        val secret = p.getString("secret", "").orEmpty().ifBlank { Config.SECRET }

        val msg: String = try {
            if (employee.isBlank() || url.isBlank()) {
                "Not set up yet. Enter your name and the sheet link, then tap Save."
            } else if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                "Call log permission is off. Open phone Settings > Apps > Bolt Call Tracker > Permissions and allow Call logs."
            } else {
                val stored = p.getLong("lastSync", 0L)
                val since = if (stored == 0L) {
                    System.currentTimeMillis() - FIRST_RUN_DAYS * 24L * 3600L * 1000L
                } else {
                    stored
                }
                val calls = readCalls(ctx, since)
                var maxTime = stored
                var sent = 0
                var failure: String? = null
                for (batch in calls.chunked(BATCH)) {
                    val reply = post(url, employee, secret, batch)
                    if (!reply.startsWith("ok")) {
                        failure = reply
                        break
                    }
                    sent += batch.size
                    maxTime = maxOf(maxTime, batch.maxOf { it.getLong("time") })
                }
                if (failure != null) {
                    "Sync failed: " + failure.take(80)
                } else {
                    if (maxTime > 0L) p.edit().putLong("lastSync", maxTime).apply()
                    val now = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date())
                    "Last sync $now. Calls checked: $sent"
                }
            }
        } catch (e: Exception) {
            "Sync error: " + e.message
        }

        p.edit().putString("status", msg).apply()
        return msg
    }

    private fun readCalls(ctx: Context, since: Long): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val cols = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val cursor = ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            cols,
            CallLog.Calls.DATE + " >= ?",
            arrayOf(since.toString()),
            CallLog.Calls.DATE + " ASC"
        )
        cursor?.use { c ->
            val iNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val iName = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val iDur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("number", c.getString(iNum) ?: "")
                o.put("name", c.getString(iName) ?: "")
                o.put("type", typeName(c.getInt(iType)))
                o.put("time", c.getLong(iDate))
                o.put("duration", c.getLong(iDur))
                out.add(o)
            }
        }
        return out
    }

    private fun typeName(t: Int): String {
        return when (t) {
            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
            CallLog.Calls.MISSED_TYPE -> "MISSED"
            CallLog.Calls.REJECTED_TYPE -> "REJECTED"
            CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
            CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
            else -> "OTHER"
        }
    }

    /** Sends one batch. Returns the sheet's reply ("ok N" when it worked). */
    private fun post(url: String, employee: String, secret: String, calls: List<JSONObject>): String {
        val body = JSONObject()
        body.put("secret", secret)
        body.put("employee", employee)
        body.put("calls", JSONArray(calls))

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 300..399) {
                // Google Apps Script answers with a redirect that holds the real reply.
                val loc = conn.getHeaderField("Location") ?: return "no reply from the sheet"
                return get(loc)
            }
            return readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 60000
            return readBody(c)
        } finally {
            c.disconnect()
        }
    }

    private fun readBody(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return if (code in 200..299) text else "HTTP $code"
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Sync.run(applicationContext)
        return Result.success()
    }
}
