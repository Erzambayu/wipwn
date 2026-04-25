package com.wipwn.app.data

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export attack results ke berbagai format.
 * - CSV
 * - JSON
 * - Plain text (human-readable)
 * - Share intent
 * - Clipboard
 */
object CredentialExporter {

    enum class ExportFormat { CSV, JSON, TEXT }

    data class ExportResult(
        val content: String,
        val filename: String,
        val mimeType: String
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val displayDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    /**
     * Export list of AttackResult ke format tertentu.
     */
    fun export(results: List<AttackResult>, format: ExportFormat): ExportResult {
        val timestamp = dateFormat.format(Date())
        return when (format) {
            ExportFormat.CSV -> exportCsv(results, timestamp)
            ExportFormat.JSON -> exportJson(results, timestamp)
            ExportFormat.TEXT -> exportText(results, timestamp)
        }
    }

    /**
     * Export hanya yang sukses (credentials).
     */
    fun exportCredentialsOnly(results: List<AttackResult>, format: ExportFormat): ExportResult {
        return export(results.filter { it.success }, format)
    }

    /**
     * Generate share intent.
     */
    fun createShareIntent(content: String, mimeType: String = "text/plain"): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_SUBJECT, "WiPwn Export")
        }
    }

    /**
     * Save to file di app's files directory.
     */
    fun saveToFile(context: Context, exportResult: ExportResult): File {
        val dir = File(context.filesDir, "exports")
        dir.mkdirs()
        val file = File(dir, exportResult.filename)
        file.writeText(exportResult.content)
        return file
    }

    /**
     * Format single result buat clipboard.
     */
    fun formatForClipboard(result: AttackResult): String {
        return buildString {
            appendLine("SSID: ${result.ssid}")
            appendLine("BSSID: ${result.bssid}")
            if (result.pin != null) appendLine("PIN: ${result.pin}")
            if (result.password != null) appendLine("Password: ${result.password}")
            appendLine("Status: ${if (result.success) "SUCCESS" else "FAILED"}")
            appendLine("Time: ${displayDateFormat.format(Date(result.timestamp))}")
        }.trim()
    }

    // ── CSV ────────────────────────────────────────────────────────────

    private fun exportCsv(results: List<AttackResult>, timestamp: String): ExportResult {
        val sb = StringBuilder()
        sb.appendLine("SSID,BSSID,PIN,Password,Success,Error,Timestamp")
        results.forEach { r ->
            sb.appendLine(
                "${csvEscape(r.ssid)},${r.bssid},${r.pin ?: ""},${r.password ?: ""}," +
                "${r.success},${csvEscape(r.errorMessage ?: "")},${displayDateFormat.format(Date(r.timestamp))}"
            )
        }
        return ExportResult(
            content = sb.toString(),
            filename = "wipwn_export_$timestamp.csv",
            mimeType = "text/csv"
        )
    }

    // ── JSON ───────────────────────────────────────────────────────────

    private fun exportJson(results: List<AttackResult>, timestamp: String): ExportResult {
        val root = JSONObject()
        root.put("exported_at", displayDateFormat.format(Date()))
        root.put("total", results.size)
        root.put("success_count", results.count { it.success })

        val arr = JSONArray()
        results.forEach { r ->
            arr.put(JSONObject().apply {
                put("ssid", r.ssid)
                put("bssid", r.bssid)
                put("pin", r.pin ?: JSONObject.NULL)
                put("password", r.password ?: JSONObject.NULL)
                put("success", r.success)
                put("error", r.errorMessage ?: JSONObject.NULL)
                put("timestamp", displayDateFormat.format(Date(r.timestamp)))
            })
        }
        root.put("results", arr)

        return ExportResult(
            content = root.toString(2),
            filename = "wipwn_export_$timestamp.json",
            mimeType = "application/json"
        )
    }

    // ── Text ───────────────────────────────────────────────────────────

    private fun exportText(results: List<AttackResult>, timestamp: String): ExportResult {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  WiPwn Attack Report")
        sb.appendLine("  Generated: ${displayDateFormat.format(Date())}")
        sb.appendLine("  Total: ${results.size} | Success: ${results.count { it.success }}")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        results.forEachIndexed { index, r ->
            sb.appendLine("─── #${index + 1} ───")
            sb.appendLine("  SSID     : ${r.ssid}")
            sb.appendLine("  BSSID    : ${r.bssid}")
            sb.appendLine("  Status   : ${if (r.success) "✓ SUCCESS" else "✗ FAILED"}")
            if (r.pin != null) sb.appendLine("  PIN      : ${r.pin}")
            if (r.password != null) sb.appendLine("  Password : ${r.password}")
            if (r.errorMessage != null) sb.appendLine("  Error    : ${r.errorMessage}")
            sb.appendLine("  Time     : ${displayDateFormat.format(Date(r.timestamp))}")
            sb.appendLine()
        }

        return ExportResult(
            content = sb.toString(),
            filename = "wipwn_report_$timestamp.txt",
            mimeType = "text/plain"
        )
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }
}
