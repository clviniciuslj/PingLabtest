package br.com.pingreal

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** Um ping por vez. Existe como interface para que os testes usem um ping falso, sem rede. */
interface Pinger {
    fun ping(host: String, timeoutMs: Int): PingOutcome

    /** Encerra os pings em andamento deste pinger (as chamadas a [ping] terminam logo em seguida). */
    fun cancelAll()
}

/**
 * Um ping por vez, pelo `/system/bin/ping` do Android (o mesmo usado no resto do app). Nunca lança por
 * timeout ou host inacessível: devolve um [PingOutcome] com o tipo de falha.
 *
 * Cada funcionalidade usa a sua própria instância, para que [cancelAll] só encerre os pings dela.
 */
class OnuPinger : Pinger {
    private val processes = CopyOnWriteArrayList<Process>()

    override fun ping(host: String, timeoutMs: Int): PingOutcome {
        if (host.isBlank()) return PingOutcome(false, null, null, PingFailure.ERROR)
        val seconds = max(1, ceil(timeoutMs / 1000.0).toInt())

        val process = try {
            ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", seconds.toString(), "-s", "32", host)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return PingOutcome(false, null, null, PingFailure.ERROR)
        }
        processes.add(process)

        val lines = try {
            process.inputStream.bufferedReader().use { it.readLines() }
        } catch (_: Exception) {
            emptyList()
        } finally {
            processes.remove(process)
            process.destroy()
        }

        for (line in lines) {
            val reply = PingSession.parseReply(line) ?: continue
            return PingOutcome(true, reply.timeMs.roundToLong(), reply.ttl.toIntOrNull(), PingFailure.NONE)
        }

        val text = lines.joinToString("\n").lowercase()
        val failure = when {
            "unreachable" in text || "unknown host" in text || "bad address" in text ||
                "name or service" in text -> PingFailure.UNREACHABLE
            "not permitted" in text || "sendmsg" in text -> PingFailure.ERROR
            else -> PingFailure.TIMED_OUT
        }
        return PingOutcome(false, null, null, failure)
    }

    override fun cancelAll() {
        processes.forEach { it.destroy() }
    }
}
