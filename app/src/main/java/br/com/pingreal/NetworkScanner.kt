package br.com.pingreal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

// =========================================================================
// Sub-rede
// =========================================================================

object Subnet {
    /** Menor máscara varrida (/22 = 1022 endereços). Redes maiores são varridas só no /24 do celular. */
    const val MIN_PREFIX = 22
    const val FALLBACK_PREFIX = 24

    fun parseIpv4(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255 || p.length > 3) return null
            value = (value shl 8) or n
        }
        return value
    }

    fun format(value: Int): String =
        "${(value ushr 24) and 0xff}.${(value ushr 16) and 0xff}.${(value ushr 8) and 0xff}.${value and 0xff}"

    /** A máscara realmente varrida: a da rede, se couber, senão /24. Fora de 8..30 não há o que varrer. */
    fun effectivePrefix(prefix: Int?): Int = when {
        prefix == null -> FALLBACK_PREFIX
        prefix > 30 -> prefix
        prefix < MIN_PREFIX -> FALLBACK_PREFIX
        else -> prefix
    }

    /** Todos os endereços da sub-rede (sem rede, sem broadcast e sem o do próprio celular), em ordem. */
    fun hosts(ip: String, prefix: Int?): List<String> {
        val own = parseIpv4(ip) ?: return emptyList()
        val p = effectivePrefix(prefix)
        if (p > 30) return emptyList()
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        val network = own and mask
        val broadcast = network or mask.inv()
        val out = ArrayList<String>()
        // Long para não estourar nos extremos do espaço de 32 bits.
        var cur = (network.toLong() and 0xffffffffL) + 1
        val last = (broadcast.toLong() and 0xffffffffL) - 1
        while (cur <= last) {
            val v = cur.toInt()
            if (v != own) out.add(format(v))
            cur++
        }
        return out
    }

    fun label(ip: String, prefix: Int?): String {
        val own = parseIpv4(ip) ?: return ip
        val p = effectivePrefix(prefix)
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        return "${format(own and mask)}/$p"
    }
}

/** Máscara (nº de bits) da rede física atual, a partir do IP do celular. Null se não achar. */
fun readLocalPrefix(context: Context, ip: String): Int? = try {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    var found: Int? = null
    for (n in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(n) ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
        val match = cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address.hostAddress == ip }
        if (match != null) {
            found = match.prefixLength
            break
        }
    }
    found
} catch (_: Exception) {
    null
}

// =========================================================================
// Portas
// =========================================================================

enum class PortState { OPEN, CLOSED, NO_RESPONSE }

/** Uma tentativa de conexão TCP. Interface para que os testes usem uma rede falsa. */
interface PortProber {
    fun probe(host: String, port: Int, timeoutMs: Int): PortState
}

class TcpPortProber : PortProber {
    override fun probe(host: String, port: Int, timeoutMs: Int): PortState {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            PortState.OPEN
        } catch (e: ConnectException) {
            // "Connection refused": o aparelho existe e respondeu que a porta está fechada.
            if (e.message.orEmpty().contains("refused", ignoreCase = true)) PortState.CLOSED else PortState.NO_RESPONSE
        } catch (_: Exception) {
            PortState.NO_RESPONSE
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}

object Ports {
    /** Porta -> (nome curto, o que significa). */
    val INFO: Map<Int, Pair<String, String>> = linkedMapOf(
        21 to ("FTP" to "transferência de arquivos"),
        22 to ("SSH" to "acesso remoto por terminal"),
        23 to ("Telnet" to "acesso remoto sem criptografia"),
        53 to ("DNS" to "servidor de nomes"),
        80 to ("HTTP" to "página de configuração"),
        139 to ("NetBIOS" to "compartilhamento Windows"),
        443 to ("HTTPS" to "página de configuração segura"),
        445 to ("SMB" to "compartilhamento Windows"),
        554 to ("RTSP" to "vídeo de câmera"),
        3389 to ("RDP" to "área de trabalho remota"),
        5000 to ("UPnP/web" to "página de serviço"),
        7547 to ("TR-069" to "gerência remota da operadora"),
        8000 to ("HTTP-8000" to "página web; comum em câmeras Hikvision"),
        8080 to ("HTTP-alt" to "página de configuração"),
        8081 to ("HTTP-8081" to "página web"),
        8443 to ("HTTPS-alt" to "página de configuração segura"),
        8554 to ("RTSP-alt" to "vídeo de câmera"),
        8888 to ("HTTP-8888" to "página web"),
        34567 to ("DVR" to "porta de DVR e câmeras genéricas"),
        37777 to ("Dahua" to "protocolo de câmeras e DVR Dahua")
    )

    /** Portas testadas em cada aparelho da varredura da rede (poucas, para ser rápido). */
    val QUICK: List<Int> = listOf(22, 23, 53, 80, 443, 554, 7547, 8000, 8080, 8443, 8554, 34567, 37777)

    /** Portas testadas na verificação de um IP só. */
    val ALL: List<Int> = INFO.keys.sorted()

    fun name(port: Int): String = INFO[port]?.first ?: "porta $port"
}

// =========================================================================
// Pistas de câmera
// =========================================================================

object CameraHints {
    private val STRONG_PORTS = mapOf(
        554 to "porta 554 (vídeo RTSP) aberta",
        8554 to "porta 8554 (vídeo RTSP) aberta",
        37777 to "porta 37777 (protocolo Dahua) aberta",
        34567 to "porta 34567 (DVR/câmera) aberta",
        8000 to "porta 8000 (comum em câmeras Hikvision) aberta"
    )
    private val CAMERA_VENDORS = setOf("Hikvision", "Dahua")
    private val IOT_CHIP_VENDORS = setOf("Espressif")

    /** Motivos para achar que o aparelho pode ser uma câmera. Vazio = nenhuma pista. */
    fun reasons(openPorts: List<Int>, vendor: String?): List<String> {
        val out = ArrayList<String>()
        if (vendor != null && vendor in CAMERA_VENDORS) out.add("fabricante $vendor, de câmeras")
        if (vendor != null && vendor in IOT_CHIP_VENDORS) out.add("chip $vendor, comum em câmeras baratas e aparelhos inteligentes")
        for (port in openPorts) STRONG_PORTS[port]?.let { out.add(it) }
        return out
    }
}

// =========================================================================
// MAC e fabricante
// =========================================================================

object Oui {
    // Tabela parcial: só prefixos conhecidos de câmeras e de equipamentos de rede comuns.
    private val VENDORS: Map<String, String> = buildMap {
        fun add(vendor: String, vararg prefixes: String) = prefixes.forEach { put(it, vendor) }
        add("Hikvision", "44:19:B6", "BC:AD:28", "C0:56:E3", "28:57:BE", "4C:BD:8F")
        add("Dahua", "3C:EF:8C", "4C:11:BF", "90:02:A9", "E0:50:8B")
        add("Espressif", "24:0A:C4", "30:AE:A4", "84:0D:8E", "A4:CF:12", "24:6F:28", "7C:9E:BD", "3C:71:BF", "5C:CF:7F", "18:FE:34", "CC:50:E3", "84:F3:EB", "60:01:94", "68:C6:3A", "EC:FA:BC", "B4:E6:2D")
        add("TP-Link", "50:C7:BF", "14:CC:20", "98:DA:C4", "F4:F2:6D", "EC:08:6B", "10:FE:ED", "60:E3:27", "A0:F3:C1", "30:B5:C2")
        add("Ubiquiti", "44:D9:E7", "78:8A:20", "F0:9F:C2", "FC:EC:DA", "74:AC:B9", "80:2A:A8")
        add("MikroTik", "4C:5E:0C", "64:D1:54", "E4:8D:8C", "2C:C8:1B", "B8:69:F4", "CC:2D:E0", "D4:CA:6D", "48:8F:5A", "74:4D:28")
    }

    fun normalize(mac: String): String? {
        val parts = mac.trim().uppercase().split(':', '-')
        if (parts.size != 6 || parts.any { it.length != 2 || it.any { c -> c !in "0123456789ABCDEF" } }) return null
        return parts.joinToString(":")
    }

    fun vendor(mac: String): String? = normalize(mac)?.let { VENDORS[it.substring(0, 8)] }

    /** MAC "privado" (aleatório): o bit de endereço administrado localmente está ligado. Celulares fazem isso. */
    fun isPrivate(mac: String): Boolean {
        val first = normalize(mac)?.substring(0, 2)?.toInt(16) ?: return false
        return first and 0x02 != 0
    }
}

object ArpTable {
    /** Texto de /proc/net/arp -> IP para MAC. */
    fun parseProcArp(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.lineSequence().drop(1)) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 4) continue
            val flags = cols[2]
            val mac = Oui.normalize(cols[3]) ?: continue
            if (flags == "0x0" || mac == "00:00:00:00:00:00") continue
            out[cols[0]] = mac
        }
        return out
    }

    /** Saída de `ip neigh show` -> IP para MAC. */
    fun parseIpNeigh(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val cols = line.trim().split(Regex("\\s+"))
            val at = cols.indexOf("lladdr")
            if (cols.isEmpty() || at < 0 || at + 1 >= cols.size) continue
            val mac = Oui.normalize(cols[at + 1]) ?: continue
            if (cols.last() == "FAILED") continue
            out[cols[0]] = mac
        }
        return out
    }

    /** Tenta ler a tabela ARP. O Android recente bloqueia isso para apps comuns: aí devolve vazio. */
    fun read(): Map<String, String> {
        try {
            val file = java.io.File("/proc/net/arp")
            if (file.canRead()) {
                val found = parseProcArp(file.readText())
                if (found.isNotEmpty()) return found
            }
        } catch (_: Exception) {
        }
        return try {
            val process = ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.destroy()
            parseIpNeigh(text)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

object ReverseDns {
    /** Nome do aparelho na rede (o roteador costuma dar um). Null se não houver. */
    fun lookup(ip: String): String? = try {
        InetAddress.getByName(ip).canonicalHostName.takeIf { it.isNotBlank() && it != ip }
    } catch (_: Exception) {
        null
    }
}

// =========================================================================
// Apelidos (guardados por MAC, ou pelo IP quando o MAC não é conhecido)
// =========================================================================

object DeviceNames {
    fun key(ip: String, mac: String?): String = mac ?: ip

    fun encode(names: Map<String, String>): String =
        names.entries.joinToString("\n") { "${it.key}\t${it.value.replace('\t', ' ').replace('\n', ' ')}" }

    fun decode(text: String?): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.orEmpty().lineSequence()) {
            val at = line.indexOf('\t')
            if (at <= 0) continue
            val name = line.substring(at + 1).trim()
            if (name.isNotEmpty()) out[line.substring(0, at)] = name
        }
        return out
    }
}

// =========================================================================
// Varredura
// =========================================================================

data class Device(
    val ip: String,
    val pingMs: Long?,
    val openPorts: List<Int>,
    val hostname: String?,
    val mac: String? = null
) {
    val vendor: String? get() = mac?.let { Oui.vendor(it) }
    val cameraReasons: List<String> get() = CameraHints.reasons(openPorts, vendor)
}

class NetworkScanner(
    private val pinger: Pinger,
    private val prober: PortProber,
    private val arp: () -> Map<String, String> = { ArpTable.read() },
    private val resolveName: (String) -> String? = { ReverseDns.lookup(it) }
) {
    companion object {
        const val HOST_CONCURRENCY = 20
        const val PROBE_THREADS = 120
        const val PING_TIMEOUT_MS = 1000
        const val QUICK_PROBE_TIMEOUT_MS = 600
        const val MANUAL_PROBE_TIMEOUT_MS = 1500
        const val NAME_TIMEOUT_MS = 1200L
    }

    interface Listener {
        /** Chamado de threads de trabalho. */
        fun onProgress(done: Int, total: Int)

        /** Um aparelho achado (de novo, já com MAC, quando a tabela ARP responde). */
        fun onDevice(device: Device)

        fun onFinished(cancelled: Boolean)
    }

    /** Cada varredura tem um número; cancelar (ou começar outra) invalida o das anteriores. */
    private val generation = AtomicInteger(0)
    @Volatile private var pools: List<ExecutorService> = emptyList()

    /** Varre [hosts] sem bloquear: avisa o [listener] de cada aparelho achado. */
    fun scan(hosts: List<String>, listener: Listener) {
        cancel()
        val mine = generation.incrementAndGet()
        fun stale() = generation.get() != mine
        val workers = Executors.newFixedThreadPool(max(1, min(HOST_CONCURRENCY, hosts.size)))
        val probes = Executors.newFixedThreadPool(PROBE_THREADS)
        pools = listOf(workers, probes)
        val done = AtomicInteger(0)
        val found = java.util.concurrent.ConcurrentHashMap<String, Device>()
        val total = hosts.size

        for (ip in hosts) {
            try {
                workers.execute {
                    val device = try {
                        scanHost(ip, probes, ::stale)
                    } catch (_: Exception) {
                        null
                    }
                    if (device != null && !stale()) {
                        found[ip] = device
                        listener.onDevice(device)
                    }
                    val n = done.incrementAndGet()
                    if (n % 4 == 0 || n == total) listener.onProgress(n, total)
                }
            } catch (_: Exception) {
                break
            }
        }
        workers.shutdown()

        Thread({
            try {
                workers.awaitTermination(10, TimeUnit.MINUTES)
            } catch (_: InterruptedException) {
            }
            if (!stale() && found.isNotEmpty()) {
                val table = try {
                    arp()
                } catch (_: Exception) {
                    emptyMap()
                }
                for ((ip, device) in found) {
                    val mac = table[ip] ?: continue
                    listener.onDevice(device.copy(mac = mac))
                }
            }
            probes.shutdownNow()
            listener.onFinished(stale())
        }, "net-scan").start()
    }

    private fun scanHost(ip: String, probes: ExecutorService, stale: () -> Boolean): Device? {
        if (stale()) return null
        val futures = Ports.QUICK.associateWith { port ->
            probes.submit<PortState> { prober.probe(ip, port, QUICK_PROBE_TIMEOUT_MS) }
        }
        val ping = pinger.ping(ip, PING_TIMEOUT_MS)
        val states = futures.mapValues { (_, f) ->
            try {
                f.get()
            } catch (_: Exception) {
                PortState.NO_RESPONSE
            }
        }
        if (stale()) return null
        // Recusar a conexão também prova que o aparelho existe (muitos ignoram ping).
        val alive = ping.success || states.values.any { it != PortState.NO_RESPONSE }
        if (!alive) return null
        val open = states.filter { it.value == PortState.OPEN }.keys.sorted()
        val nameFuture = probes.submit<String?> { resolveName(ip) }
        val name = try {
            nameFuture.get(NAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            nameFuture.cancel(true)
            null
        }
        return Device(ip, if (ping.success) ping.latencyMs else null, open, name)
    }

    /** Testa as [ports] de um IP, em paralelo, e avisa cada resultado. [onFinished] recebe se foi cancelado. */
    fun scanPorts(
        host: String,
        ports: List<Int>,
        onResult: (Int, PortState) -> Unit,
        onFinished: (Boolean) -> Unit
    ) {
        cancel()
        val mine = generation.incrementAndGet()
        val pool = Executors.newFixedThreadPool(max(1, min(ports.size, 32)))
        pools = listOf(pool)
        val left = CountDownLatch(ports.size)
        for (port in ports) {
            try {
                pool.execute {
                    try {
                        val state = prober.probe(host, port, MANUAL_PROBE_TIMEOUT_MS)
                        if (generation.get() == mine) onResult(port, state)
                    } finally {
                        left.countDown()
                    }
                }
            } catch (_: Exception) {
                left.countDown()
            }
        }
        pool.shutdown()
        Thread({
            try {
                left.await(2, TimeUnit.MINUTES)
            } catch (_: InterruptedException) {
            }
            onFinished(generation.get() != mine)
        }, "net-ports").start()
    }

    fun cancel() {
        generation.incrementAndGet()
        pinger.cancelAll()
        pools.forEach { it.shutdownNow() }
        pools = emptyList()
    }
}
