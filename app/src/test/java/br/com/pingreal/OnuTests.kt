package br.com.pingreal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val OK = PingOutcome(true, 5, 64, PingFailure.NONE)
private val TIMEOUT = PingOutcome(false, null, null, PingFailure.TIMED_OUT)
private val UNREACHABLE = PingOutcome(false, null, null, PingFailure.UNREACHABLE)

private fun waitUntil(timeoutMs: Long = 4000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

/** Ping falso: devolve o que o teste mandar, por host, e registra quantos rodam ao mesmo tempo. */
private class FakePinger(
    private val delayMs: Long = 0,
    private val answer: (String, Int) -> PingOutcome = { _, _ -> OK }
) : Pinger {
    val calls = ConcurrentHashMap<String, AtomicInteger>()
    val running = AtomicInteger(0)
    val maxRunning = AtomicInteger(0)

    override fun ping(host: String, timeoutMs: Int): PingOutcome {
        val now = running.incrementAndGet()
        maxRunning.updateAndGet { maxOf(it, now) }
        try {
            if (delayMs > 0) Thread.sleep(delayMs)
            val n = calls.computeIfAbsent(host) { AtomicInteger(0) }.incrementAndGet()
            return answer(host, n)
        } finally {
            running.decrementAndGet()
        }
    }

    override fun cancelAll() {}
}

class OnuStateMachineTest {
    @Test
    fun oneAndTwoFailuresAreUnstableWithoutEvent() {
        val m = OnuStateMachine()
        assertNull(m.apply(TIMEOUT))
        assertEquals(OnuState.INSTAVEL, m.state)
        assertNull(m.apply(TIMEOUT))
        assertEquals(OnuState.INSTAVEL, m.state)
    }

    @Test
    fun thirdFailureRaisesDropOnceAndFurtherFailuresDoNot() {
        val m = OnuStateMachine()
        m.apply(TIMEOUT)
        m.apply(TIMEOUT)
        assertEquals(OnuEventKind.QUEDA, m.apply(TIMEOUT))
        assertEquals(OnuState.SEM_RESPOSTA, m.state)
        assertNull(m.apply(TIMEOUT))
        assertNull(m.apply(TIMEOUT))
    }

    @Test
    fun successAfterDropRaisesRecovery() {
        val m = OnuStateMachine()
        repeat(3) { m.apply(TIMEOUT) }
        assertEquals(OnuEventKind.RECUPERACAO, m.apply(OK))
        assertEquals(OnuState.ONLINE, m.state)
        assertNull(m.apply(OK))
    }

    @Test
    fun successBetweenFailuresResetsTheCounter() {
        val m = OnuStateMachine()
        m.apply(TIMEOUT)
        m.apply(TIMEOUT)
        m.apply(OK)
        assertNull(m.apply(TIMEOUT))
        assertNull(m.apply(TIMEOUT))
        assertEquals(OnuState.INSTAVEL, m.state)
    }

    @Test
    fun nonTimeoutFailuresEndAsErrorButStillCountAsDrop() {
        val m = OnuStateMachine()
        m.apply(UNREACHABLE)
        m.apply(UNREACHABLE)
        assertEquals(OnuEventKind.QUEDA, m.apply(UNREACHABLE))
        assertEquals(OnuState.ERRO, m.state)
    }

    @Test
    fun machinesAreIndependent() {
        val a = OnuStateMachine()
        val b = OnuStateMachine()
        repeat(3) { a.apply(TIMEOUT) }
        assertEquals(OnuState.DESCONHECIDO, b.state)
    }
}

class OnuImporterTest {
    private fun parse(text: String, existing: List<Onu> = emptyList()) = OnuImporter.parse(text, "172.16", existing)

    @Test
    fun parsesQuadraLoteAndIdentification() {
        val r = parse("3,7,Casa 07\n3;8\n4\t1\tFulano")
        assertEquals(3, r.valid.size)
        assertEquals(Triple(3, 7, "Casa 07"), Triple(r.valid[0].quadra, r.valid[0].lote, r.valid[0].identificacao))
        assertNull(r.valid[1].identificacao)
        assertEquals("Fulano", r.valid[2].identificacao)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun parsesIpsWithThePrefix() {
        val r = parse("172.16.3.8,Casa 08\n172.16.3.9")
        assertEquals(listOf(3 to 8, 3 to 9), r.valid.map { it.quadra to it.lote })
        assertEquals("Casa 08", r.valid[0].identificacao)
    }

    @Test
    fun skipsTheHeaderLine() {
        val r = parse("quadra,lote,identificacao\n1,1,A")
        assertEquals(1, r.valid.size)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun reportsDuplicatesInvalidLinesAndOutOfRange() {
        val existing = listOf(Onu.create(1, 1))
        val r = parse("1,1\n1,2\n1,2\nabc\n300,1\n10.0.0.5\n172.16.1", existing)
        assertEquals(listOf(1 to 2), r.valid.map { it.quadra to it.lote })
        // duplicada com a existente, duplicada repetida, "abc", "300,1", IP fora do prefixo e "172.16.1"
        assertEquals(6, r.errors.size)
        assertTrue(r.errors.any { it.reason.contains("já cadastrado") })
        assertTrue(r.errors.any { it.reason.contains("entre 0 e 255") })
        assertTrue(r.errors.any { it.reason.contains("fora do prefixo") })
    }

    @Test
    fun toleratesBomAndWindowsLineEndings() {
        val r = parse("\uFEFF1,1\r\n1,2\r\n")
        assertEquals(2, r.valid.size)
    }
}

class MonitoringServiceTest {
    private fun options(concurrency: Int = 4) = MonitorOptions("172.16", 40, 1000, concurrency)

    private fun onus(n: Int) = (1..n).map { Onu.create(1, it) }

    @Test
    fun threeFailuresInARowMakeAnOnuUnreachableAndOtherOnusStayOnline() {
        val samples = Collections.synchronizedList(ArrayList<OnuSample>())
        val pinger = FakePinger { host, _ -> if (host.endsWith(".1.1")) TIMEOUT else OK }
        val service = MonitoringService(pinger) { samples.add(it) }
        service.options = options()
        val list = onus(3)
        service.start(list)

        assertTrue(waitUntil { samples.count { it.ip == "172.16.1.1" } >= 4 && samples.count { it.ip == "172.16.1.2" } >= 2 })
        service.stop()

        val first = samples.filter { it.ip == "172.16.1.1" }
        assertEquals(OnuState.INSTAVEL, first[0].snapshot.state)
        assertEquals(OnuState.SEM_RESPOSTA, first[2].snapshot.state)
        assertEquals(1, first.count { it.event == OnuEventKind.QUEDA })
        assertTrue(samples.filter { it.ip == "172.16.1.2" }.all { it.snapshot.state == OnuState.ONLINE })
    }

    @Test
    fun countsTxRxAndLatency() {
        val samples = Collections.synchronizedList(ArrayList<OnuSample>())
        val service = MonitoringService(FakePinger { _, n -> if (n % 2 == 0) TIMEOUT else OK }) { samples.add(it) }
        service.options = options()
        service.start(onus(1))
        assertTrue(waitUntil { samples.size >= 4 })
        service.stop()

        val s = samples[3].snapshot
        assertEquals(4, s.tx)
        assertEquals(2, s.rx)
        assertEquals(50.0, s.lossPercent, 0.001)
    }

    @Test
    fun neverRunsMorePingsAtOnceThanTheConcurrencyLimit() {
        val pinger = FakePinger(delayMs = 30)
        val samples = Collections.synchronizedList(ArrayList<OnuSample>())
        val service = MonitoringService(pinger) { samples.add(it) }
        service.options = options(concurrency = 3)
        service.start(onus(20))
        assertTrue(waitUntil { samples.size >= 20 })
        service.stop()
        assertTrue("máximo simultâneo = ${pinger.maxRunning.get()}", pinger.maxRunning.get() <= 3)
    }

    @Test
    fun stopHaltsEverythingAndStartingTwiceDoesNotDuplicateLoops() {
        val samples = Collections.synchronizedList(ArrayList<OnuSample>())
        val service = MonitoringService(FakePinger()) { samples.add(it) }
        service.options = options()
        val list = onus(2)
        service.start(list)
        service.start(list) // segunda chamada é ignorada
        assertTrue(service.isRunning)
        assertTrue(waitUntil { samples.size >= 6 })

        service.stop()
        assertFalse(service.isRunning)
        Thread.sleep(150)
        val frozen = samples.size
        Thread.sleep(250)
        assertEquals("não pode chegar amostra depois de parar", frozen, samples.size)

        // Cada ONU tem um único loop: no mesmo intervalo, ninguém tem o dobro do outro.
        val a = samples.count { it.ip == "172.16.1.1" }
        val b = samples.count { it.ip == "172.16.1.2" }
        assertTrue("a=$a b=$b", kotlin.math.abs(a - b) <= 2)
    }

    @Test
    fun stateSurvivesStopAndRestartButForgetClearsIt() {
        val samples = Collections.synchronizedList(ArrayList<OnuSample>())
        val service = MonitoringService(FakePinger { _, _ -> TIMEOUT }) { samples.add(it) }
        service.options = options()
        val list = onus(1)
        service.start(list)
        assertTrue(waitUntil { samples.size >= 3 })
        service.stop()
        val before = samples.size

        service.start(list)
        assertTrue(waitUntil { samples.size > before })
        service.stop()
        // continuou de onde parou: não repetiu a Queda (já estava sem resposta)
        assertEquals(1, samples.count { it.event == OnuEventKind.QUEDA })
        assertTrue(samples.last().snapshot.tx > before)

        service.forget(list[0].id)
        val count = samples.size
        service.start(list)
        assertTrue(waitUntil { samples.size > count })
        service.stop()
        assertEquals(1, samples[count].snapshot.tx)
    }

    @Test
    fun removeStopsOnlyThatOnu() {
        val samples = Collections.synchronizedList(ArrayList<OnuSample>())
        val service = MonitoringService(FakePinger()) { samples.add(it) }
        service.options = options()
        val list = onus(2)
        service.start(list)
        assertTrue(waitUntil { samples.size >= 4 })
        service.remove(list[0].id)
        Thread.sleep(150)
        val aBefore = samples.count { it.onu.id == list[0].id }
        Thread.sleep(250)
        assertEquals(aBefore, samples.count { it.onu.id == list[0].id })
        assertTrue(samples.count { it.onu.id == list[1].id } > aBefore - 1)
        service.stop()
    }
}

class QuadraPingJobTest {
    @Test
    fun pingsEveryHostTheRequestedNumberOfTimesAndSummarizes() {
        val lines = Collections.synchronizedList(ArrayList<PingLine>())
        val summaries = Collections.synchronizedList(ArrayList<HostSummary>())
        var result: List<HostSummary>? = null
        var wasCancelled = true
        val pinger = FakePinger { host, n -> if (host.endsWith(".2") && n % 2 == 0) TIMEOUT else OK }
        val hosts = listOf("172.16.1.1", "172.16.1.2", "172.16.1.3")

        QuadraPingJob(
            hosts, count = 4, intervalMs = 5, timeoutMs = 1000, concurrency = 8,
            onLine = { lines.add(it) },
            onSummary = { summaries.add(it) },
            onDone = { done, cancelled ->
                result = done
                wasCancelled = cancelled
            },
            pinger = pinger
        ).start()

        assertTrue(waitUntil { result != null })
        assertFalse(wasCancelled)
        assertEquals(12, lines.size)
        assertEquals(3, summaries.size)
        val byHost = result!!.associateBy { it.host }
        assertEquals(4, byHost.getValue("172.16.1.1").received)
        assertEquals(2, byHost.getValue("172.16.1.2").received)
        assertEquals(50.0, byHost.getValue("172.16.1.2").lossPercent, 0.001)
    }

    @Test
    fun respectsTheConcurrencyLimit() {
        val pinger = FakePinger(delayMs = 20)
        var result: List<HostSummary>? = null
        QuadraPingJob(
            (1..10).map { "172.16.2.$it" }, count = 2, intervalMs = 5, timeoutMs = 1000, concurrency = 3,
            onLine = {}, onSummary = {}, onDone = { done, _ -> result = done }, pinger = pinger
        ).start()
        assertTrue(waitUntil { result != null })
        assertEquals(10, result!!.size)
        assertTrue("máximo simultâneo = ${pinger.maxRunning.get()}", pinger.maxRunning.get() <= 3)
    }

    @Test
    fun cancelReturnsOnlyFinishedHostsAndDoesNotThrow() {
        var result: List<HostSummary>? = null
        var wasCancelled = false
        val job = QuadraPingJob(
            (1..6).map { "172.16.3.$it" }, count = 50, intervalMs = 40, timeoutMs = 1000, concurrency = 6,
            onLine = {}, onSummary = {},
            onDone = { done, cancelled ->
                result = done
                wasCancelled = cancelled
            },
            pinger = FakePinger()
        )
        job.start()
        Thread.sleep(150)
        job.cancel()

        assertTrue(waitUntil { result != null })
        assertTrue(wasCancelled)
        assertTrue("cancelado no meio não entrega resumo incompleto", result!!.isEmpty())
    }
}

class PingLineFormatterTest {
    @Test
    fun formatsTheThreeKindsOfLine() {
        assertTrue(PingLineFormatter.format(0, "172.16.1.1", OK).contains("PING 172.16.1.1 -- RESPOSTA -- 5 ms -- TTL=64"))
        assertTrue(PingLineFormatter.format(0, "172.16.1.1", TIMEOUT).endsWith("TEMPO ESGOTADO"))
        assertTrue(PingLineFormatter.format(0, "172.16.1.1", UNREACHABLE).endsWith("ERRO: Destino inacessível"))
    }
}
