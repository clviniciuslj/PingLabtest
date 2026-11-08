package br.com.pingreal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private fun releaseJson(tag: String, apk: Boolean = true, name: String = "1.17") = """
        {
          "tag_name": "$tag",
          "name": "$name",
          "body": "  Mudanças desta versão  ",
          "assets": [
            {"name": "notas.txt", "browser_download_url": "https://github.com/x/y/releases/download/$tag/notas.txt", "size": 10}
            ${if (apk) """,{"name": "PingLab-$name.apk", "browser_download_url": "https://github.com/x/y/releases/download/$tag/PingLab-$name.apk", "size": 2580000}""" else ""}
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheVersionNumberFromTheTag() {
        assertEquals(17L, Updater.codeFromTag("build-17"))
        assertNull(Updater.codeFromTag("v1.17"))
        assertNull(Updater.codeFromTag("build-abc"))
        assertNull(Updater.codeFromTag(""))
    }

    @Test
    fun parsesTheLatestRelease() {
        val r = Updater.parseRelease(releaseJson("build-17"))!!
        assertEquals(17L, r.code)
        assertEquals("1.17", r.name)
        assertEquals("Mudanças desta versão", r.notes)
        assertTrue(r.apkUrl.endsWith("PingLab-1.17.apk"))
        assertEquals(2580000L, r.sizeBytes)
    }

    @Test
    fun rejectsReleasesWithoutApkOrWithAForeignTag() {
        assertNull(Updater.parseRelease(releaseJson("build-17", apk = false)))
        assertNull(Updater.parseRelease(releaseJson("v1.17")))
        assertNull(Updater.parseRelease("isto não é json"))
    }

    @Test
    fun onlyAcceptsHttpsDownloadLinks() {
        val insecure = releaseJson("build-17").replace("https://", "http://")
        assertNull(Updater.parseRelease(insecure))
    }

    @Test
    fun comparesWithTheInstalledVersion() {
        fun result(current: Long, tag: String) = Updater.check(current, "1.16") { HttpReply(200, releaseJson(tag)) }

        assertTrue(result(16, "build-17") is UpdateResult.Newer)
        val same = result(17, "build-17")
        assertTrue(same is UpdateResult.UpToDate)
        assertEquals("1.16", (same as UpdateResult.UpToDate).current)
        // Instalada mais nova que a publicada (por exemplo, depois de uma volta de versão): não oferece "atualizar".
        assertTrue(result(20, "build-17") is UpdateResult.UpToDate)
        assertNotNull((result(16, "build-17") as UpdateResult.Newer).release.apkUrl)
    }

    @Test
    fun explainsEachKindOfFailure() {
        assertTrue(Updater.check(1, "1") { HttpReply(404, "{}") } is UpdateResult.NoReleases)
        assertTrue(Updater.check(1, "1") { HttpReply(403, "rate limit") } is UpdateResult.Failed)
        assertTrue(Updater.check(1, "1") { HttpReply(200, "lixo") } is UpdateResult.Failed)
        assertTrue(Updater.check(1, "1") { throw java.io.IOException("sem rede") } is UpdateResult.Failed)
    }
}
