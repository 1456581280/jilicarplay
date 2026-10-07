package com.shilapi.xcertplay

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer

internal data class SteeringBinding(
    val operation: String,
    val keyCode: Int,
    val event: Int,
    val source: String = "logcat",
    val logTag: String = "",
    val broadcastAction: String = "",
    val keyExtra: String = "",
    val eventExtra: String = "",
    val logContains: List<String> = emptyList(),
) {
    // carlito | Existing OneOS profiles also work through the bridge's selected ECARX backend.
    val isVendorInput: Boolean get() = source in listOf("oneos", "ecarx", "vehicle_bridge")
    fun json(): JSONObject = JSONObject()
        .put("operation", operation).put("keyCode", keyCode).put("event", event)
        .put("source", source).put("logTag", logTag)
        .put("broadcastAction", broadcastAction).put("keyExtra", keyExtra).put("eventExtra", eventExtra)
        .put("logContains", JSONArray(logContains))

    val inputId: String get() = listOf(source, keyCode.toString(), logTag, broadcastAction, keyExtra, eventExtra)
        .joinToString("\u0000") + "\u0000" + logContains.sorted().joinToString("\u0000")

    companion object {
        val operations = listOf("play_pause", "next", "previous", "siri")
        fun fromJson(json: JSONObject) = SteeringBinding(
            json.getString("operation"), json.getInt("keyCode"), json.getInt("event"),
            json.getString("source"), json.optString("logTag"), json.optString("broadcastAction"),
            json.optString("keyExtra"), json.optString("eventExtra"),
            json.optJSONArray("logContains")?.let { array ->
                require(array.length() <= 4)
                (0 until array.length()).map(array::getString)
            }.orEmpty(),
        ).also {
            require(it.operation in operations && it.event in 0..4)
            require(it.source in listOf("broadcast", "logcat", "oneos", "ecarx", "vehicle_bridge"))
            require(it.keyCode in 1..1_000_000 || (it.keyCode == 0 && it.source == "logcat" && it.logContains.isNotEmpty()))
            require(it.source != "logcat" || it.logTag.isNotBlank())
            require(it.logTag.length <= 100 && it.logTag.none(Char::isISOControl))
            require(it.broadcastAction.length <= 160 && it.broadcastAction.none(Char::isISOControl))
            require(it.keyExtra.length <= 120 && it.eventExtra.length <= 120)
            require(it.keyExtra.none(Char::isISOControl) && it.eventExtra.none(Char::isISOControl))
            require(it.source != "broadcast" || (it.broadcastAction.matches(Regex("[A-Za-z0-9_.]+")) && it.keyExtra.isNotBlank()))
            require(it.logContains.size <= 4 && (it.source == "logcat" || it.logContains.isEmpty()))
            it.logContains.forEach { text -> require(text.isNotBlank() && text.length <= 160 && text.none(Char::isISOControl)) }
        }
    }
}

internal data class SteeringProfile(
    val carModel: String,
    val headUnitModel: String,
    val manufacturer: String = Build.MANUFACTURER.orEmpty(),
    val firmware: String = Build.DISPLAY.orEmpty(),
    val androidVersion: String = Build.VERSION.RELEASE.orEmpty(),
    val savedAt: Long = System.currentTimeMillis(),
    val bindings: List<SteeringBinding> = emptyList(),
) {
    val fileName: String get() = listOf(carModel, headUnitModel, firmware)
        .joinToString("_") { filePart(it) } + ".json"

    fun json(): JSONObject = JSONObject().put("schemaVersion", 1).put("backend", "system_key_events")
        .put("carModel", carModel).put("headUnitModel", headUnitModel)
        .put("manufacturer", manufacturer).put("firmware", firmware)
        .put("androidVersion", androidVersion).put("savedAt", savedAt)
        .put("bindings", JSONArray().also { array -> bindings.forEach { array.put(it.json()) } })

    fun validated(): SteeringProfile = also {
        require(carModel.isNotBlank() && headUnitModel.isNotBlank())
        listOf(carModel, headUnitModel, manufacturer, firmware, androidVersion).forEach { value ->
            require(value.length <= 120 && value.none(Char::isISOControl))
        }
        require(bindings.size in 1..4 && bindings.map { it.operation }.distinct().size == bindings.size)
        require(bindings.map { it.inputId }.distinct().size == bindings.size)
        require(savedAt in 1..999_999_999_999_999L)
        bindings.forEach { SteeringBinding.fromJson(it.json()) }
    }

    companion object {
        fun draft() = SteeringProfile("", Build.MODEL.orEmpty())
        fun fromJson(json: JSONObject, validate: Boolean = true): SteeringProfile {
            require(json.getInt("schemaVersion") == 1 && json.getString("backend") == "system_key_events")
            val array = json.getJSONArray("bindings")
            require(array.length() <= 4)
            val profile = SteeringProfile(
                json.getString("carModel"), json.getString("headUnitModel"),
                json.optString("manufacturer"), json.optString("firmware"),
                json.optString("androidVersion"), json.getLong("savedAt"),
                (0 until array.length()).map { SteeringBinding.fromJson(array.getJSONObject(it)) },
            )
            return if (validate) profile.validated() else profile
        }

        private fun filePart(value: String): String {
            val safe = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim('.', ' ').ifBlank { "unknown" }
            val part = StringBuilder()
            var bytes = 0
            val points = safe.codePoints().iterator()
            while (points.hasNext()) {
                val letter = String(Character.toChars(points.nextInt()))
                bytes += letter.toByteArray(Charsets.UTF_8).size
                if (bytes > 64) break
                part.append(letter)
            }
            return part.toString()
        }
    }
}

/** Local profiles and a durable outbox. Network work belongs to [SteeringProfileUploadService]. */
internal object SteeringProfiles {
    private const val TAG = "DiPlay-SteeringProfiles"
    private const val JOB_ID = 0x535750
    const val MAX_BYTES = 32 * 1024
    const val UPLOAD_URL = "https://carlito.i234.me:5214/diplay-profiles/v1/profiles"

    private fun prefs(context: Context) = context.getSharedPreferences("steering_profiles", Context.MODE_PRIVATE)
    private fun directory(context: Context) = File(context.filesDir, "steering-profiles").apply { mkdirs() }
    fun outbox(context: Context) = File(directory(context), "outbox").apply { mkdirs() }

    @Synchronized fun load(context: Context): SteeringProfile? = runCatching {
        val name = prefs(context).getString("active", null) ?: return@runCatching null
        require(File(name).name == name && name.endsWith(".json"))
        SteeringProfile.fromJson(JSONObject(AtomicFile(File(directory(context), name)).readFully().toString(Charsets.UTF_8)))
    }.onFailure { Log.w(TAG, "Could not load steering profile", it) }.getOrNull()

    @Synchronized fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    @Synchronized fun loadEnabled(context: Context): SteeringProfile? = if (enabled(context)) load(context) else null
    @Synchronized fun disable(context: Context) { check(prefs(context).edit().putBoolean("enabled", false).commit()) }

    @Synchronized fun save(context: Context, profile: SteeringProfile) {
        profile.validated()
        val bytes = profile.json().toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        write(File(directory(context), profile.fileName), bytes)
        val receipt = digest(bytes)
        write(File(outbox(context), "$receipt.json"), bytes)
        check(prefs(context).edit().putString("active", profile.fileName)
            .putBoolean("enabled", true).putString("receipt", receipt).putString("upload_state", "pending").commit())
        scheduleUpload(context)
    }

    @Synchronized fun uploadState(context: Context): String = prefs(context).getString("upload_state", "none")!!
    @Synchronized fun developerUnlocked(context: Context): Boolean = prefs(context).getBoolean("developer", false)
    @Synchronized fun unlockDeveloper(context: Context) { prefs(context).edit().putBoolean("developer", true).apply() }

    fun scheduleUpload(context: Context) {
        if (outbox(context).listFiles()?.none { it.extension == "json" } != false) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SteeringProfileUploadService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        if (context.getSystemService(JobScheduler::class.java)?.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "Steering profile upload remains pending")
        }
    }

    @Synchronized fun noteUpload(context: Context, receipt: String, state: String) {
        if (prefs(context).getString("receipt", null) == receipt) {
            prefs(context).edit().putString("upload_state", state).apply()
        }
    }

    /** Returns false for a permanent refusal; a network failure throws so the outbox is retried. */
    fun upload(context: Context, file: File, onConnection: (HttpURLConnection) -> Unit): Boolean {
        val bytes = file.readBytes()
        require(bytes.size in 1..MAX_BYTES)
        SteeringProfile.fromJson(JSONObject(bytes.toString(Charsets.UTF_8)))
        val receipt = file.nameWithoutExtension
        val connection = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        onConnection(connection)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status in listOf(400, 413, 422)) {
                noteUpload(context, receipt, "failed")
                return false
            }
            check(status in 200..299) { "Profile upload HTTP $status" }
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val response = JSONObject(body.toString(Charsets.UTF_8))
            check(response.optBoolean("ok") && response.optString("receipt") == digest(bytes))
            check(file.delete()) { "Could not acknowledge uploaded profile" }
            noteUpload(context, receipt, "uploaded")
            return true
        } finally {
            connection.disconnect()
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}
