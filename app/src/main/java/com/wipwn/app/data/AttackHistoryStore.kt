package com.wipwn.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyDataStore by preferencesDataStore(name = "wipwn_history")

/**
 * Persists [AttackResult]s across app restarts using Preferences DataStore.
 *
 * We serialise the list manually as a JSON string to avoid pulling in
 * kotlinx.serialization just for one entity. If the shape grows, migrate to
 * Proto DataStore or Room.
 */
class AttackHistoryStore(private val context: Context) {

    private val key = stringPreferencesKey(KEY_HISTORY_JSON)

    /** Live stream of history, newest-first is the caller's responsibility. */
    val history: Flow<List<AttackResult>> = context.historyDataStore.data.map { prefs ->
        prefs[key]?.let { decode(it) } ?: emptyList()
    }

    suspend fun add(result: AttackResult) {
        context.historyDataStore.edit { prefs ->
            val existing = prefs[key]?.let { decode(it) } ?: emptyList()
            val combined = (existing + result).takeLast(MAX_ENTRIES)
            prefs[key] = encode(combined)
        }
    }

    suspend fun clear() {
        context.historyDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    // --- serialisation --------------------------------------------------

    private fun encode(results: List<AttackResult>): String {
        val arr = JSONArray()
        results.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("bssid", r.bssid)
                    put("ssid", r.ssid)
                    put("pin", r.pin ?: JSONObject.NULL)
                    put("password", r.password ?: JSONObject.NULL)
                    put("success", r.success)
                    put("error", r.error?.message ?: JSONObject.NULL)
                    put("errorType", r.error?.let(::errorTypeTag) ?: JSONObject.NULL)
                    put("timestamp", r.timestamp)
                    put("attackType", r.attackType?.name ?: JSONObject.NULL)
                    put("captureFile", r.captureFile ?: JSONObject.NULL)
                    put("reconData", r.reconData ?: JSONObject.NULL)
                    put("macUsed", r.macUsed ?: JSONObject.NULL)
                    put("algorithmUsed", r.algorithmUsed ?: JSONObject.NULL)
                }
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String): List<AttackResult> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val errMsg = o.optString("error").takeIf { !o.isNull("error") }
                val errTag = o.optString("errorType").takeIf { !o.isNull("errorType") }
                add(
                    AttackResult(
                        bssid = o.getString("bssid"),
                        ssid = o.getString("ssid"),
                        pin = o.optString("pin").takeIf { !o.isNull("pin") },
                        password = o.optString("password").takeIf { !o.isNull("password") },
                        success = o.getBoolean("success"),
                        error = errMsg?.let { decodeError(errTag, it) },
                        timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                        attackType = o.optString("attackType").takeIf { !o.isNull("attackType") }?.let {
                            runCatching { AttackType.valueOf(it) }.getOrNull()
                        },
                        captureFile = o.optString("captureFile").takeIf { !o.isNull("captureFile") },
                        reconData = o.optString("reconData").takeIf { !o.isNull("reconData") },
                        macUsed = o.optString("macUsed").takeIf { !o.isNull("macUsed") },
                        algorithmUsed = o.optString("algorithmUsed").takeIf { !o.isNull("algorithmUsed") }
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun errorTypeTag(e: AttackError): String = when (e) {
        is AttackError.WpsLocked -> "locked"
        is AttackError.SelinuxBlocked -> "selinux"
        is AttackError.PixieDustNotVulnerable -> "pixie"
        is AttackError.EnvironmentFailed -> "env"
        is AttackError.NoRoot -> "noroot"
        is AttackError.RateLimited -> "ratelimit"
        is AttackError.MonitorModeFailed -> "monitor"
        is AttackError.ToolNotFound -> "toolnotfound"
        is AttackError.CaptureFailed -> "capture"
        is AttackError.MacSpoofFailed -> "macspoof"
        is AttackError.Unknown -> "unknown"
    }

    private fun decodeError(tag: String?, msg: String): AttackError = when (tag) {
        "locked" -> AttackError.WpsLocked(msg)
        "selinux" -> AttackError.SelinuxBlocked(msg)
        "pixie" -> AttackError.PixieDustNotVulnerable(msg)
        "env" -> AttackError.EnvironmentFailed(msg)
        "noroot" -> AttackError.NoRoot(msg)
        "ratelimit" -> AttackError.RateLimited(msg)
        "monitor" -> AttackError.MonitorModeFailed(msg)
        "toolnotfound" -> AttackError.ToolNotFound(msg)
        "capture" -> AttackError.CaptureFailed(msg)
        "macspoof" -> AttackError.MacSpoofFailed(msg)
        else -> AttackError.Unknown(msg)
    }

    companion object {
        private const val KEY_HISTORY_JSON = "history_json"
        private const val MAX_ENTRIES = 200
    }
}
