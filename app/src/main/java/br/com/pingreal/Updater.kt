package br.com.pingreal

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** De onde vêm as atualizações: as Releases do repositório do GitHub (público). */
object UpdateConfig {
    const val REPO = "clviniciuslj/PingLabtest"

    /** O workflow publica cada build numa Release com a tag `build-<versionCode>`. */
    const val TAG_PREFIX = "build-"
}

class ReleaseInfo(
    val code: Long,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long
)

sealed class UpdateResult {
    class Newer(val release: ReleaseInfo) : UpdateResult()
    class UpToDate(val current: String) : UpdateResult()
    object NoReleases : UpdateResult()
    class Failed(val reason: String) : UpdateResult()
}

/** Resposta crua do GitHub: código HTTP e corpo. */
class HttpReply(val status: Int, val body: String)

object Updater {

    /** Número da versão (versionCode) escrito na tag `build-17`. Null se a tag não segue o padrão. */
    fun codeFromTag(tag: String): Long? =
        tag.takeIf { it.startsWith(UpdateConfig.TAG_PREFIX) }?.removePrefix(UpdateConfig.TAG_PREFIX)?.toLongOrNull()

    /** Lê o JSON de `releases/latest`. Null se faltar a tag no padrão ou o arquivo .apk. */
    fun parseRelease(json: String): ReleaseInfo? = try {
        val root = JSONObject(json)
        val code = codeFromTag(root.optString("tag_name"))
        val assets = root.optJSONArray("assets")
        var url: String? = null
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    size = asset.optLong("size", 0L)
                    break
                }
            }
        }
        if (code == null || url == null) {
            null
        } else {
            ReleaseInfo(code, root.optString("name").ifBlank { "versão $code" }, root.optString("body").trim(), url, size)
        }
    } catch (_: Exception) {
        null
    }

    /** Pergunta ao GitHub qual é a última Release e compara com a versão instalada. Bloqueia: chame fora da tela. */
    fun check(
        currentCode: Long,
        currentName: String,
        fetch: () -> HttpReply = { httpGetLatest() }
    ): UpdateResult {
        val reply = try {
            fetch()
        } catch (_: Exception) {
            return UpdateResult.Failed("sem conexão com o GitHub")
        }
        return when {
            reply.status == 404 -> UpdateResult.NoReleases
            reply.status != 200 -> UpdateResult.Failed("o GitHub respondeu ${reply.status}")
            else -> {
                val release = parseRelease(reply.body) ?: return UpdateResult.Failed("resposta inesperada do GitHub")
                if (release.code > currentCode) UpdateResult.Newer(release) else UpdateResult.UpToDate(currentName)
            }
        }
    }

    private fun httpGetLatest(): HttpReply {
        val conn = URL("https://api.github.com/repos/${UpdateConfig.REPO}/releases/latest").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "PingLab")
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            HttpReply(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Baixa o APK para [target] (escreve num arquivo temporário e só renomeia no fim, para nunca sobrar um
     * APK pela metade). Devolve false se foi cancelado. Lança em caso de erro de rede.
     */
    fun download(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean
    ): Boolean {
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "PingLab")
            if (conn.responseCode != 200) throw java.io.IOException("o GitHub respondeu ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var done = 0L
            conn.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (cancelled()) {
                            temp.delete()
                            return false
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (done == 0L || (total > 0 && done != total)) {
                temp.delete()
                throw java.io.IOException("download incompleto")
            }
            target.delete()
            if (!temp.renameTo(target)) throw java.io.IOException("não foi possível salvar o arquivo")
            return true
        } finally {
            conn.disconnect()
        }
    }
}
