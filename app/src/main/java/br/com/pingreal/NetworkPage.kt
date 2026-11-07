package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.TreeMap

/**
 * Aba "Rede": lista os aparelhos da rede Wi-Fi atual, destaca os que parecem câmera e deixa pingar, abrir a
 * página, copiar IP/MAC, ver as portas abertas e dar apelido. Não bloqueia nem remove aparelhos: isso só o
 * roteador faz.
 */
class NetworkPage(
    private val activity: Activity,
    private val ui: UiKit,
    private val host: PageHost
) {
    val view: LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private val handler = Handler(Looper.getMainLooper())
    private val scanner = NetworkScanner(OnuPinger(), TcpPortProber())
    private val portScanner = NetworkScanner(OnuPinger(), TcpPortProber())

    private val devices = HashMap<String, Device>()
    private val names = DeviceNames.decode(host.prefs().getString(Prefs.NET_NAMES, null))

    private var scanning = false
    private var busyHeld = false
    private var idleGlobal = true
    private var closed = false
    private var scanToken = 0
    private var gatewayIp = ""

    private lateinit var netLine: TextView
    private lateinit var vpnLine: TextView
    private lateinit var scanButton: Button
    private lateinit var portInput: EditText
    private lateinit var statusLine: TextView
    private lateinit var macNote: TextView
    private lateinit var listBox: LinearLayout

    init {
        build()
        onNetworkChanged()
        updateControls()
    }

    // =====================================================================
    // API usada pela activity
    // =====================================================================

    fun onModeChanged(idle: Boolean) {
        idleGlobal = idle
        updateControls()
    }

    fun onNetworkChanged() {
        val info = host.netInfo()
        val usable = Subnet.parseIpv4(info.ip) != null
        netLine.text = if (usable) {
            val prefix = readLocalPrefix(activity, info.ip)
            "${info.type} · ${Subnet.label(info.ip, prefix)}\nEste celular ${info.ip} · gateway ${info.gateway}"
        } else {
            "${info.type}. Conecte-se a um Wi-Fi para ver os aparelhos da rede."
        }
        vpnLine.visibility = if (info.type.contains("VPN")) View.VISIBLE else View.GONE
    }

    fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(portInput.windowToken, 0)
        portInput.clearFocus()
    }

    fun shutdown() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        scanner.cancel()
        portScanner.cancel()
    }

    // =====================================================================
    // Interface
    // =====================================================================

    private fun build() {
        val card = ui.card()
        card.addView(ui.title("Rede local"))
        netLine = ui.medium("", 13f, ui.muted).apply { setLineSpacing(0f, 1.15f) }
        card.addView(netLine, ui.lp(-1, -2).apply { topMargin = ui.dp(4) })
        vpnLine = ui.medium("VPN ligada: ela pode responder pelos aparelhos e estragar a varredura. Desligue antes de escanear.", 12.5f, ui.orange).apply {
            visibility = View.GONE
        }
        card.addView(vpnLine, ui.lp(-1, -2).apply { topMargin = ui.dp(8) })
        view.addView(card, ui.lp(-1, -2))

        scanButton = ui.button("Escanear rede", ui.accent, ui.onAccent) { toggleScan() }
        view.addView(scanButton, ui.lp(-1, ui.dp(52)).apply { topMargin = ui.dp(12) })

        val portRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        portInput = EditText(activity).apply {
            setSingleLine(true)
            textSize = 14f
            typeface = Fonts.regular
            setTextColor(ui.white)
            setHintTextColor(ui.muted)
            hint = "IP para ver as portas abertas"
            background = ui.field()
            setPadding(ui.dp(14), 0, ui.dp(8), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
        }
        val portButton = ui.button("Portas", ui.softFill(ui.accent), ui.accentInk) {
            val target = parseHost(portInput.text.toString().trim())
            if (target == null) {
                Toast.makeText(activity, "Informe um IP ou nome válido.", Toast.LENGTH_SHORT).show()
            } else {
                hideKeyboard()
                showPortScan(target)
            }
        }
        portRow.addView(portInput, ui.lp(0, ui.dp(46), 1f))
        portRow.addView(portButton, ui.lp(ui.dp(92), ui.dp(46)).apply { marginStart = ui.dp(8) })
        view.addView(portRow, ui.lp(-1, -2).apply { topMargin = ui.dp(10) })

        statusLine = ui.medium("Toque em Escanear rede para ver quem está conectado.", 13f, ui.muted)
        view.addView(statusLine, ui.lp(-1, -2).apply { topMargin = ui.dp(14) })
        macNote = ui.medium(
            "O Android não liberou o endereço MAC, então o fabricante não aparece. Nome, portas abertas e tempo de resposta ainda ajudam a identificar.",
            12f, ui.amber
        ).apply { visibility = View.GONE }
        view.addView(macNote, ui.lp(-1, -2).apply { topMargin = ui.dp(4) })

        listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(listBox)
        }
        view.addView(scroll, ui.lp(-1, 0, 1f).apply { topMargin = ui.dp(8) })

        view.addView(
            ui.medium(
                "Só aparecem aparelhos ligados a esta rede Wi-Fi. Não achar uma câmera aqui não prova que ela não existe: ela pode estar em outra rede ou gravar só no cartão de memória.",
                11.5f, ui.muted
            ),
            ui.lp(-1, -2).apply { topMargin = ui.dp(8) }
        )
    }

    private fun updateControls() {
        if (scanning) {
            scanButton.text = "Parar"
            ui.danger(scanButton)
        } else {
            scanButton.text = "Escanear rede"
            ui.primary(scanButton)
        }
        ui.setEnabled(scanButton, scanning || idleGlobal || busyHeld)
    }

    // =====================================================================
    // Varredura
    // =====================================================================

    private fun toggleScan() {
        if (scanning) {
            scanToken++
            scanner.cancel()
            finishScan(cancelled = true)
        } else {
            startScan()
        }
    }

    private fun startScan() {
        val info = host.netInfo()
        val onLan = info.type.startsWith("Wi-Fi") || info.type.startsWith("Ethernet")
        if (!onLan || Subnet.parseIpv4(info.ip) == null) {
            Toast.makeText(activity, "Conecte-se a um Wi-Fi para varrer a rede.", Toast.LENGTH_LONG).show()
            return
        }
        val hosts = Subnet.hosts(info.ip, readLocalPrefix(activity, info.ip))
        if (hosts.isEmpty()) {
            Toast.makeText(activity, "Esta rede é pequena demais para varrer.", Toast.LENGTH_LONG).show()
            return
        }
        if (!busyHeld) {
            if (!host.acquire("Rede", ui.accentInk)) {
                Toast.makeText(activity, "Já existe um teste em andamento.", Toast.LENGTH_SHORT).show()
                return
            }
            busyHeld = true
        }

        gatewayIp = info.gateway
        devices.clear()
        macNote.visibility = View.GONE
        scanning = true
        rebuildList()
        statusLine.text = "Escaneando… 0 de ${hosts.size}"
        updateControls()
        onNetworkChanged()

        val token = ++scanToken
        scanner.scan(hosts, object : NetworkScanner.Listener {
            override fun onProgress(done: Int, total: Int) {
                host.post {
                    if (token == scanToken && scanning) statusLine.text = "Escaneando… $done de $total · ${summary()}"
                }
            }

            override fun onDevice(device: Device) {
                host.post {
                    if (token != scanToken) return@post
                    devices[device.ip] = device
                    rebuildList()
                }
            }

            override fun onFinished(cancelled: Boolean) {
                host.post { if (token == scanToken && scanning) finishScan(cancelled) }
            }
        })
    }

    private fun finishScan(cancelled: Boolean) {
        scanning = false
        if (busyHeld) {
            busyHeld = false
            host.release()
        }
        statusLine.text = when {
            devices.isEmpty() && !cancelled -> "Nenhum aparelho respondeu."
            cancelled -> "Varredura interrompida · ${summary()}"
            else -> summary()
        }
        macNote.visibility = if (devices.isNotEmpty() && devices.values.none { it.mac != null }) View.VISIBLE else View.GONE
        updateControls()
    }

    private fun summary(): String {
        val n = devices.size
        val cameras = devices.values.count { it.cameraReasons.isNotEmpty() }
        val base = if (n == 1) "1 aparelho" else "$n aparelhos"
        return when (cameras) {
            0 -> base
            1 -> "$base · 1 possível câmera"
            else -> "$base · $cameras possíveis câmeras"
        }
    }

    // =====================================================================
    // Lista
    // =====================================================================

    private fun nicknameOf(d: Device): String? = d.mac?.let { names[it] } ?: names[d.ip]

    private fun rebuildList() {
        listBox.removeAllViews()
        val sorted = devices.values.sortedWith(
            compareBy<Device>(
                { if (it.cameraReasons.isNotEmpty()) 0 else if (it.ip == gatewayIp) 1 else 2 },
                { (Subnet.parseIpv4(it.ip)?.toLong() ?: 0L) and 0xffffffffL }
            )
        )
        for (d in sorted) listBox.addView(deviceRow(d), ui.lp(-1, -2).apply { bottomMargin = ui.dp(8) })
    }

    private fun deviceRow(d: Device): View {
        val reasons = d.cameraReasons
        val nickname = nicknameOf(d)
        val isGateway = d.ip == gatewayIp
        val primary = nickname ?: (if (isGateway) "Roteador (gateway)" else null) ?: d.hostname ?: d.ip

        val second = ArrayList<String>()
        if (primary != d.ip) second.add(d.ip)
        if (nickname != null && isGateway) second.add("gateway")
        if (nickname != null && d.hostname != null) second.add(d.hostname)
        d.vendor?.let { second.add(it) }
        if (d.vendor == null && d.mac != null && Oui.isPrivate(d.mac)) second.add("MAC privado")
        d.pingMs?.let { second.add("$it ms") }

        val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        text.addView(ui.tv(primary, 16f, ui.white, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (second.isNotEmpty()) {
            text.addView(ui.medium(second.joinToString(" · "), 12.5f, ui.muted).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, ui.lp(-1, -2).apply { topMargin = ui.dp(1) })
        }
        if (d.openPorts.isNotEmpty()) {
            text.addView(
                ui.medium("Portas abertas: " + d.openPorts.joinToString(", ") { "$it ${Ports.name(it)}" }, 12.5f, ui.text),
                ui.lp(-1, -2).apply { topMargin = ui.dp(3) }
            )
        }
        if (reasons.isNotEmpty()) {
            text.addView(
                ui.medium("Possível câmera: " + reasons.joinToString("; "), 12.5f, ui.orange),
                ui.lp(-1, -2).apply { topMargin = ui.dp(3) }
            )
        }

        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (reasons.isNotEmpty()) ui.orange else ui.green)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rippled(ui.panel, 16f, if (reasons.isNotEmpty()) ui.tint(ui.orange, 150) else ui.panelStroke)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            isClickable = true
            setOnClickListener { showDeviceMenu(d) }
            addView(View(activity).apply { background = dot }, ui.lp(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(12) })
            addView(text, ui.lp(0, -2, 1f))
        }
    }

    // =====================================================================
    // Ações de um aparelho
    // =====================================================================

    private fun showDeviceMenu(d: Device) {
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, action: () -> Unit) {
            items.add(label)
            actions.add(action)
        }
        add("Pingar") {
            if (scanning) {
                Toast.makeText(activity, "Pare a varredura antes de pingar.", Toast.LENGTH_SHORT).show()
            } else {
                host.pingInTerminal(d.ip)
            }
        }
        add("Abrir no navegador") {
            val https = 443 in d.openPorts && 80 !in d.openPorts
            host.openUrl((if (https) "https://" else "http://") + d.ip)
        }
        add("Copiar IP") { host.copyText(d.ip) }
        d.mac?.let { mac -> add("Copiar MAC") { host.copyText(mac) } }
        add("Ver portas abertas") { showPortScan(d.ip) }
        add(if (nicknameOf(d) == null) "Dar apelido" else "Mudar apelido") { showNameDialog(d) }

        AlertDialog.Builder(activity)
            .setTitle(nicknameOf(d) ?: d.hostname ?: d.ip)
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showNameDialog(d: Device) {
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = "Ex.: ONU, TV da sala, impressora"
            setText(nicknameOf(d).orEmpty())
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = LinearLayout(activity).apply {
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
            addView(input, ui.lp(-1, -2))
        }
        AlertDialog.Builder(activity)
            .setTitle("Apelido de ${d.ip}")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val key = DeviceNames.key(d.ip, d.mac)
                val name = input.text.toString().trim()
                names.remove(d.ip)
                if (name.isEmpty()) names.remove(key) else names[key] = name
                host.prefs().edit().putString(Prefs.NET_NAMES, DeviceNames.encode(names)).apply()
                rebuildList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =====================================================================
    // Portas de um IP
    // =====================================================================

    private fun showPortScan(target: String) {
        val states = TreeMap<Int, PortState>()
        var finished = false
        val output = ui.tv("", 13.5f, ui.text).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply { addView(output) }

        fun render() {
            val open = states.filter { it.value == PortState.OPEN }.keys
            val closed = states.count { it.value == PortState.CLOSED }
            val silent = states.count { it.value == PortState.NO_RESPONSE }
            val sb = SpannableStringBuilder()
            fun line(text: String, color: Int) {
                val start = sb.length
                sb.append(text).append('\n')
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            line(
                if (finished) "Concluído: ${open.size} aberta(s) de ${Ports.ALL.size}"
                else "Testando… ${states.size} de ${Ports.ALL.size}",
                ui.muted
            )
            for (p in open) line("● $p ${Ports.name(p)} — ${Ports.INFO[p]?.second.orEmpty()}", ui.green)
            if (finished && open.isEmpty()) {
                line(
                    if (closed == 0) "Nenhuma porta respondeu. O aparelho pode estar desligado, em outra rede ou atrás de firewall."
                    else "Nenhuma porta aberta, mas o aparelho respondeu (portas fechadas: $closed).",
                    ui.amber
                )
            }
            if (states.isNotEmpty()) line("Fechadas: $closed · Sem resposta: $silent", ui.muted)
            output.text = sb
        }
        render()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Portas de $target")
            .setView(scroll)
            .setNegativeButton("Fechar", null)
            .create()
        dialog.setOnDismissListener { portScanner.cancel() }
        dialog.show()

        portScanner.scanPorts(
            target, Ports.ALL,
            onResult = { port, state -> host.post { if (dialog.isShowing) { states[port] = state; render() } } },
            onFinished = { cancelled -> host.post { if (!cancelled && dialog.isShowing) { finished = true; render() } } }
        )
    }
}
