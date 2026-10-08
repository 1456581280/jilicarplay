package com.shilapi.xcertplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReleasePolicyTest {
    private fun metadata() = JSONObject("""{
      "tag_name":"v0.2.16", "draft":false, "prerelease":false,
      "assets":[{"name":"JiliCarPlay-0.2.16.apk", "state":"uploaded", "size":10,
       "digest":"sha256:${"a".repeat(64)}",
       "browser_download_url":"https://github.com/1456581280/jilicarplay/releases/download/v0.2.16/JiliCarPlay-0.2.16.apk"}]
    }""")
    @Test fun stableUpgradeUsesNumericVersionComparison() {
        assertTrue(ReleasePolicy.newer("0.2.16", "0.2.15"))
        assertTrue(ReleasePolicy.newer("0.10.0", "0.9.99"))
        assertFalse(ReleasePolicy.newer("0.2.14", "0.2.15"))
        assertFalse(ReleasePolicy.newer("0.2.15", "0.2.15"))
        assertTrue(ReleasePolicy.newer("0.2.15", "0.2.15-beta.1"))
        assertFalse(ReleasePolicy.newer("bad", "0.2.15"))
        assertEquals("0.2.16", ReleasePolicy.parse(metadata(), "0.2.15")!!.version)
    }
    @Test fun ignoresDraftPrereleaseOlderAndMissingAssets() {
        assertNull(ReleasePolicy.parse(metadata().put("draft", true), "0.2.15"))
        assertNull(ReleasePolicy.parse(metadata().put("prerelease", true), "0.2.15"))
        assertNull(ReleasePolicy.parse(metadata(), "0.2.16"))
        assertNull(ReleasePolicy.parse(metadata().put("assets", org.json.JSONArray()), "0.2.15"))
    }
    @Test fun rejectsWrongRepositoriesInsecureUrlsAndMissingDigests() {
        for (url in listOf("http://github.com/1456581280/jilicarplay/releases/download/v0.2.16/a.apk",
            "https://github.com/other/repo/releases/download/v0.2.16/a.apk",
            "https://github.com.evil.example/1456581280/jilicarplay/releases/download/a.apk")) {
            val json=metadata(); json.getJSONArray("assets").getJSONObject(0).put("browser_download_url",url)
            assertNull(ReleasePolicy.parse(json,"0.2.15"))
        }
        val json=metadata();json.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertNull(ReleasePolicy.parse(json,"0.2.15"))
    }
    @Test fun detectsTruncatedOrModifiedDownloads() {
        val file=File.createTempFile("update-test", ".apk")
        try {
            file.writeText("payload")
            val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val release=AppRelease("0.2.16", "", file.length(), hash, "")
            ReleasePolicy.verifyBytes(file,release)
            file.writeText("changed")
            assertThrows(IllegalStateException::class.java) { ReleasePolicy.verifyBytes(file,release) }
            file.writeText("x")
            assertThrows(IllegalStateException::class.java) { ReleasePolicy.verifyBytes(file,release) }
        } finally { file.delete() }
    }
}
