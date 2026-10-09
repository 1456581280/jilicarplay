package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.HttpCookie
import java.net.URL
import java.net.URLEncoder

internal data class AppRelease(val version: String, val url: String, val notes: String? = null)

internal object ReleasePolicy {
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val FOLDER_URL = "https://1280ds.lanzoue.com/b03anv53fc"
    const val FOLDER_PASSWORD = "f6uq"
    const val NOTES_URL = "https://my.feishu.cn/wiki/BulMwX4lgiHI5xkAm3bciXgIn7g"
    const val CLOUD_HOST = "1280ds.lanzoue.com"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36"
    private const val NOTES_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
    fun userAgent(host: String) = if (host == "my.feishu.cn") NOTES_USER_AGENT else USER_AGENT
    private fun numbers(version: String): List<Long>? {
        if (!version.matches(Regex("v?\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.]+)?"))) return null
        return version.removePrefix("v").substringBefore('-').split('.').map { it.toLongOrNull() ?: return null }
    }
    fun newer(candidate: String, installed: String): Boolean {
        val next = numbers(candidate) ?: return false
        val current = numbers(installed) ?: return false
        for (i in next.indices) if (next[i] != current[i]) return next[i] > current[i]
        return '-' in installed && '-' !in candidate
    }
    fun entry(file: JSONObject): AppRelease? {
        if (file.optInt("t", -1) != 0) return null // Exclude promoted links.
        val id = file.optString("id")
        if (!id.matches(Regex("[A-Za-z0-9_]{5,80}"))) return null
        val match = Regex("JiliCarPlay[-_ ]v?(\\d+\\.\\d+\\.\\d+)\\.apk", RegexOption.IGNORE_CASE)
            .matchEntire(file.optString("name_all")) ?: return null
        if (numbers(match.groupValues[1]) == null) return null
        return AppRelease(match.groupValues[1], "https://$CLOUD_HOST/$id")
    }
    fun latest(files: List<AppRelease>, installed: String): AppRelease? =
        files.filter { newer(it.version, installed) }.reduceOrNull { a, b -> if (newer(b.version, a.version)) b else a }

    /** Provider/CDN pages stay inside the update dialog; app and intent links are never launched. */
    fun downloadPageAllowed(url: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol == "https" && parsed.userInfo == null && parsed.port in listOf(-1, 443) &&
            listOf(CLOUD_HOST, "woozooo.com", "lanzouw.com", "lanzouc.com", "lanzoue.com", "lanzouj.com", "lanzouo.com", "lanzoug.com", "lanrar.com", "dmpdmp.com").any {
                parsed.host == it || parsed.host.endsWith(".$it")
            }
    }.getOrDefault(false)
}

/** Public, read-only requests. Cookies are isolated from the system browser and other app WebViews. */
internal interface UpdateRepository {
    fun latest(installed: String): AppRelease?
    fun notes(): String
}

internal class UpdateSource : UpdateRepository {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    override fun latest(installed: String): AppRelease? {
        val html = text(ReleasePolicy.FOLDER_URL)
        val request = folderRequest(html)
        val files = mutableListOf<AppRelease>()
        for (page in 1..100) {
            val json = JSONObject(text(request.first, request.second + mapOf("pg" to "$page", "pwd" to ReleasePolicy.FOLDER_PASSWORD), ReleasePolicy.FOLDER_URL))
            when (json.optInt("zt")) {
                2 -> return ReleasePolicy.latest(files, installed)
                1 -> {
                    val entries = json.getJSONArray("text")
                    for (i in 0 until entries.length()) ReleasePolicy.entry(entries.getJSONObject(i))?.let(files::add)
                    if (entries.length() < 50) return ReleasePolicy.latest(files, installed)
                }
                else -> error("无法读取更新文件夹，请稍后重试")
            }
        }
        error("更新文件夹内容过多，未完成检查")
    }

    override fun notes(): String = parseNotes(text(ReleasePolicy.NOTES_URL))

    private fun text(initial: String, form: Map<String, String>? = null, referer: String? = null): String {
        var url = URL(initial)
        var body = form
        repeat(8) {
            check(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) &&
                url.host in setOf(ReleasePolicy.CLOUD_HOST, "my.feishu.cn")) { "更新地址不可用：${url.host}" }
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000; conn.readTimeout = 20_000; conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", ReleasePolicy.userAgent(url.host))
                conn.setRequestProperty("Cache-Control", "no-cache")
                if (referer != null) conn.setRequestProperty("Referer", referer)
                cookies.get(url.toURI(), emptyMap()).forEach { (key, values) -> conn.setRequestProperty(key, values.joinToString("; ")) }
                if (body != null) {
                    conn.requestMethod = "POST"; conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    val data = body!!.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
                    conn.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                cookies.put(url.toURI(), conn.headerFields.filterKeys { it != null })
                if (status in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, conn.getHeaderField("Location") ?: error("更新地址不可用"))
                    if (status in listOf(301, 302, 303)) body = null
                    return@repeat
                }
                check(status == 200) { "更新服务暂不可用" }
                val value = conn.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        check(output.size() + count <= 2 * 1024 * 1024) { "更新内容过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
                val challenge = Regex("var\\s+arg1\\s*=\\s*'([A-Fa-f0-9]{40})'").find(value)
                if (challenge != null && url.host == ReleasePolicy.CLOUD_HOST) {
                    // Same deterministic cookie set by the provider's public page JavaScript.
                    cookies.cookieStore.add(url.toURI(), HttpCookie("acw_sc__v2", challengeCookie(challenge.groupValues[1])).apply { path = "/"; secure = true })
                    return@repeat
                }
                return value
            } finally { conn.disconnect() }
        }
        error("更新服务暂不可用")
    }

    companion object {
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        fun challengeCookie(arg: String): String {
            require(arg.matches(Regex("[A-Fa-f0-9]{40}")))
            val order = listOf(15,35,29,24,33,16,1,38,10,9,19,31,40,27,22,23,25,13,6,11,39,18,20,8,14,21,32,26,2,30,7,4,17,5,3,28,34,37,12,36)
            val rearranged = order.map { arg[it - 1] }.joinToString("")
            val mask = "3000176000856006061501533003690027800375"
            return (0 until 40 step 2).joinToString("") { index ->
                "%02x".format(rearranged.substring(index, index + 2).toInt(16) xor mask.substring(index, index + 2).toInt(16))
            }
        }
        fun folderRequest(html: String): Pair<String, Map<String, String>> {
            val variables = Regex("var\\s+(\\w+)\\s*=\\s*'([^']*)'").findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
            val path = Regex("url\\s*:\\s*'(/filemoreajax\\.php\\?file=\\d+)'").find(html)?.groupValues?.get(1)
                ?: error("更新页面格式已变化")
            val data = Regex("data\\s*:\\s*\\{([^}]+)\\}").find(html)?.groupValues?.get(1) ?: error("更新页面格式已变化")
            val params = Regex("'([a-z0-9]+)'\\s*:\\s*('[^']*'|\\w+)").findAll(data).associate {
                val value = it.groupValues[2]
                it.groupValues[1] to if (value.startsWith("'")) value.removeSurrounding("'") else variables[value] ?: value
            }
            check(listOf("fid", "uid", "t", "k").all { params[it]?.isNotBlank() == true }) { "更新页面格式已变化" }
            return "https://${ReleasePolicy.CLOUD_HOST}$path" to params
        }

        /** Read only Feishu's pre-rendered document tree, never execute the document's JavaScript. */
        fun parseNotes(html: String): String {
            val marker = Regex("clientVars\\s*:\\s*Object\\(\\s*(?=\\{)").find(html) ?: error("更新日志暂不可用")
            val data = JSONObject(JSONTokener(html.substring(marker.range.last + 1))).getJSONObject("data")
            check(data.optJSONArray("next_cursors")?.length() in listOf(null, 0)) { "更新日志过长，暂时无法完整读取" }
            val blocks = data.getJSONObject("block_map")
            val ids = blocks.keys().asSequence().toList()
            val root = ids.singleOrNull { blocks.getJSONObject(it).getJSONObject("data").optString("type") == "page" }
                ?: error("更新日志暂不可用")
            val visited = mutableSetOf<String>()
            val lines = mutableListOf<String>()
            fun visit(id: String, depth: Int) {
                check(depth <= 100 && visited.size < 5000) { "更新日志过长" }
                if (!visited.add(id)) return
                val block = blocks.optJSONObject(id)?.optJSONObject("data") ?: return
                if (block.optBoolean("hidden")) return
                val text = block.optJSONObject("text")?.optJSONObject("initialAttributedTexts")?.optJSONObject("text")
                if (text != null && id != root) {
                    val line = text.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                        .joinToString("") { text.optString(it) }.replace("\u200B", "").trim()
                    if (line.isNotBlank()) lines += (if (block.optString("type") == "bullet") "• " else "") + line
                }
                val children = block.optJSONArray("children") ?: JSONArray()
                for (i in 0 until children.length()) visit(children.getString(i), depth + 1)
            }
            visit(root, 0)
            val notes = lines.joinToString("\n\n")
            check(notes.isNotBlank() && notes.length <= 64 * 1024) { "更新日志暂不可用" }
            return notes
        }
    }
}
