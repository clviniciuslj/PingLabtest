package br.com.pingreal

import java.util.UUID

/** Valores padrão do monitoramento (os mesmos do Monitor de ONUs do PC, exceto a concorrência). */
object CondoDefaults {
    const val IP_PREFIX = "172.16"
    const val INTERVAL_MS = 3000L
    const val TIMEOUT_MS = 1000

    /** O PC usa 50; no Android cada ping é um processo e o sistema encerra apps com processos demais. */
    const val CONCURRENCY = 16

    /** "Pingar quadra": 8 pings por ONU, 1 por segundo. */
    const val QUADRA_PING_COUNT = 8
    const val QUADRA_PING_INTERVAL_MS = 1000L

    /** Ping individual: 1 por segundo, como o ping.exe. */
    const val TERMINAL_INTERVAL_MS = 1000L
}

/** Uma ONU: a tupla (quadra, lote) é única e o IP é `prefixo.quadra.lote`. */
data class Onu(val id: String, val quadra: Int, val lote: Int, val identificacao: String?) {
    fun ip(prefix: String) = "$prefix.$quadra.$lote"

    companion object {
        fun create(quadra: Int, lote: Int, identificacao: String? = null) =
            Onu(UUID.randomUUID().toString(), quadra, lote, identificacao?.trim()?.takeIf { it.isNotEmpty() })
    }
}

enum class OnuState { DESCONHECIDO, ONLINE, INSTAVEL, SEM_RESPOSTA, ERRO }

enum class PingFailure { NONE, TIMED_OUT, UNREACHABLE, ERROR }

class PingOutcome(val success: Boolean, val latencyMs: Long?, val ttl: Int?, val failure: PingFailure)

enum class OnuEventKind { QUEDA, RECUPERACAO }

/** Regra das 3 falhas consecutivas para uma única ONU. */
class OnuStateMachine {
    companion object {
        const val FAILURE_THRESHOLD = 3
    }

    var state = OnuState.DESCONHECIDO
        private set
    var consecutiveFailures = 0
        private set

    fun apply(result: PingOutcome): OnuEventKind? {
        if (result.success) {
            val wasDown = state == OnuState.SEM_RESPOSTA || state == OnuState.ERRO
            consecutiveFailures = 0
            state = OnuState.ONLINE
            return if (wasDown) OnuEventKind.RECUPERACAO else null
        }

        consecutiveFailures++
        if (consecutiveFailures < FAILURE_THRESHOLD) {
            state = OnuState.INSTAVEL
            return null
        }

        state = if (result.failure == PingFailure.TIMED_OUT) OnuState.SEM_RESPOSTA else OnuState.ERRO
        return if (consecutiveFailures == FAILURE_THRESHOLD) OnuEventKind.QUEDA else null
    }
}

class OnuSnapshot(val state: OnuState, val tx: Int, val rx: Int, val latencyMs: Long?, val lastResponseAt: Long?) {
    val lossPercent: Double get() = if (tx == 0) 0.0 else (tx - rx) * 100.0 / tx
}

class OnuSample(
    val onu: Onu,
    val ip: String,
    val outcome: PingOutcome,
    val snapshot: OnuSnapshot,
    val event: OnuEventKind?,
    val at: Long
)

class PingLine(val at: Long, val host: String, val outcome: PingOutcome)

class HostSummary(val host: String, val sent: Int, val received: Int, val averageMs: Double?) {
    val lossPercent: Double get() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent
}
