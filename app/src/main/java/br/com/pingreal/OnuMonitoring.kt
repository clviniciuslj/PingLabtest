package br.com.pingreal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

private val daemonThreads = ThreadFactory { r -> Thread(r).apply { isDaemon = true } }

class MonitorOptions(
    val prefix: String = CondoDefaults.IP_PREFIX,
    val intervalMs: Long = CondoDefaults.INTERVAL_MS,
    val timeoutMs: Int = CondoDefaults.TIMEOUT_MS,
    val concurrency: Int = CondoDefaults.CONCURRENCY
)

/**
 * Um loop por ONU, com limite global de pings simultâneos (como o `MonitoringService` do PC).
 *
 * O estado de cada ONU (regra das 3 falhas e contadores) vive aqui, não no loop: parar/iniciar e
 * remover+adicionar a mesma ONU não repetem Queda nem perdem Recuperação. Use [forget] para descartar o
 * estado de uma ONU apagada ou que trocou de endereço.
 *
 * Os [onSample] chegam de threads de trabalho: quem recebe deve só enfileirar.
 */
class MonitoringService(
    private val pinger: Pinger,
    private val onSample: (OnuSample) -> Unit
) {
    var options = MonitorOptions()

    private val sync = Any()
    private val loops = HashMap<String, Loop>()
    private val runtimes = HashMap<String, Runtime>()
    private var scheduler: ScheduledThreadPoolExecutor? = null
    private var workers: ThreadPoolExecutor? = null
    private var running = false

    val isRunning: Boolean get() = synchronized(sync) { running }

    fun start(onus: List<Onu>) {
        synchronized(sync) {
            if (running) return
            running = true
            scheduler = ScheduledThreadPoolExecutor(1, daemonThreads)
            workers = Executors.newFixedThreadPool(max(1, options.concurrency), daemonThreads) as ThreadPoolExecutor
            // Partida escalonada: espalha os primeiros pings ao longo do intervalo.
            onus.forEachIndexed { index, onu ->
                startLoop(onu, if (onus.isEmpty()) 0L else options.intervalMs * index / onus.size)
            }
        }
    }

    fun add(onu: Onu) {
        synchronized(sync) {
            if (!running || loops.containsKey(onu.id)) return
            startLoop(onu, 0L)
        }
    }

    /** Para o loop da ONU. O estado é mantido; veja [forget]. */
    fun remove(onuId: String) {
        val loop = synchronized(sync) { loops.remove(onuId) } ?: return
        loop.cancel()
    }

    fun forget(onuId: String) {
        synchronized(sync) { runtimes.remove(onuId) }
    }

    fun stop() {
        val toCancel: List<Loop>
        val sched: ScheduledThreadPoolExecutor?
        val pool: ThreadPoolExecutor?
        synchronized(sync) {
            running = false
            toCancel = loops.values.toList()
            loops.clear()
            sched = scheduler
            pool = workers
            scheduler = null
            workers = null
        }
        toCancel.forEach { it.cancel() }
        sched?.shutdownNow()
        pool?.shutdownNow()
        pinger.cancelAll()
    }

    // ------------------------------------------------------------------ internos

    private fun startLoop(onu: Onu, delayMs: Long) {
        val runtime = runtimes.getOrPut(onu.id) { Runtime() }
        val loop = Loop(onu, onu.ip(options.prefix), runtime, options)
        loops[onu.id] = loop
        schedule(loop, delayMs)
    }

    private fun schedule(loop: Loop, delayMs: Long) {
        val sched = synchronized(sync) { scheduler } ?: return
        try {
            sched.schedule({ dispatch(loop) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun dispatch(loop: Loop) {
        if (loop.cancelled) return
        val pool = synchronized(sync) { workers } ?: return
        try {
            pool.execute { pingOnce(loop) }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun pingOnce(loop: Loop) {
        if (loop.cancelled) return
        val outcome = try {
            pinger.ping(loop.ip, loop.options.timeoutMs)
        } catch (_: Exception) {
            PingOutcome(false, null, null, PingFailure.ERROR)
        }
        if (loop.cancelled) return
        publish(loop, outcome)
        schedule(loop, loop.options.intervalMs)
    }

    private fun publish(loop: Loop, outcome: PingOutcome) {
        val at = System.currentTimeMillis()
        // O estado só muda sob o lock do loop e só se o loop não foi cancelado: um loop antigo ainda em
        // voo (remover/parar) nunca altera o estado que o loop novo da mesma ONU já está usando.
        synchronized(loop.gate) {
            if (loop.cancelled) return
            val rt = loop.runtime
            val kind = rt.machine.apply(outcome)
            rt.tx++
            if (outcome.success) {
                rt.rx++
                rt.lastResponse = at
            }
            val snapshot = OnuSnapshot(
                rt.machine.state, rt.tx, rt.rx,
                if (outcome.success) outcome.latencyMs else null, rt.lastResponse
            )
            onSample(OnuSample(loop.onu, loop.ip, outcome, snapshot, kind, at))
        }
    }

    private class Runtime {
        val machine = OnuStateMachine()
        var tx = 0
        var rx = 0
        var lastResponse: Long? = null
    }

    private class Loop(val onu: Onu, val ip: String, val runtime: Runtime, val options: MonitorOptions) {
        val gate = Any()

        @Volatile
        var cancelled = false
            private set

        fun cancel() {
            synchronized(gate) { cancelled = true }
        }
    }
}

/**
 * N pings por host, vários hosts em paralelo (limite de concorrência). Não toca no estado das ONUs: serve
 * para conferir um grupo agora (ex.: uma quadra). Roda em thread própria; [onLine], [onSummary] e [onDone]
 * chegam dela.
 */
class QuadraPingJob(
    private val hosts: List<String>,
    private val count: Int,
    private val intervalMs: Long,
    private val timeoutMs: Int,
    private val concurrency: Int,
    private val onLine: (PingLine) -> Unit,
    private val onSummary: (HostSummary) -> Unit,
    private val onDone: (summaries: List<HostSummary>, cancelled: Boolean) -> Unit,
    private val pinger: Pinger = OnuPinger()
) {
    @Volatile
    private var cancelled = false

    @Volatile
    private var pool: java.util.concurrent.ExecutorService? = null

    fun start() {
        Thread { onDone(runAll(), cancelled) }.apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        cancelled = true
        pool?.shutdownNow()
        pinger.cancelAll()
    }

    /** Devolve os resumos dos hosts concluídos. Cancelar devolve só os concluídos, sem lançar. */
    private fun runAll(): List<HostSummary> {
        if (hosts.isEmpty()) return emptyList()
        val summaries = arrayOfNulls<HostSummary>(hosts.size)
        val next = AtomicInteger(-1)
        val workerCount = min(max(1, concurrency), hosts.size)
        val executor = Executors.newFixedThreadPool(workerCount, daemonThreads)
        pool = executor
        val latch = CountDownLatch(workerCount)

        repeat(workerCount) {
            try {
                executor.execute {
                    try {
                        while (!cancelled) {
                            val i = next.incrementAndGet()
                            if (i >= hosts.size) return@execute
                            // Cancelado no meio do host: o resumo incompleto não é entregue.
                            val summary = pingHost(hosts[i]) ?: return@execute
                            summaries[i] = summary
                            onSummary(summary)
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (_: RejectedExecutionException) {
                latch.countDown()
            }
        }

        latch.await()
        executor.shutdownNow()
        return summaries.filterNotNull()
    }

    private fun pingHost(host: String): HostSummary? {
        var sent = 0
        var received = 0
        var latencySum = 0L
        for (n in 0 until count) {
            if (n > 0) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            if (cancelled) return null

            val outcome = try {
                pinger.ping(host, timeoutMs)
            } catch (_: Exception) {
                PingOutcome(false, null, null, PingFailure.ERROR)
            }
            if (cancelled) return null

            sent++
            if (outcome.success) {
                received++
                latencySum += outcome.latencyMs ?: 0L
            }
            onLine(PingLine(System.currentTimeMillis(), host, outcome))
        }
        return HostSummary(host, sent, received, if (received == 0) null else latencySum.toDouble() / received)
    }
}

/** Ping contínuo de um único destino, independente do monitoramento geral (ping individual de uma ONU). */
class PingLoop(
    private val host: String,
    private val intervalMs: Long,
    private val timeoutMs: Int,
    private val onLine: (PingLine) -> Unit
) {
    private class Run {
        @Volatile
        var cancelled = false
    }

    private val pinger = OnuPinger()
    private var run: Run? = null

    val isRunning: Boolean get() = run != null

    fun start() {
        if (run != null) return
        val current = Run()
        run = current
        Thread { loop(current) }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val current = run ?: return
        run = null
        current.cancelled = true
        pinger.cancelAll()
    }

    private fun loop(current: Run) {
        while (!current.cancelled) {
            val outcome = try {
                pinger.ping(host, timeoutMs)
            } catch (_: Exception) {
                PingOutcome(false, null, null, PingFailure.ERROR)
            }
            if (current.cancelled) return
            onLine(PingLine(System.currentTimeMillis(), host, outcome))
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}

/** Texto da linha de ping, igual ao do Monitor de ONUs do PC. */
object PingLineFormatter {
    fun format(at: Long, host: String, r: PingOutcome): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))
        val prefix = "[$time]  PING $host -- "
        if (r.success) return "${prefix}RESPOSTA -- ${latencyText(r.latencyMs)} -- TTL=${r.ttl ?: "?"}"
        return when (r.failure) {
            PingFailure.TIMED_OUT -> "${prefix}TEMPO ESGOTADO"
            PingFailure.UNREACHABLE -> "${prefix}ERRO: Destino inacessível"
            else -> "${prefix}ERRO: Falha no ping"
        }
    }

    fun latencyText(ms: Long?): String = when {
        ms == null -> "—"
        ms < 1 -> "<1 ms"
        else -> "$ms ms"
    }
}
