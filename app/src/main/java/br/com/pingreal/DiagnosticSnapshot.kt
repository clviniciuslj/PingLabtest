package br.com.pingreal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SnapshotStep(val title: String, val status: Status, val detail: String, val isPublicIp: Boolean)

/**
 * Uma foto do diagnóstico no momento em que o usuário abre o chat: é isto que vira texto para a IA.
 * O IP público só entra se [toText] for chamado com `includePublicIp = true`.
 */
class DiagnosticSnapshot(
    val atMillis: Long,
    val netType: String,
    val ip: String,
    val gateway: String,
    val dns: String,
    val steps: List<SnapshotStep>,
    val speed: SpeedResult?
) {
    fun toText(includePublicIp: Boolean): String {
        val sb = StringBuilder()
        sb.append("Rede: $netType · IP $ip\n")
        sb.append("Gateway $gateway · DNS $dns\n")
        val shown = steps.filter { it.status != Status.PENDING }
        if (shown.isNotEmpty()) {
            sb.append("\nETAPAS\n")
            for (s in shown) {
                sb.append("${glyph(s.status)} ${s.title}")
                val detail = if (s.isPublicIp && !includePublicIp) hideAddresses(s.detail) else s.detail
                if (detail.isNotBlank()) sb.append(": $detail")
                sb.append('\n')
            }
        }
        speed?.let {
            sb.append("\nVELOCIDADE\n")
            sb.append("Download ${formatMbps(it.downMbps)} Mbps · Upload ${formatMbps(it.upMbps)} Mbps\n")
            sb.append("Ping ${formatMs(it.pingMs)} ms · Jitter ${formatMs(it.jitterMs)} ms\n")
        }
        return sb.toString().trimEnd()
    }

    fun timeLabel(): String = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(atMillis))

    companion object {
        const val OMITTED = "[omitido]"

        private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        private val IPV6 = Regex("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:])")

        /** Troca qualquer endereço IPv4 ou IPv6 do texto por [OMITTED]. */
        fun hideAddresses(text: String): String = IPV6.replace(IPV4.replace(text, OMITTED), OMITTED)

        private fun glyph(s: Status) = when (s) {
            Status.PENDING -> "○"
            Status.RUNNING -> "◔"
            Status.OK -> "✔"
            Status.WARN -> "▲"
            Status.FAIL -> "✘"
            Status.SKIP -> "–"
        }

        /** Monta a foto a partir do que a tela de Diagnóstico guardou. Null se não há nada para enviar. */
        fun from(net: NetInfo?, report: DiagnosticReport?, speed: SpeedResult?, at: Date?): DiagnosticSnapshot? {
            if (report == null && speed == null) return null
            val n = net ?: NetInfo.EMPTY
            val steps = report?.steps.orEmpty().map { SnapshotStep(it.title, it.status, it.detail, it.id == "pub") }
            return DiagnosticSnapshot((at ?: Date()).time, n.type, n.ip, n.gateway, n.dns, steps, speed)
        }
    }
}
