package com.shilapi.xcertplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReleasePolicyTest {
    private fun entry(version: String, id: String = "iKN9k4ba9eti") = JSONObject()
        .put("t", 0).put("id", id).put("name_all", "JiliCarPlay-$version.apk")

    @Test fun picksNewestApkNumericallyAndSkipsCurrentOrOlderVersions() {
        val files = listOf("0.2.14", "0.2.15", "0.2.16", "0.10.0").map { ReleasePolicy.entry(entry(it))!! }
        assertEquals("0.10.0", ReleasePolicy.latest(files, "0.2.15")!!.version)
        assertNull(ReleasePolicy.latest(files, "0.10.0"))
        assertTrue(ReleasePolicy.newer("0.2.16", "0.2.16-beta.1"))
        assertFalse(ReleasePolicy.newer("bad", "0.2.15"))
        assertFalse(ReleasePolicy.newer("999999999999999999999.2.16", "0.2.15"))
    }
    @Test fun ignoresAdvertisementsForeignLinksAndUnversionedFiles() {
        assertNull(ReleasePolicy.entry(entry("0.2.16").put("t", 1)))
        assertNull(ReleasePolicy.entry(entry("0.2.16", "https://evil.example/a.apk")))
        for (name in listOf("a.apk", "OtherApp-0.2.16.apk", "JiliCarPlay-0.2.16-beta.apk", "<b>JiliCarPlay-0.2.16.apk</b>")) {
            assertNull(ReleasePolicy.entry(entry("0.2.16").put("name_all", name)))
        }
        assertEquals("https://1280ds.lanzoue.com/iKN9k4ba9eti", ReleasePolicy.entry(entry("0.2.16"))!!.url)
    }
    @Test fun onlyProviderHttpsPagesCanOpenInUpdateDialog() {
        assertTrue(ReleasePolicy.downloadPageAllowed(ReleasePolicy.FOLDER_URL))
        assertTrue(ReleasePolicy.downloadPageAllowed("https://slsstm2.dmpdmp.com/file/?test"))
        for (url in listOf("intent://install", "file:///data/private", "http://1280ds.lanzoue.com/file", "https://dmpdmp.com.evil.example/file", "https://user@1280ds.lanzoue.com/a", "https://1280ds.lanzoue.com:80/a")) {
            assertFalse(url, ReleasePolicy.downloadPageAllowed(url))
        }
    }
    @Test fun folderTokensAreReadFromPageInsteadOfHardcoded() {
        val html = """var time_key='1791508602';var sign_key='changing-token';
            url:'/filemoreajax.php?file=14068992',data:{'lx':2,'fid':14068992,'uid':'941341',
            'pg':pgs,'t':time_key,'k':sign_key,'pwd':pwd} """
        val request = UpdateSource.folderRequest(html)
        assertEquals("https://1280ds.lanzoue.com/filemoreajax.php?file=14068992", request.first)
        assertEquals("changing-token", request.second["k"])
        assertEquals("1791508602", request.second["t"])
        assertThrows(IllegalStateException::class.java) { UpdateSource.folderRequest("a login page") }
    }
    @Test fun providerCookieMatchesPublishedPageAlgorithm() {
        assertEquals("6ac83d28535ee5b1722eea1317f45505c293a143", UpdateSource.challengeCookie("4315530035B3425AEBD47B787753CCBF2AA628E8"))
    }
    @Test fun logUsesPublicMobileReaderWhileCloudUsesDesktopDownloadPage() {
        assertTrue(ReleasePolicy.userAgent("my.feishu.cn").contains("Mobile"))
        assertFalse(ReleasePolicy.userAgent(ReleasePolicy.CLOUD_HOST).contains("Mobile"))
    }
    @Test fun logUsesDocumentOrderNotMetadataOrHiddenBlocks() {
        fun block(type: String, children: String, text: String, hidden: Boolean = false) = """{"data":{"type":"$type","children":$children,"hidden":$hidden,"text":{"initialAttributedTexts":{"text":$text}}}}"""
        val html = """<script>window.DATA={clientVars: Object({"data":{"next_cursors":[],"block_map":{
            "b":${block("text", "[]", """{"0":"修复连接问题。"}""")},
            "root":${block("page", """["a","hidden","b"]""", """{"0":"更新日志"}""")},
            "hidden":${block("text", "[]", """{"0":"不应显示"}""", true)},
            "a":${block("bullet", "[]", """{"1":"应用图标。","0":"更新"}""")}
        }},"private_metadata":"user-id-must-not-appear"}),meta:{title:'not log content'}};</script>"""
        assertEquals("• 更新应用图标。\n\n修复连接问题。", UpdateSource.parseNotes(html))
        assertThrows(IllegalStateException::class.java) { UpdateSource.parseNotes("<html>请登录</html>") }
    }
}
