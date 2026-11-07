package br.com.pingreal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * ONUs cadastradas, em um arquivo JSON local. A gravação vai para um arquivo temporário seguido de troca; um
 * arquivo corrompido vira `.bak` e o app inicia vazio com aviso (como o Monitor de ONUs do PC).
 */
class OnuStore(private val file: File) {

    class Loaded(val onus: List<Onu>, val warning: String?)

    fun load(): Loaded {
        if (!file.exists()) return Loaded(emptyList(), null)
        return try {
            val array = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("onus")
            val onus = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Onu(
                    o.getString("id"),
                    o.getInt("quadra"),
                    o.getInt("lote"),
                    if (o.isNull("identificacao")) null else o.getString("identificacao")
                )
            }
            Loaded(onus, null)
        } catch (_: Exception) {
            val backup = File(file.parentFile, file.name + ".bak")
            try {
                backup.delete()
                file.renameTo(backup)
            } catch (_: Exception) {
            }
            Loaded(emptyList(), "O arquivo de ONUs estava corrompido e foi guardado como ${backup.name}.")
        }
    }

    @Throws(IOException::class)
    fun save(onus: List<Onu>) {
        val array = JSONArray()
        for (o in onus) {
            array.put(
                JSONObject()
                    .put("id", o.id)
                    .put("quadra", o.quadra)
                    .put("lote", o.lote)
                    .put("identificacao", o.identificacao ?: JSONObject.NULL)
            )
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(JSONObject().put("onus", array).toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Não foi possível gravar ${file.name}.")
        }
    }
}

class ImportError(val line: Int, val text: String, val reason: String)

class ImportResult(val valid: List<Onu>, val errors: List<ImportError>)

object OnuImporter {
    private val IP_PATTERN = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Aceita linhas "quadra,lote[,identificação]" ou "IP[,identificação]", separadas por vírgula, ponto e vírgula ou tab. */
    fun parse(text: String, prefix: String, existing: List<Onu>): ImportResult {
        val seen = existing.map { it.quadra to it.lote }.toHashSet()
        val valid = ArrayList<Onu>()
        val errors = ArrayList<ImportError>()
        val lines = text.trimStart('\uFEFF').split('\n')
        var headerChecked = false

        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (!headerChecked) {
                headerChecked = true
                if (line.startsWith("quadra", ignoreCase = true)) continue
            }

            val tokens = line.split(',', ';', '\t').map { it.trim() }
            val key = parseKey(tokens, prefix)
            if (key.reason != null) {
                errors.add(ImportError(i + 1, line, key.reason))
                continue
            }
            if (!seen.add(key.quadra to key.lote)) {
                errors.add(ImportError(i + 1, line, "Quadra ${key.quadra} lote ${key.lote} já cadastrado."))
                continue
            }
            valid.add(Onu.create(key.quadra, key.lote, key.ident))
        }
        return ImportResult(valid, errors)
    }

    private class Key(val quadra: Int, val lote: Int, val ident: String?, val reason: String?)

    private fun fail(reason: String) = Key(0, 0, null, reason)

    private fun parseKey(tokens: List<String>, prefix: String): Key {
        val quadra: Int
        val lote: Int
        val ident: String?

        if (IP_PATTERN.matches(tokens[0])) {
            if (!tokens[0].startsWith("$prefix.")) return fail("IP fora do prefixo $prefix.")
            val rest = tokens[0].substring(prefix.length + 1).split('.')
            val q = rest.getOrNull(0)?.toIntOrNull()
            val l = rest.getOrNull(1)?.toIntOrNull()
            if (rest.size != 2 || q == null || l == null) return fail("IP deve ser $prefix.QUADRA.LOTE.")
            quadra = q
            lote = l
            ident = tokens.getOrNull(1)
        } else {
            val q = tokens.getOrNull(0)?.toIntOrNull()
            val l = tokens.getOrNull(1)?.toIntOrNull()
            if (tokens.size < 2 || q == null || l == null) {
                return fail("Formato inválido. Use: quadra,lote[,identificação] ou o IP.")
            }
            quadra = q
            lote = l
            ident = tokens.getOrNull(2)
        }

        if (quadra !in 0..255 || lote !in 0..255) return fail("Quadra e lote devem estar entre 0 e 255.")
        return Key(quadra, lote, ident, null)
    }
}
