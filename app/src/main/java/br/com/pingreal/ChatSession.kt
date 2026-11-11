package br.com.pingreal

enum class Role { USER, MODEL }

/** [snapshotAt] != null marca uma mensagem que carrega um diagnóstico (ela nunca é cortada do histórico). */
class ChatMessage(val role: Role, val text: String, val snapshotAt: Long? = null, val snapshotLabel: String? = null)

object ChatPrompts {
    /** A instrução de sistema: um assistente técnico de redes, não preso ao diagnóstico inicial. */
    const val SYSTEM = """Você é um assistente técnico de redes para um técnico de suporte de provedor de internet por fibra óptica. Os assuntos do dia a dia dele: ONUs e GPON, roteadores e Wi-Fi, MikroTik, DNS, DHCP, PPPoE, ping, traceroute, latência, jitter e perda de pacotes, e procedimentos de campo.

Responda sempre em português do Brasil, de forma objetiva e prática. Quando receber um diagnóstico do app PingLab, use-o como contexto inicial: interprete cada etapa, levante hipóteses da mais para a menos provável, diga o que cada teste indicaria e sugira o próximo passo de verificação. A conversa não se limita a esse diagnóstico: responda livremente a qualquer pergunta técnica relacionada a redes, fibra, equipamentos e procedimentos de suporte.

Não invente medições nem dados que não estejam na conversa. Quando faltar informação, pergunte ou diga o que seria preciso medir. Seja honesto sobre incertezas. Lembre que o PingLab mede a partir de um celular, usando o ping do Android, e que VPN ou bloqueador de anúncios no celular pode falsear ping e traceroute."""

    fun firstSnapshot(body: String) =
        "Analise este diagnóstico de rede do PingLab e me diga o que ele indica, quais as causas mais prováveis e o que testar em seguida.\n\n$body"

    fun nextSnapshot(body: String) =
        "Rodei outro diagnóstico. Compare com o anterior, se fizer sentido, e me diga o que mudou.\n\n$body"
}

/** O histórico da conversa. A API não guarda nada: a cada pergunta o histórico inteiro é reenviado. */
class ChatSession {
    private val list = ArrayList<ChatMessage>()

    val messages: List<ChatMessage> get() = list
    val isEmpty: Boolean get() = list.isEmpty()
    val snapshotCount: Int get() = list.count { it.snapshotAt != null }

    fun clear() = list.clear()

    fun addUser(text: String): ChatMessage = ChatMessage(Role.USER, text).also { list.add(it) }

    fun addModel(text: String): ChatMessage = ChatMessage(Role.MODEL, text).also { list.add(it) }

    fun hasSnapshot(atMillis: Long) = list.any { it.snapshotAt == atMillis }

    /** Acrescenta um diagnóstico: o primeiro pede uma análise, os seguintes pedem uma comparação. */
    fun addSnapshot(snapshot: DiagnosticSnapshot, includePublicIp: Boolean): ChatMessage {
        val body = snapshot.toText(includePublicIp)
        val first = snapshotCount == 0
        val number = snapshotCount + 1
        val text = if (first) ChatPrompts.firstSnapshot(body) else ChatPrompts.nextSnapshot(body)
        val label = if (first) "Diagnóstico das ${snapshot.timeLabel()}" else "Diagnóstico $number das ${snapshot.timeLabel()}"
        return ChatMessage(Role.USER, text, snapshot.atMillis, label).also { list.add(it) }
    }

    /**
     * O histórico para enviar. Passando de [maxChars], descarta as mensagens mais antigas, mas nunca os diagnósticos
     * nem a última mensagem. Junta falas seguidas do mesmo papel e garante que começa por uma fala do usuário.
     */
    fun turns(maxChars: Int = MAX_CONTEXT_CHARS): List<ChatTurn> {
        val work = ArrayList(list)
        var total = work.sumOf { it.text.length }
        while (total > maxChars) {
            val index = work.indices.firstOrNull { it < work.lastIndex && work[it].snapshotAt == null } ?: break
            total -= work[index].text.length
            work.removeAt(index)
        }
        val out = ArrayList<ChatTurn>()
        for (m in work) {
            val role = if (m.role == Role.USER) "user" else "model"
            val last = out.lastOrNull()
            if (last != null && last.role == role) {
                out[out.lastIndex] = ChatTurn(role, last.text + "\n\n" + m.text)
            } else {
                out.add(ChatTurn(role, m.text))
            }
        }
        while (out.isNotEmpty() && out.first().role != "user") out.removeAt(0)
        return out
    }

    companion object {
        /** Folga larga para a janela de contexto do modelo; só evita mandar conversas enormes a cada pergunta. */
        const val MAX_CONTEXT_CHARS = 80_000
    }
}
