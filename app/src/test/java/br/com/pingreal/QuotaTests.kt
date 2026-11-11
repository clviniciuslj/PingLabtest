package br.com.pingreal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaTest {
    private val client = GeminiClient(object : GeminiTransport {
        override fun call(method: String, url: String, apiKey: String, body: String?, timeoutMs: Int) = HttpReply(200, "{}")
    })

    private val daily = """
        {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
         "message":"You exceeded your current quota. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash",
         "details":[
           {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
             {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
              "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20"}]},
           {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"80123s"}
         ]}}
    """.trimIndent()

    private val perMinute = """
        {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded",
         "details":[
           {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
             {"quotaId":"GenerateContentInputTokensPerModelPerMinute-FreeTier","quotaValue":"250000"}]},
           {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"41s"}
         ]}}
    """.trimIndent()

    @Test
    fun readsWhichQuotaWasExceededAndHowLongToWait() {
        val f = client.failureFrom(429, daily)
        assertEquals(GeminiError.RATE_LIMIT, f.kind)
        assertEquals("GenerateRequestsPerDayPerProjectPerModel-FreeTier", f.quotaId)
        assertEquals("20", f.quotaValue)
        assertEquals(80123, f.retryAfterSeconds)
    }

    @Test
    fun dailyLimitIsExplainedAsDailyWithTheRealWaitAndTheGoogleMessage() {
        val text = GeminiClient.describe(client.failureFrom(429, daily), "gemini-3.8-flash")
        assertTrue(text.contains("DIÁRIO"))
        assertTrue(text.contains("22 h 15 min"))
        assertTrue(text.contains("limite: 20"))
        assertTrue(text.contains("outro modelo"))
        assertTrue(text.contains("Mensagem do Google: You exceeded your current quota"))
    }

    @Test
    fun perMinuteLimitIsExplainedAsPerMinute() {
        val f = client.failureFrom(429, perMinute)
        val text = GeminiClient.describe(f, "gemini-3.8-flash")
        assertEquals(41, f.retryAfterSeconds)
        assertTrue(text.contains("por minuto"))
        assertTrue(text.contains("41 s"))
    }

    @Test
    fun rateLimitWithoutDetailsStillShowsTheGoogleMessage() {
        val f = client.failureFrom(429, """{"error":{"message":"Resource has been exhausted (e.g. check quota)."}}""")
        assertNull(f.quotaId)
        assertTrue(GeminiClient.describe(f, "gemini-3.8-flash").contains("Resource has been exhausted"))
    }

    @Test
    fun waitIsWrittenInHumanUnits() {
        assertEquals("45 s", GeminiClient.formatWait(45))
        assertEquals("2 min 5 s", GeminiClient.formatWait(125))
        assertEquals("22 h 15 min", GeminiClient.formatWait(80123))
    }
}
