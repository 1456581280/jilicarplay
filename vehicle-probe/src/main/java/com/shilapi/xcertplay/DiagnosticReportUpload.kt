package com.shilapi.xcertplay

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** carlito | Shared bounded report transport for diagnostics and vehicle scans. */
class DiagnosticReportTooLargeException : Exception()

object DiagnosticReportUpload {
    private const val UPLOAD_URL = "https://carlito.i234.me:5214/diplay-profiles/v1/reports"
    const val MAX_DESCRIPTION_LENGTH = 2_000
    private const val MAX_REPORT_BYTES = 1024 * 1024
    private const val CHUNK_BYTES = 16 * 1024
    private const val MAX_REQUEST_BYTES = 31 * 1024
    private const val MAX_RESPONSE_BYTES = 32 * 1024

    fun upload(fileName: String, issueDescription: String, report: String, maxBytes: Int = MAX_REPORT_BYTES,
               connectionReady: (HttpURLConnection) -> Unit = {},
               submittedAt: Long = System.currentTimeMillis()): String {
        require(maxBytes in 1..10 * 1024 * 1024)
        val description = issueDescription.trim()
        require(description.isNotEmpty() && description.length <= MAX_DESCRIPTION_LENGTH)
        require(fileName.matches(Regex("DiPlay-[0-9]{8}-[0-9]{6}-[0-9]{3}\\.txt")))
        val reportBytes = report.toByteArray(Charsets.UTF_8)
        if (reportBytes.size > maxBytes) throw DiagnosticReportTooLargeException()
        require(reportBytes.isNotEmpty())
        val receipt = digest(description.toByteArray(Charsets.UTF_8), byteArrayOf(0), reportBytes)
        val chunkCount = (reportBytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        var complete = false
        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * CHUNK_BYTES
            val end = minOf(start + CHUNK_BYTES, reportBytes.size)
            val request = JSONObject()
                .put("schemaVersion", 1)
                .put("submittedAt", submittedAt)
                .put("fileName", fileName)
                .put("issueDescription", description)
                .put("uploadId", receipt)
                .put("chunkIndex", chunkIndex)
                .put("chunkCount", chunkCount)
                .put("content", Base64.encodeToString(reportBytes.copyOfRange(start, end), Base64.NO_WRAP))
                .toString()
                .toByteArray(Charsets.UTF_8)
            require(request.size in 1..MAX_REQUEST_BYTES)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Report upload cancelled")
            val response = post(request, connectionReady)
            check(response.optBoolean("ok") && response.optString("receipt") == receipt)
            complete = response.optBoolean("complete")
        }
        check(complete) { "Report upload incomplete" }
        return receipt
    }

    private fun post(bytes: ByteArray, connectionReady: (HttpURLConnection) -> Unit): JSONObject {
        val connection = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        try {
            connectionReady(connection)
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            check(status in 200..299) { "Report upload HTTP $status" }
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(body.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun digest(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
