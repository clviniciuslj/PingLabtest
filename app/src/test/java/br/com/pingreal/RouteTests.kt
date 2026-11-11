package br.com.pingreal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MobileRouteTest {
    @Test
    fun aWorkingDefaultNetworkIsAlwaysPreferredSoNoMobileDataIsSpent() {
        assertEquals(RouteChoice.DEFAULT, MobileRoute.choose(defaultHasInternet = true, mobileAcquired = false))
        assertEquals(RouteChoice.DEFAULT, MobileRoute.choose(defaultHasInternet = true, mobileAcquired = true))
    }

    @Test
    fun aBrokenWifiFallsBackToMobileData() {
        assertEquals(RouteChoice.MOBILE, MobileRoute.choose(defaultHasInternet = false, mobileAcquired = true))
    }

    @Test
    fun withNoWayOutItSaysSo() {
        assertEquals(RouteChoice.NO_INTERNET, MobileRoute.choose(defaultHasInternet = false, mobileAcquired = false))
    }

    @Test
    fun noWayOutBecomesAClearErrorAndTheRouteIsStillReleased() {
        var released = 0
        val transport = UrlConnectionTransport { Route(null, RouteChoice.NO_INTERNET) { released++ } }
        try {
            transport.call("POST", "https://example.invalid/x", "k", "{}", 1000)
            org.junit.Assert.fail("deveria lançar")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("dados móveis"))
        }
        assertEquals(1, released)
    }

    @Test
    fun theNoInternetMessageReachesTheUserAsANetworkError() {
        val client = GeminiClient(UrlConnectionTransport { Route(null, RouteChoice.NO_INTERNET) })
        val result = client.generate("k", "gemini-3.8-flash", "s", listOf(ChatTurn("user", "oi")))
        result as GeminiResult.Failure
        assertEquals(GeminiError.NETWORK, result.kind)
        assertTrue(GeminiClient.describe(result, "gemini-3.8-flash").contains("ligue os dados móveis"))
    }

    @Test
    fun theMobileFlagTravelsFromTheTransportToTheReply() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
        val viaMobile = object : GeminiTransport {
            override fun call(method: String, url: String, apiKey: String, body0: String?, timeoutMs: Int) = HttpReply(200, body, viaMobile = true)
        }
        val plain = object : GeminiTransport {
            override fun call(method: String, url: String, apiKey: String, body0: String?, timeoutMs: Int) = HttpReply(200, body)
        }
        val turns = listOf(ChatTurn("user", "oi"))
        assertTrue((GeminiClient(viaMobile).generate("k", "gemini-3.8-flash", "s", turns) as GeminiResult.Reply).viaMobile)
        assertFalse((GeminiClient(plain).generate("k", "gemini-3.8-flash", "s", turns) as GeminiResult.Reply).viaMobile)
    }
}
