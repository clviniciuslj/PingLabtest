package br.com.pingreal

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object GeminiConfig {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

    /**
     * Primeira opção: o modelo Flash mais inteligente com uso gratuito. Identificador e gratuidade conferidos
     * na documentação do Google (páginas de modelos e de preços). O app nunca troca de modelo sozinho.
     */
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    const val CHAT_TIMEOUT_MS = 180_000
    const val LIST_TIMEOUT_MS = 20_000
}

/** Uma fala da conversa. [role] é "user" ou "model", como a API espera. */
class ChatTurn(val role: String, val text: String)

enum class GeminiError { INVALID_KEY, FORBIDDEN, MODEL_UNAVAILABLE, RATE_LIMIT, BAD_REQUEST, SERVER, TIMEOUT, NETWORK, UNKNOWN }

sealed class GeminiResult {
    /** [thinkingDropped]: o modelo recusou o ajuste de raciocínio e respondeu no padrão. [truncated]: bateu no limite de tamanho. */
    class Reply(
        val text: String,
        val thinkingDropped: Boolean = false,
        val truncated: Boolean = false,
        val viaMobile: Boolean = false
    ) : GeminiResult()

    class Blocked(val reason: String) : GeminiResult()

    class Failure(
        val kind: GeminiError,
        val message: String,
        val retryAfterSeconds: Int? = null,
        val quotaId: String? = null,
        val quotaValue: String? = null
    ) : GeminiResult()
}

class ModelInfo(val id: String, val displayName: String)

sealed class ModelsResult {
    class Ok(val models: List<ModelInfo>) : ModelsResult()
    class Failure(val kind: GeminiError, val message: String) : ModelsResult()
}

/** A chamada HTTP em si. Interface para os testes responderem sem rede. */
interface GeminiTransport {
    fun call(method: String, url: String, apiKey: String, body: String?, timeoutMs: Int): HttpReply
}

/**
 * A chamada HTTP de verdade. Com [route], escolhe a cada chamada por onde sair (rede padrão ou dados móveis);
 * sem ela, usa a rede padrão do sistema.
 */
class UrlConnectionTransport(private val route: (() -> Route)? = null) : GeminiTransport {
    override fun call(method: String, url: String, apiKey: String, body: String?, timeoutMs: Int): HttpReply {
        val chosen = route?.invoke()
        try {
            if (chosen?.choice == RouteChoice.NO_INTERNET) {
                throw IOException("a rede atual está sem internet e os dados móveis não estão disponíveis (ligue os dados móveis)")
            }
            val target = URL(url)
            val conn = (chosen?.network?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
            return try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = timeoutMs
                // A chave vai no cabeçalho, nunca na URL (URLs aparecem em logs).
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "PingLab")
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                HttpReply(
                    status,
                    stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
                    viaMobile = chosen?.choice == RouteChoice.MOBILE
                )
            } finally {
                conn.disconnect()
            }
        } finally {
            chosen?.release?.invoke()
        }
    }
}

/** Fala com a API do Gemini (sem streaming). Não guarda a conversa nem a chave: recebe tudo a cada chamada. */
class GeminiClient(private val transport: GeminiTransport = UrlConnectionTransport()) {

    /** Só letras, números, ponto, hífen e sublinhado: o nome do modelo entra na URL. */
    private fun validModel(model: String) = model.matches(Regex("[A-Za-z0-9._-]{1,80}"))

    /**
     * Ajuste de raciocínio no nível mais alto, conforme a família do modelo (documentação do Google):
     * Gemini 3 e 4 usam `thinkingLevel` (HIGH); Gemini 2.5 usa `thinkingBudget` (-1 = dinâmico, até o máximo do modelo).
     * Os dois juntos dão erro 400, então só um é enviado. Null = não enviar nada.
     */
    fun thinkingConfig(model: String): JSONObject? = when {
        model.startsWith("gemini-3") || model.startsWith("gemini-4") -> JSONObject().put("thinkingLevel", "HIGH")
        model.startsWith("gemini-2.5") -> JSONObject().put("thinkingBudget", -1)
        else -> null
    }

    fun buildRequest(system: String, turns: List<ChatTurn>, model: String, withThinking: Boolean = true): String {
        val contents = JSONArray()
        for (turn in turns) {
            contents.put(
                JSONObject()
                    .put("role", turn.role)
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
            )
        }
        val root = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        val thinking = if (withThinking) thinkingConfig(model) else null
        // Sem limite de tamanho de resposta: os tokens de raciocínio contam como saída, e um limite baixo cortaria a resposta.
        if (thinking != null) root.put("generationConfig", JSONObject().put("thinkingConfig", thinking))
        return root.toString()
    }

    fun generate(apiKey: String, model: String, system: String, turns: List<ChatTurn>): GeminiResult {
        if (!validModel(model)) return GeminiResult.Failure(GeminiError.BAD_REQUEST, "nome de modelo inválido")
        val url = "${GeminiConfig.BASE_URL}/models/$model:generateContent"
        var result = send(url, apiKey, buildRequest(system, turns, model, true))
        // Se o modelo recusar só o ajuste de raciocínio, repete UMA vez sem ele, no MESMO modelo, e avisa.
        if (result is GeminiResult.Failure && result.kind == GeminiError.BAD_REQUEST &&
            thinkingConfig(model) != null && result.message.contains("think", ignoreCase = true)
        ) {
            val retry = send(url, apiKey, buildRequest(system, turns, model, false))
            result = if (retry is GeminiResult.Reply) GeminiResult.Reply(retry.text, thinkingDropped = true, truncated = retry.truncated, viaMobile = retry.viaMobile) else retry
        }
        return result
    }

    private fun send(url: String, apiKey: String, body: String): GeminiResult = try {
        val reply = transport.call("POST", url, apiKey, body, GeminiConfig.CHAT_TIMEOUT_MS)
        if (reply.status in 200..299) {
            val parsed = parseReply(reply.body)
            if (parsed is GeminiResult.Reply && reply.viaMobile) GeminiResult.Reply(parsed.text, parsed.thinkingDropped, parsed.truncated, true) else parsed
        } else {
            failureFrom(reply.status, reply.body)
        }
    } catch (_: SocketTimeoutException) {
        GeminiResult.Failure(GeminiError.TIMEOUT, "a resposta demorou demais")
    } catch (e: IOException) {
        GeminiResult.Failure(GeminiError.NETWORK, e.message ?: "falha de rede")
    }

    fun parseReply(body: String): GeminiResult {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return GeminiResult.Failure(GeminiError.UNKNOWN, "resposta inesperada do Gemini")
        }
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            return if (block.isNotBlank()) GeminiResult.Blocked(block)
            else GeminiResult.Failure(GeminiError.UNKNOWN, "o Gemini não devolveu resposta")
        }
        val candidate = candidates.getJSONObject(0)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought", false)) continue // resumo do raciocínio: não é a resposta
                sb.append(part.optString("text", ""))
            }
        }
        val finish = candidate.optString("finishReason", "")
        val text = sb.toString().trim()
        if (text.isEmpty()) {
            return if (finish in BLOCK_REASONS) GeminiResult.Blocked(finish)
            else GeminiResult.Failure(GeminiError.UNKNOWN, "o Gemini devolveu uma resposta vazia ($finish)")
        }
        return GeminiResult.Reply(text, truncated = finish == "MAX_TOKENS")
    }

    fun failureFrom(status: Int, body: String): GeminiResult.Failure {
        val error = try {
            JSONObject(body).optJSONObject("error")
        } catch (_: Exception) {
            null
        }
        val message = error?.optString("message").orEmpty()
        val kind = when {
            status == 400 && message.contains("API key", ignoreCase = true) -> GeminiError.INVALID_KEY
            status == 400 -> GeminiError.BAD_REQUEST
            status == 401 -> GeminiError.INVALID_KEY
            status == 403 -> GeminiError.FORBIDDEN
            status == 404 -> GeminiError.MODEL_UNAVAILABLE
            status == 429 -> GeminiError.RATE_LIMIT
            status >= 500 -> GeminiError.SERVER
            else -> GeminiError.UNKNOWN
        }
        val (quotaId, quotaValue) = quotaOf(error)
        return GeminiResult.Failure(kind, message.ifBlank { "HTTP $status" }, retrySeconds(error, message), quotaId, quotaValue)
    }

    /** Qual cota estourou (por dia, por minuto, por tokens...), do campo QuotaFailure do erro. */
    private fun quotaOf(error: JSONObject?): Pair<String?, String?> {
        val details = error?.optJSONArray("details") ?: return null to null
        for (i in 0 until details.length()) {
            val violations = details.optJSONObject(i)?.optJSONArray("violations") ?: continue
            val first = violations.optJSONObject(0) ?: continue
            val id = first.optString("quotaId").ifBlank { first.optString("quotaMetric") }.ifBlank { null }
            return id to first.optString("quotaValue").ifBlank { null }
        }
        return null to null
    }

    /** Quanto o Google pede para esperar: no campo RetryInfo ou na frase "retry in 34s". */
    private fun retrySeconds(error: JSONObject?, message: String): Int? {
        val details = error?.optJSONArray("details")
        if (details != null) {
            for (i in 0 until details.length()) {
                val delay = details.optJSONObject(i)?.optString("retryDelay").orEmpty()
                Regex("([0-9]+(?:\\.[0-9]+)?)s").find(delay)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                    return kotlin.math.ceil(it).toInt()
                }
            }
        }
        return Regex("retry in ([0-9]+(?:\\.[0-9]+)?)s", RegexOption.IGNORE_CASE).find(message)
            ?.groupValues?.get(1)?.toDoubleOrNull()?.let { kotlin.math.ceil(it).toInt() }
    }

    /** Os modelos de conversa que a chave enxerga (sem os de voz, imagem, vídeo e embeddings). */
    fun listModels(apiKey: String): ModelsResult {
        return try {
            val reply = transport.call("GET", "${GeminiConfig.BASE_URL}/models?pageSize=200", apiKey, null, GeminiConfig.LIST_TIMEOUT_MS)
            if (reply.status !in 200..299) {
                val f = failureFrom(reply.status, reply.body)
                return ModelsResult.Failure(f.kind, f.message)
            }
            ModelsResult.Ok(parseModels(reply.body))
        } catch (_: SocketTimeoutException) {
            ModelsResult.Failure(GeminiError.TIMEOUT, "a consulta demorou demais")
        } catch (e: IOException) {
            ModelsResult.Failure(GeminiError.NETWORK, e.message ?: "falha de rede")
        } catch (_: Exception) {
            ModelsResult.Failure(GeminiError.UNKNOWN, "resposta inesperada do Gemini")
        }
    }

    fun parseModels(body: String): List<ModelInfo> {
        val array = JSONObject(body).optJSONArray("models") ?: return emptyList()
        val out = ArrayList<ModelInfo>()
        for (i in 0 until array.length()) {
            val m = array.getJSONObject(i)
            val id = m.optString("name").removePrefix("models/")
            if (!id.startsWith("gemini-") || NOT_CHAT.containsMatchIn(id)) continue
            val methods = m.optJSONArray("supportedGenerationMethods")
            var chat = false
            if (methods != null) for (k in 0 until methods.length()) if (methods.optString(k) == "generateContent") chat = true
            if (chat) out.add(ModelInfo(id, m.optString("displayName").ifBlank { id }))
        }
        return out.sortedByDescending { it.id }
    }

    companion object {
        private val BLOCK_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY")
        private val NOT_CHAT = Regex("tts|image|live|embed|audio|video|veo|imagen|robotics|computer-use|transcribe|native")

        /** O texto que o usuário lê para cada tipo de erro. */
        fun describe(f: GeminiResult.Failure, model: String): String = when (f.kind) {
            GeminiError.INVALID_KEY -> "A chave do Gemini não foi aceita. Confira se colou a chave inteira ou crie outra."
            GeminiError.FORBIDDEN -> "O Google negou o acesso (${f.message}). A chave pode não ter permissão para este modelo ou região."
            GeminiError.MODEL_UNAVAILABLE -> "O modelo $model não está disponível para a sua chave (${f.message}). Escolha outro manualmente."
            GeminiError.RATE_LIMIT -> rateLimitText(f, model)
            GeminiError.BAD_REQUEST -> "O Gemini recusou o pedido: ${f.message}"
            GeminiError.SERVER -> "O Gemini está com problema no servidor (${f.message}). Tente de novo em instantes."
            GeminiError.TIMEOUT -> "O Gemini demorou demais para responder. Tente de novo."
            GeminiError.NETWORK -> "Sem conexão com o Gemini (${f.message}). Confira a internet."
            GeminiError.UNKNOWN -> "Não foi possível obter a resposta: ${f.message}"
        }

        /** 80000 s vira "22 h 13 min": prazos longos são quase sempre o limite diário. */
        fun formatWait(seconds: Int): String = when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
            else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        }

        fun rateLimitText(f: GeminiResult.Failure, model: String): String {
            val id = f.quotaId.orEmpty()
            val limit = f.quotaValue?.let { " (limite: $it)" }.orEmpty()
            val wait = f.retryAfterSeconds?.let { " O Google pede para esperar ${formatWait(it)}." }.orEmpty()
            val head = when {
                id.contains("PerDay", ignoreCase = true) ->
                    "O limite DIÁRIO do plano gratuito para o modelo $model acabou$limit. Ele reinicia à meia-noite do horário do Pacífico (por volta das 4h ou 5h da manhã em Brasília).$wait Você pode escolher outro modelo à mão."
                id.contains("PerMinute", ignoreCase = true) ->
                    "Limite por minuto do plano gratuito atingido para o modelo $model$limit.$wait"
                else -> "Limite gratuito do Gemini atingido para o modelo $model.$wait"
            }
            return head + "\n\nMensagem do Google: " + f.message.take(400)
        }

        fun describeBlock(reason: String) = "O Gemini não respondeu esta mensagem (motivo: $reason). Reformule a pergunta."
    }
}
