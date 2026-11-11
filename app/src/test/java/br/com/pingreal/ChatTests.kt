package br.com.pingreal

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Date

// ---------------------------------------------------------------------------------------------
// Apoio
// ---------------------------------------------------------------------------------------------

/** Rede de mentira: devolve as respostas na ordem e guarda o que foi enviado. */
private class FakeTransport(private vararg val replies: Any) : GeminiTransport {
    class Call(val method: String, val url: String, val apiKey: String, val body: String?)

    val calls = ArrayList<Call>()

    override fun call(method: String, url: String, apiKey: String, body: String?, timeoutMs: Int): HttpReply {
        calls.add(Call(method, url, apiKey, body))
        val next = replies[minOf(calls.size - 1, replies.lastIndex)]
        if (next is Throwable) throw next
        return next as HttpReply
    }
}

private fun ok(text: String, finish: String = "STOP") = HttpReply(
    200,
    """{"candidates":[{"content":{"role":"model","parts":[{"text":${JSONObject.quote(text)}}]},"finishReason":"$finish"}]}"""
)

private fun turns(vararg pairs: Pair<String, String>) = pairs.map { ChatTurn(it.first, it.second) }

private fun snapshot(at: Long = 1_700_000_000_000L, pubDetail: String = "IP público 179.1.2.3 · AS65000 Provedor Exemplo · Rio, BR") =
    DiagnosticSnapshot(
        at, "Wi-Fi", "192.168.1.20", "192.168.1.1", "192.168.1.1",
        listOf(
            SnapshotStep("Conexão com a rede", Status.OK, "Wi-Fi conectado", false),
            SnapshotStep("DNS", Status.WARN, "lento", false),
            SnapshotStep("IP público e provedor", Status.OK, pubDetail, true)
        ),
        SpeedResult(300.0, 100.0, 12.0, 2.0)
    )

/** SharedPreferences na memória, para testar o armazenamento da chave. */
private class MemoryPrefs : SharedPreferences {
    val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = also { map[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = also { map[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = also { map[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = also { map[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = also { map[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = also { map[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = also { map.remove(key) }
        override fun clear(): SharedPreferences.Editor = also { map.clear() }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}

/** Cifrador de mentira, reversível e que não deixa o texto original à vista. */
private class FakeCipher(private val fail: Boolean = false) : KeyCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        if (fail) throw IllegalStateException("sem keystore")
        return plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    override fun decrypt(data: ByteArray): ByteArray = data.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
}

// ---------------------------------------------------------------------------------------------
// Chave protegida
// ---------------------------------------------------------------------------------------------

class SecureKeyStoreTest {
    private val secret = "AIzaSyExemploDeChaveQueNaoEhReal1234567"

    @Test
    fun keyRoundTripsAndNeverSitsInPlainText() {
        val prefs = MemoryPrefs()
        val store = SecureKeyStore(prefs, FakeCipher())
        assertNull(store.load())
        assertTrue(store.save(secret))
        assertTrue(store.hasKey())
        assertEquals(secret, store.load())
        val stored = prefs.map[Prefs.AI_KEY_ENC] as String
        assertFalse(stored.contains(secret))
        assertFalse(stored.contains("AIza"))
    }

    @Test
    fun clearRemovesTheKey() {
        val store = SecureKeyStore(MemoryPrefs(), FakeCipher())
        store.save(secret)
        store.clear()
        assertFalse(store.hasKey())
        assertNull(store.load())
    }

    @Test
    fun saveFailsWithoutWritingWhenTheDeviceCannotEncrypt() {
        val prefs = MemoryPrefs()
        assertFalse(SecureKeyStore(prefs, FakeCipher(fail = true)).save(secret))
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun unreadableRecordIsDroppedInsteadOfCrashing() {
        val prefs = MemoryPrefs()
        prefs.map[Prefs.AI_KEY_ENC] = "isto não é base64 válido!!"
        val store = SecureKeyStore(prefs, FakeCipher())
        assertNull(store.load())
        assertFalse(store.hasKey())
    }

    @Test
    fun formatCheckAndMask() {
        assertTrue(SecureKeyStore.looksLikeKey(secret))
        assertFalse(SecureKeyStore.looksLikeKey("curta"))
        assertFalse(SecureKeyStore.looksLikeKey("AIzaSy com espaço no meio da chave"))
        assertEquals("AIza…4567", SecureKeyStore.mask(secret))
        assertFalse(SecureKeyStore.mask(secret).contains("ExemploDeChave"))
    }
}

// ---------------------------------------------------------------------------------------------
// Cliente do Gemini
// ---------------------------------------------------------------------------------------------

class GeminiClientTest {
    private val history = turns("user" to "olá", "model" to "oi", "user" to "e o DNS?")

    @Test
    fun requestHasSystemHistoryAndHighThinkingForGemini3() {
        val client = GeminiClient(FakeTransport())
        val json = JSONObject(client.buildRequest("SISTEMA", history, "gemini-3.8-flash"))
        assertEquals("SISTEMA", json.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = json.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("e o DNS?", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"))
        val thinking = json.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertEquals("HIGH", thinking.getString("thinkingLevel"))
        assertFalse(thinking.has("thinkingBudget"))
        // sem teto de resposta: os tokens de raciocínio contam como saída
        assertFalse(json.getJSONObject("generationConfig").has("maxOutputTokens"))
    }

    @Test
    fun thinkingSettingDependsOnTheModelFamilyAndNeverSendsBoth() {
        val client = GeminiClient(FakeTransport())
        val v25 = client.thinkingConfig("gemini-2.5-flash")!!
        assertEquals(-1, v25.getInt("thinkingBudget"))
        assertFalse(v25.has("thinkingLevel"))
        assertEquals("HIGH", client.thinkingConfig("gemini-4-argon")!!.getString("thinkingLevel"))
        assertNull(client.thinkingConfig("gemini-1.5-flash"))
        assertFalse(JSONObject(client.buildRequest("s", history, "gemini-1.5-flash")).has("generationConfig"))
    }

    @Test
    fun generateReturnsTheTextKeepsTheKeyOutOfTheUrlAndUsesTheChosenModel() {
        val transport = FakeTransport(ok("Resposta **boa**"))
        val result = GeminiClient(transport).generate("CHAVE-SECRETA-123456789", "gemini-3.8-flash", "s", history)
        assertEquals("Resposta **boa**", (result as GeminiResult.Reply).text)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("CHAVE-SECRETA-123456789", call.apiKey)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", call.url)
        assertFalse(call.url.contains("CHAVE-SECRETA"))
        assertFalse(call.body!!.contains("CHAVE-SECRETA"))
    }

    @Test
    fun thoughtSummariesAreNotShownAsTheAnswer() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"pensando...","thought":true},{"text":"A resposta."}]},"finishReason":"STOP"}]}"""
        val result = GeminiClient(FakeTransport(HttpReply(200, body))).generate("k", "gemini-3.8-flash", "s", history)
        assertEquals("A resposta.", (result as GeminiResult.Reply).text)
    }

    @Test
    fun truncatedAndBlockedAnswersAreReportedAsSuch() {
        val cut = GeminiClient(FakeTransport(ok("texto", "MAX_TOKENS"))).generate("k", "gemini-3.8-flash", "s", history)
        assertTrue((cut as GeminiResult.Reply).truncated)
        val blockedBody = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        val blocked = GeminiClient(FakeTransport(HttpReply(200, blockedBody))).generate("k", "gemini-3.8-flash", "s", history)
        assertEquals("SAFETY", (blocked as GeminiResult.Blocked).reason)
    }

    @Test
    fun invalidModelNameNeverReachesTheNetwork() {
        val transport = FakeTransport(ok("x"))
        val result = GeminiClient(transport).generate("k", "gemini/../x?y=1", "s", history)
        assertEquals(GeminiError.BAD_REQUEST, (result as GeminiResult.Failure).kind)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun mapsHttpErrorsToKinds() {
        fun kind(status: Int, body: String) = GeminiClient(FakeTransport(HttpReply(status, body)))
            .generate("k", "gemini-3.8-flash", "s", history).let { (it as GeminiResult.Failure).kind }
        assertEquals(GeminiError.INVALID_KEY, kind(400, """{"error":{"message":"API key not valid. Please pass a valid API key."}}"""))
        assertEquals(GeminiError.BAD_REQUEST, kind(400, """{"error":{"message":"Invalid JSON payload"}}"""))
        assertEquals(GeminiError.FORBIDDEN, kind(403, """{"error":{"message":"Permission denied"}}"""))
        assertEquals(GeminiError.MODEL_UNAVAILABLE, kind(404, """{"error":{"message":"models/x is not found"}}"""))
        assertEquals(GeminiError.RATE_LIMIT, kind(429, """{"error":{"message":"Quota exceeded"}}"""))
        assertEquals(GeminiError.SERVER, kind(503, """{"error":{"message":"overloaded"}}"""))
    }

    @Test
    fun readsHowLongToWaitOnTheFreeLimit() {
        val byField = """{"error":{"message":"Quota exceeded","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"34s"}]}}"""
        val a = GeminiClient(FakeTransport(HttpReply(429, byField))).generate("k", "gemini-3.8-flash", "s", history) as GeminiResult.Failure
        assertEquals(34, a.retryAfterSeconds)
        val byText = """{"error":{"message":"You exceeded your quota. Please retry in 12.3s."}}"""
        val b = GeminiClient(FakeTransport(HttpReply(429, byText))).generate("k", "gemini-3.8-flash", "s", history) as GeminiResult.Failure
        assertEquals(13, b.retryAfterSeconds)
    }

    @Test
    fun rateLimitDoesNotSwitchModelOrRetryAutomatically() {
        val transport = FakeTransport(HttpReply(429, """{"error":{"message":"Quota exceeded"}}"""))
        GeminiClient(transport).generate("k", "gemini-3.8-flash", "s", history)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun networkProblemsAreClassified() {
        val timeout = GeminiClient(FakeTransport(SocketTimeoutException("lento"))).generate("k", "gemini-3.8-flash", "s", history)
        assertEquals(GeminiError.TIMEOUT, (timeout as GeminiResult.Failure).kind)
        val offline = GeminiClient(FakeTransport(IOException("sem rede"))).generate("k", "gemini-3.8-flash", "s", history)
        assertEquals(GeminiError.NETWORK, (offline as GeminiResult.Failure).kind)
    }

    @Test
    fun retriesOnceWithoutThinkingOnTheSameModelWhenTheModelRefusesIt() {
        val refuse = HttpReply(400, """{"error":{"message":"Thinking level is not supported for this model"}}""")
        val transport = FakeTransport(refuse, ok("respondeu"))
        val result = GeminiClient(transport).generate("k", "gemini-3.8-flash", "s", history)
        result as GeminiResult.Reply
        assertTrue(result.thinkingDropped)
        assertEquals(2, transport.calls.size)
        assertEquals(transport.calls[0].url, transport.calls[1].url) // mesmo modelo
        assertTrue(JSONObject(transport.calls[0].body!!).has("generationConfig"))
        assertFalse(JSONObject(transport.calls[1].body!!).has("generationConfig"))
    }

    @Test
    fun otherBadRequestsAreNotRetried() {
        val transport = FakeTransport(HttpReply(400, """{"error":{"message":"Invalid JSON payload"}}"""))
        GeminiClient(transport).generate("k", "gemini-3.8-flash", "s", history)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun listsOnlyChatModelsNewestFirstAndKeepsTheChosenOneVisible() {
        val body = """{"models":[
            {"name":"models/gemini-3.6-flash","displayName":"Gemini 3.6 Flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-3.8-flash","displayName":"Gemini 3.8 Flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.8-flash-tts","displayName":"TTS","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.1-flash-image","displayName":"Imagem","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-embedding-001","displayName":"Embedding","supportedGenerationMethods":["embedContent"]},
            {"name":"models/text-bison","displayName":"Antigo","supportedGenerationMethods":["generateContent"]}
        ]}"""
        val result = GeminiClient(FakeTransport(HttpReply(200, body))).listModels("k") as ModelsResult.Ok
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.6-flash"), result.models.map { it.id })
    }

    @Test
    fun listModelsReportsAnInvalidKey() {
        val result = GeminiClient(FakeTransport(HttpReply(400, """{"error":{"message":"API key not valid."}}"""))).listModels("k")
        assertEquals(GeminiError.INVALID_KEY, (result as ModelsResult.Failure).kind)
    }

    @Test
    fun defaultModelIsTheOneConfirmedInTheDocs() {
        assertEquals("gemini-3.8-flash", GeminiConfig.DEFAULT_MODEL)
    }
}

// ---------------------------------------------------------------------------------------------
// Conversa
// ---------------------------------------------------------------------------------------------

class ChatSessionTest {
    @Test
    fun firstSnapshotAsksForAnalysisAndLaterOnesForAComparison() {
        val s = ChatSession()
        val first = s.addSnapshot(snapshot(1000L), includePublicIp = false)
        val second = s.addSnapshot(snapshot(2000L), includePublicIp = false)
        assertTrue(first.text.startsWith("Analise este diagnóstico"))
        assertTrue(second.text.startsWith("Rodei outro diagnóstico"))
        assertTrue(first.snapshotLabel!!.startsWith("Diagnóstico das"))
        assertTrue(second.snapshotLabel!!.startsWith("Diagnóstico 2 das"))
        assertTrue(s.hasSnapshot(1000L))
        assertFalse(s.hasSnapshot(3000L))
        assertEquals(2, s.snapshotCount)
    }

    @Test
    fun historyKeepsRolesAndMergesConsecutiveUserMessages() {
        val s = ChatSession()
        s.addSnapshot(snapshot(), false)
        s.addUser("e o DNS?")
        s.addModel("resposta")
        s.addUser("valeu")
        val t = s.turns()
        assertEquals(listOf("user", "model", "user"), t.map { it.role })
        assertTrue(t[0].text.contains("e o DNS?")) // o diagnóstico e a pergunta seguida viraram uma fala só
        assertEquals("valeu", t[2].text)
    }

    @Test
    fun truncationDropsOldTextButNeverTheDiagnosticsNorTheLastMessage() {
        val s = ChatSession()
        s.addSnapshot(snapshot(1L), false)
        repeat(20) {
            s.addUser("pergunta $it " + "x".repeat(200))
            s.addModel("resposta $it " + "y".repeat(200))
        }
        s.addUser("ULTIMA")
        val t = s.turns(maxChars = 1500)
        assertEquals("user", t.first().role)
        assertTrue(t.first().text.contains("Analise este diagnóstico"))
        assertTrue(t.last().text.contains("ULTIMA"))
        assertTrue(t.sumOf { it.text.length } < 2600)
    }

    @Test
    fun clearEmptiesTheConversation() {
        val s = ChatSession()
        s.addUser("oi")
        s.clear()
        assertTrue(s.isEmpty)
        assertTrue(s.turns().isEmpty())
    }

    @Test
    fun systemPromptIsAFreeNetworkAssistantNotLockedToTheDiagnostic() {
        val p = ChatPrompts.SYSTEM
        assertTrue(p.contains("português do Brasil"))
        assertTrue(p.contains("contexto inicial"))
        assertTrue(p.contains("livremente"))
        assertTrue(p.contains("Não invente"))
    }
}

// ---------------------------------------------------------------------------------------------
// Diagnóstico enviado à IA
// ---------------------------------------------------------------------------------------------

class DiagnosticSnapshotTest {
    @Test
    fun publicIpIsOmittedByDefaultButTheProviderStays() {
        val text = snapshot().toText(includePublicIp = false)
        assertFalse(text.contains("179.1.2.3"))
        assertTrue(text.contains("IP público ${DiagnosticSnapshot.OMITTED}"))
        assertTrue(text.contains("AS65000 Provedor Exemplo"))
    }

    @Test
    fun publicIpIsIncludedOnlyWhenAsked() {
        assertTrue(snapshot().toText(includePublicIp = true).contains("179.1.2.3"))
    }

    @Test
    fun ipv6PublicAddressesAreOmittedToo() {
        val text = snapshot(pubDetail = "IP público 2804:14d:abcd:1234::1 · AS65000").toText(includePublicIp = false)
        assertFalse(text.contains("2804:14d"))
        assertTrue(text.contains("AS65000"))
    }

    @Test
    fun hideAddressesOnlyTouchesAddresses() {
        assertEquals("a [omitido] b [omitido] c", DiagnosticSnapshot.hideAddresses("a 8.8.8.8 b 2001:db8::1 c"))
        assertEquals("sem endereço", DiagnosticSnapshot.hideAddresses("sem endereço"))
    }

    @Test
    fun textHasNetworkStepsAndSpeed() {
        val text = snapshot().toText(false)
        assertTrue(text.contains("Rede: Wi-Fi"))
        assertTrue(text.contains("✔ Conexão com a rede: Wi-Fi conectado"))
        assertTrue(text.contains("▲ DNS: lento"))
        assertTrue(text.contains("Download 300 Mbps"))
    }

    @Test
    fun noSnapshotWithoutAnyResult() {
        assertNull(DiagnosticSnapshot.from(NetInfo.EMPTY, null, null, Date()))
        assertNotNull(DiagnosticSnapshot.from(NetInfo.EMPTY, null, SpeedResult(1.0, 1.0, 1.0, 1.0), Date()))
    }
}

// ---------------------------------------------------------------------------------------------
// Markdown das respostas
// ---------------------------------------------------------------------------------------------

class MarkdownLiteTest {
    private fun plain(md: String) = MarkdownLite.parse(md).joinToString("") { it.text }

    @Test
    fun boldCodeBulletsAndHeadingsLoseTheirMarkers() {
        val md = "# Título\nUm **negrito** e `codigo`.\n* item um\n- item dois\n1. numerado"
        assertEquals("Título\nUm negrito e codigo.\n• item um\n• item dois\n1. numerado", plain(md))
        val runs = MarkdownLite.parse(md)
        assertTrue(runs.any { it.bold && it.text.contains("negrito") })
        assertTrue(runs.any { it.mono && it.text == "codigo" })
        assertTrue(runs.first().bold) // o título
    }

    @Test
    fun fencedCodeIsMonospacedAndTheFencesDisappear() {
        val runs = MarkdownLite.parse("antes\n```\nping 8.8.8.8\n```\ndepois")
        assertEquals("antes\nping 8.8.8.8\ndepois", runs.joinToString("") { it.text })
        assertTrue(runs.any { it.mono && it.text.contains("ping 8.8.8.8") })
    }

    @Test
    fun singleAsterisksAndStrayMarkersDoNotBreakTheText() {
        assertEquals("texto itálico simples", plain("texto *itálico* simples"))
        assertEquals("5 * 3 sobra", plain("5 * 3 sobra"))
    }
}
