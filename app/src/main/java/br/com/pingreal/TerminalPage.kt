package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Aba "Terminal": ping (um ou vários alvos), traceroute, gráfico, console e rede atual. */
class TerminalPage(
    private val activity: Activity,
    private val ui: UiKit,
    private val host: PageHost
) {
    private enum class Run { IDLE, PING, TRACE }

    private class Target(val host: String, val continuous: Boolean, val count: Int, val size: Int)

    private class RowViews(val cur: TextView, val avg: TextView, val loss: TextView)

    companion object {
        private const val MAX_TARGETS = 4
        private const val MAX_HOPS = 30
        private const val PROBES_PER_HOP = 3
        private const val MAX_CONSOLE_LINES = 600
        private const val TRIM_LINES = 100
        private const val MAX_HISTORY = 12
        private const val DEFAULT_SIZE = 32
        private const val DASH = NetInfo.NONE
        private const val RULE = "────────────────────────────"
        private val WHITESPACE = Regex("\\s+")
    }

    val view: LinearLayout

    private lateinit var hostInput: EditText
    private lateinit var favButton: IconView
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chipsRow: LinearLayout
    private lateinit var runButton: Button
    private lateinit var traceButton: Button
    private lateinit var chart: LatencyChartView
    private lateinit var chartSubtitle: TextView
    private lateinit var latencyNow: TextView
    private lateinit var singleStats: LinearLayout
    private lateinit var multiBox: LinearLayout
    private lateinit var statMin: TextView
    private lateinit var statAvg: TextView
    private lateinit var statMax: TextView
    private lateinit var statJitter: TextView
    private lateinit var statLoss: TextView
    private lateinit var statOutages: TextView
    private lateinit var scroll: ScrollView
    private lateinit var output: TextView
    private lateinit var netLine: TextView

    private val multiRows = ArrayList<RowViews>()

    private var run = Run.IDLE
    private var lastRun = Run.IDLE
    private var idleGlobal = true
    @Volatile private var stopRequested = false
    private var traceDebug = false

    private val executor = Executors.newSingleThreadExecutor()
    private val sessionPool = Executors.newCachedThreadPool()
    private val probePool = Executors.newFixedThreadPool(PROBES_PER_HOP)
    private val activeProcesses = CopyOnWriteArrayList<Process>()

    private val buffer = SpannableStringBuilder()
    private var consoleLines = 0

    private var sessions: List<PingSession> = emptyList()
    private val emptyStats = PingStats()
    private var netInfo = NetInfo.EMPTY

    private val lossTick = object : Runnable {
        override fun run() {
            if (run != Run.PING) return
            sessions.forEach { it.tick() }
            updateStats()
            view.postDelayed(this, 500)
        }
    }

    private val sessionListener = object : PingSession.Listener {
        override fun onReply(session: PingSession, reply: PingReply, sampleTime: Long) {
            chart.addSample(sessions.indexOf(session), sampleTime, reply.timeMs.toFloat())
            val shown = if (reply.timeMs < 1) "<1" else reply.timeMs.roundToInt().toString()
            append(
                "${prefix(session)}Resposta de ${reply.address}: bytes=${session.size} tempo=${shown}ms TTL=${reply.ttl}",
                if (sessions.size > 1) session.color else ui.text
            )
            updateStats()
        }

        override fun onLoss(session: PingSession, sendTime: Long) {
            chart.addLoss(sessions.indexOf(session), sendTime)
            append("${prefix(session)}Esgotado o tempo limite da solicitação.", ui.amber)
            updateStats()
        }

        override fun onOutage(session: PingSession, down: Boolean) {
            if (!host.prefs().getBoolean(Prefs.VIBRATE, true)) return
            vibrate(if (down) longArrayOf(0, 400, 150, 400) else longArrayOf(0, 70, 90, 70))
        }

        override fun onErrorLine(session: PingSession, line: String) {
            append("${prefix(session)}$line", ui.red)
        }

        override fun onFinished(session: PingSession, error: String?) {
            printSummary(session, error)
            if (sessions.all { it.finished }) completeRun()
        }
    }

    init {
        view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        build()
        hostInput.setText(host.prefs().getString(Prefs.HOST, "") ?: "")
        refreshButtons()
        updateStats()
    }

    // =====================================================================
    // API usada pela activity
    // =====================================================================

    fun onModeChanged(idle: Boolean) {
        idleGlobal = idle
        refreshButtons()
    }

    fun onNetworkChanged() {
        netInfo = host.netInfo()
        updateNetLine()
        refreshChips()
    }

    /** Ao voltar para o app: reinicia o ping em andamento, para não depender de um processo preso no bloqueio. */
    fun onReturnedToForeground(awayMs: Long) {
        if (run == Run.PING && awayMs > 2000) sessions.forEach { it.restart() }
    }

    fun isPinging(): Boolean = run == Run.PING

    /** Preenche o campo com [ip] e começa a pingar (se não houver outro ping rodando). */
    fun pingNow(ip: String) {
        if (run != Run.IDLE) return
        hostInput.setText(ip)
        hostInput.setSelection(ip.length)
        startPing()
    }

    /** Retoma o último ping depois que o sistema encerrou o app em segundo plano. */
    fun resumePing() = startPing()

    fun applyKeepScreen() {
        if (run == Run.PING) host.setKeepScreenOn(host.prefs().getBoolean(Prefs.KEEP_SCREEN, true))
    }

    fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(hostInput.windowToken, 0)
        hostInput.clearFocus()
    }

    fun shutdown() {
        stopRequested = true
        view.removeCallbacks(lossTick)
        sessions.forEach { it.stop() }
        activeProcesses.forEach { it.destroy() }
        executor.shutdownNow()
        sessionPool.shutdownNow()
        probePool.shutdownNow()
    }

    // =====================================================================
    // Interface
    // =====================================================================

    private fun build() {
        // ---- campo do alvo, com a estrela de favorito dentro ----
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.field()
        }
        hostInput = EditText(activity).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(ui.white)
            setHintTextColor(ui.muted)
            hint = "IP ou host (vários: 8.8.8.8, 1.1.1.1)"
            // A dica em Barlow (cabe inteira); o que se digita, na fonte do console.
            typeface = Fonts.regular
            background = null
            setPadding(ui.dp(16), 0, ui.dp(4), 0)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    if (run == Run.IDLE) startPing()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    typeface = if (s.isNullOrEmpty()) Fonts.regular else Fonts.code
                    updateStar()
                }
            })
        }
        favButton = IconView(activity, IconView.STAR, 22f).apply {
            color = ui.muted
            background = ui.rippled(Color.TRANSPARENT, 14f)
            isClickable = true
            contentDescription = "Favoritar"
            setOnClickListener { toggleFavorite() }
        }
        inputRow.addView(hostInput, ui.lp(0, ui.dp(52), 1f))
        inputRow.addView(favButton, ui.lp(ui.dp(48), ui.dp(52)))
        view.addView(inputRow, ui.lp(-1, -2))

        // ---- atalhos (gateway, DNS, favoritos, recentes) ----
        chipsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipsRow, ViewGroup.LayoutParams(-2, -2))
        }
        view.addView(chipsScroll, ui.lp(-1, -2).apply { topMargin = ui.dp(10) })

        // ---- Iniciar | Traceroute: uma barra dividida em duas metades iguais ----
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        runButton = ui.button("Iniciar", ui.accent, ui.onAccent) {
            if (run == Run.PING) stopRun() else startPing()
        }
        ui.corners(runButton, floatArrayOf(16f, 5f, 5f, 16f))
        traceButton = ui.button("Traceroute", ui.accent, ui.onAccent) {
            if (run == Run.TRACE) stopRun() else startTraceRoute()
        }
        ui.corners(traceButton, floatArrayOf(5f, 16f, 16f, 5f))
        // Toque longo: mostra a linha crua que o ping devolveu em cada salto (para depurar o formato).
        traceButton.setOnLongClickListener {
            if (run == Run.IDLE) {
                traceDebug = !traceDebug
                Toast.makeText(
                    activity,
                    if (traceDebug) "Traceroute: modo depuração ligado" else "Traceroute: modo depuração desligado",
                    Toast.LENGTH_SHORT
                ).show()
            }
            true
        }
        buttons.addView(runButton, ui.lp(0, ui.dp(52), 1f))
        buttons.addView(traceButton, ui.lp(0, ui.dp(52), 1f).apply { marginStart = ui.dp(3) })
        view.addView(buttons, ui.lp(-1, -2).apply { topMargin = ui.dp(12) })

        // ---- gráfico (compacto, no meio) ----
        val chartCard = ui.card().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
        val chartHeader = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        chartSubtitle = ui.tv("últimos 35 s", 12f, ui.muted)
        val chartTitle = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.tv("Latência", 15f, ui.white, bold = true))
            addView(chartSubtitle, ui.lp(-2, -2).apply { marginStart = ui.dp(8) })
        }
        latencyNow = ui.tv("— ms", 22f, ui.white, bold = true, mono = true)
        chartHeader.addView(chartTitle, ui.lp(0, -2, 1f))
        chartHeader.addView(latencyNow)
        chartCard.addView(chartHeader, ui.lp(-1, -2))

        chart = LatencyChartView(activity)
        chartCard.addView(chart, ui.lp(-1, ui.dp(84)).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(10) })

        singleStats = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun statCell(label: String): TextView {
            val value = ui.tv(DASH, 14f, ui.white, bold = true, mono = true).apply { maxLines = 1 }
            singleStats.addView(ui.metric(label, value), ui.lp(0, -2, 1f))
            return value
        }
        statMin = statCell("Mín")
        statAvg = statCell("Méd")
        statMax = statCell("Máx")
        statJitter = statCell("Jitter")
        statLoss = statCell("Perda")
        statOutages = statCell("Quedas")
        chartCard.addView(singleStats, ui.lp(-1, -2))

        multiBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        chartCard.addView(multiBox, ui.lp(-1, -2))
        view.addView(chartCard, ui.lp(-1, -2).apply { topMargin = ui.dp(12) })

        // ---- console (ocupa o resto da tela) ----
        val consoleCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.roundRect(ui.consoleBg, 20f, ui.panelStroke)
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(10))
        }
        val consoleHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), 0, 0, 0)
        }
        consoleHeader.addView(ui.tv("Console", 15f, ui.white, bold = true), ui.lp(0, -2, 1f))
        consoleHeader.addView(ui.textAction("Enviar") { showShareDialog() })
        consoleHeader.addView(ui.textAction("Limpar") { clearAll() })
        consoleCard.addView(consoleHeader, ui.lp(-1, -2))

        scroll = ScrollView(activity).apply { isFillViewport = true }
        output = ui.tv("", 12f, ui.text).apply {
            ui.code(this)
            setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(4))
            setTextIsSelectable(true)
            setLineSpacing(ui.dpf(2f), 1f)
        }
        scroll.addView(output, ViewGroup.LayoutParams(-1, -2))
        consoleCard.addView(scroll, ui.lp(-1, 0, 1f))
        view.addView(consoleCard, ui.lp(-1, 0, 1f).apply { topMargin = ui.dp(12) })

        // ---- rede atual (uma linha) ----
        netLine = ui.tv("", 12f, ui.text, mono = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(4), 0, ui.dp(4), 0)
        }
        view.addView(netLine, ui.lp(-1, -2).apply { topMargin = ui.dp(8) })
    }

    private fun refreshButtons() {
        when (run) {
            Run.PING -> {
                runButton.text = "Parar"
                ui.danger(runButton)
                ui.setEnabled(runButton, true)
                traceButton.text = "Traceroute"
                ui.primary(traceButton)
                ui.setEnabled(traceButton, false)
            }
            Run.TRACE -> {
                runButton.text = "Iniciar"
                ui.primary(runButton)
                ui.setEnabled(runButton, false)
                traceButton.text = "Parar"
                ui.danger(traceButton)
                ui.setEnabled(traceButton, true)
            }
            Run.IDLE -> {
                runButton.text = "Iniciar"
                ui.primary(runButton)
                ui.setEnabled(runButton, idleGlobal)
                traceButton.text = "Traceroute"
                ui.primary(traceButton)
                ui.setEnabled(traceButton, idleGlobal)
            }
        }
    }

    /** "Wi-Fi 192.168.0.23 · GW 192.168.0.1 · DNS 8.8.8.8", com rótulos em cinza (GW/DNS juntos se iguais). */
    private fun updateNetLine() {
        val sb = SpannableStringBuilder()
        fun part(label: String?, value: String) {
            if (sb.isNotEmpty()) sb.append("     ")
            if (label != null) {
                val start = sb.length
                sb.append(label).append(' ')
                sb.setSpan(ForegroundColorSpan(ui.muted), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append(value)
        }
        val n = netInfo
        part(null, "${n.type} ${n.ip}".trim())
        if (n.gateway == n.dns) {
            part("GW/DNS", n.gateway)
        } else {
            part("GW", n.gateway)
            part("DNS", n.dns)
        }
        netLine.text = sb
    }

    // =====================================================================
    // Estatísticas (uma linha de resumo ou uma linha por alvo)
    // =====================================================================

    private fun buildStatsPanel() {
        val multi = sessions.size > 1
        singleStats.visibility = if (multi) View.GONE else View.VISIBLE
        multiBox.visibility = if (multi) View.VISIBLE else View.GONE
        latencyNow.visibility = if (multi) View.GONE else View.VISIBLE
        chartSubtitle.text = if (multi) "${sessions.size} alvos, últimos 35 s" else "últimos 35 s"

        multiBox.removeAllViews()
        multiRows.clear()
        if (!multi) return

        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(View(activity), ui.lp(0, 1, 1f))
        for ((text, width) in listOf("Atual" to 56, "Méd" to 56, "Perda" to 44)) {
            header.addView(
                ui.medium(text, 11f, ui.muted).apply { gravity = Gravity.END },
                ui.lp(ui.dp(width), -2)
            )
        }
        multiBox.addView(header, ui.lp(-1, -2))

        for (s in sessions) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(3), 0, ui.dp(3))
            }
            val dot = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(s.color)
                }
            }
            val name = ui.tv(s.host, 13f, ui.white, mono = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val cur = ui.tv(DASH, 13f, ui.white, bold = true, mono = true).apply { gravity = Gravity.END }
            val avg = ui.tv(DASH, 13f, ui.white, mono = true).apply { gravity = Gravity.END }
            val loss = ui.tv(DASH, 13f, ui.white, mono = true).apply { gravity = Gravity.END }
            row.addView(dot, ui.lp(ui.dp(8), ui.dp(8)))
            row.addView(name, ui.lp(0, -2, 1f).apply { marginStart = ui.dp(8) })
            row.addView(cur, ui.lp(ui.dp(56), -2))
            row.addView(avg, ui.lp(ui.dp(56), -2))
            row.addView(loss, ui.lp(ui.dp(44), -2))
            multiBox.addView(row, ui.lp(-1, -2))
            multiRows.add(RowViews(cur, avg, loss))
        }
    }

    private fun updateStats() {
        if (sessions.size > 1) updateMultiStats() else updateSingleStats(sessions.firstOrNull()?.stats ?: emptyStats)
    }

    private fun updateSingleStats(s: PingStats) {
        when {
            s.lastWasLoss -> {
                latencyNow.text = "perdido"
                latencyNow.setTextColor(ui.red)
            }
            s.last >= 0 -> {
                latencyNow.text = "${ui.formatMs(s.last)} ms"
                latencyNow.setTextColor(LatencyChartView.colorFor(s.last))
            }
            else -> {
                latencyNow.text = "— ms"
                latencyNow.setTextColor(ui.white)
            }
        }
        statMin.text = if (s.received > 0) "${ui.formatMs(s.min)}ms" else DASH
        statAvg.text = if (s.received > 0) "${ui.formatMs(s.avg)}ms" else DASH
        statMax.text = if (s.received > 0) "${ui.formatMs(s.max)}ms" else DASH
        statJitter.text = if (s.jitterSamples > 0) "${ui.formatMs(s.jitter)}ms" else DASH
        statLoss.text = if (s.sent > 0) "${s.lossPct.roundToInt()}%" else DASH
        statLoss.setTextColor(if (s.lost > 0) ui.red else ui.white)
        statOutages.text = if (s.sent > 0) s.outages.toString() else DASH
        statOutages.setTextColor(if (s.outages > 0) ui.red else ui.white)
    }

    private fun updateMultiStats() {
        for ((i, s) in sessions.withIndex()) {
            val row = multiRows.getOrNull(i) ?: continue
            val st = s.stats
            when {
                st.lastWasLoss -> {
                    row.cur.text = "perdido"
                    row.cur.setTextColor(ui.red)
                }
                st.last >= 0 -> {
                    row.cur.text = "${ui.formatMs(st.last)}ms"
                    row.cur.setTextColor(LatencyChartView.colorFor(st.last))
                }
                else -> {
                    row.cur.text = DASH
                    row.cur.setTextColor(ui.white)
                }
            }
            row.avg.text = if (st.received > 0) "${ui.formatMs(st.avg)}ms" else DASH
            row.loss.text = if (st.sent > 0) "${st.lossPct.roundToInt()}%" else DASH
            row.loss.setTextColor(if (st.lost > 0) ui.red else ui.white)
        }
    }

    // =====================================================================
    // Favoritos e histórico
    // =====================================================================

    private fun loadList(key: String): MutableList<String> =
        (host.prefs().getString(key, "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableList()

    private fun saveList(key: String, list: List<String>) {
        host.prefs().edit().putString(key, list.joinToString("\n")).apply()
    }

    private fun addHistory(entry: String) {
        val list = loadList(Prefs.HISTORY)
        list.remove(entry)
        list.add(0, entry)
        saveList(Prefs.HISTORY, list.take(MAX_HISTORY))
        refreshChips()
    }

    private fun toggleFavorite() {
        val entry = hostInput.text.toString().trim()
        if (entry.isEmpty()) {
            Toast.makeText(activity, "Digite um alvo para favoritar", Toast.LENGTH_SHORT).show()
            return
        }
        val favs = loadList(Prefs.FAVORITES)
        if (!favs.remove(entry)) favs.add(0, entry)
        saveList(Prefs.FAVORITES, favs)
        refreshChips()
    }

    private fun updateStar() {
        if (!::favButton.isInitialized) return
        val fav = hostInput.text.toString().trim().let { it.isNotEmpty() && it in loadList(Prefs.FAVORITES) }
        favButton.filled = fav
        favButton.color = if (fav) ui.accentInk else ui.muted
    }

    private fun chip(label: String, color: Int, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): TextView =
        ui.medium(label, 13f, color).apply {
            maxLines = 1
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            background = ui.rippled(ui.panel, 18f, ui.panelStroke)
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }

    private fun refreshChips() {
        chipsRow.removeAllViews()
        fun add(view: View) = chipsRow.addView(view, ui.lp(-2, -2).apply { marginEnd = ui.dp(6) })
        fun short(s: String) = if (s.length > 24) s.take(23) + "…" else s
        fun fill(entry: String) {
            hostInput.setText(entry)
            hostInput.setSelection(entry.length)
        }

        if (isUsableAddress(netInfo.gateway)) add(chip("Gateway", ui.white, { addTarget(netInfo.gateway) }))
        if (isUsableAddress(netInfo.dns)) add(chip("DNS", ui.white, { addTarget(netInfo.dns) }))

        val favs = loadList(Prefs.FAVORITES)
        for (f in favs) {
            add(chip("★ ${short(f)}", ui.accentInk, { fill(f) }, {
                favs.remove(f)
                saveList(Prefs.FAVORITES, favs)
                Toast.makeText(activity, "Favorito removido", Toast.LENGTH_SHORT).show()
                refreshChips()
            }))
        }
        for (h in loadList(Prefs.HISTORY).filter { it !in favs }) {
            add(chip(short(h), ui.text, { fill(h) }, {
                saveList(Prefs.HISTORY, loadList(Prefs.HISTORY).filter { it != h })
                Toast.makeText(activity, "Removido do histórico", Toast.LENGTH_SHORT).show()
                refreshChips()
            }))
        }

        chipsScroll.visibility = if (chipsRow.childCount == 0) View.GONE else View.VISIBLE
        updateStar()
    }

    /** Atalho de gateway/DNS: preenche o campo, ou acrescenta como mais um alvo se já houver texto. */
    private fun addTarget(ip: String) {
        val current = hostInput.text.toString().trim()
        val text = when {
            current.isEmpty() -> ip
            current.split(',').any { it.trim().split(WHITESPACE).firstOrNull() == ip } -> current
            else -> "$current, $ip"
        }
        hostInput.setText(text)
        hostInput.setSelection(text.length)
    }

    // =====================================================================
    // Console
    // =====================================================================

    private fun append(line: String, color: Int = ui.text) {
        val atBottom = scroll.getChildAt(0).let { child ->
            child == null || child.bottom - (scroll.height + scroll.scrollY) < ui.dp(48)
        }
        val span = SpannableString("$line\n")
        span.setSpan(ForegroundColorSpan(color), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        buffer.append(span)
        output.append(span)
        consoleLines++
        if (consoleLines > MAX_CONSOLE_LINES) trimConsole()
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun trimConsole() {
        var cut = 0
        repeat(TRIM_LINES) {
            val idx = buffer.indexOf('\n', cut)
            if (idx >= 0) cut = idx + 1
        }
        if (cut > 0) {
            buffer.delete(0, cut)
            consoleLines -= TRIM_LINES
            output.text = buffer
        }
    }

    private fun clearConsole() {
        buffer.clear()
        consoleLines = 0
        output.text = ""
    }

    /** Limpar: apaga console e gráfico; parado, zera também as estatísticas. */
    private fun clearAll() {
        clearConsole()
        if (run == Run.IDLE) {
            sessions = emptyList()
            lastRun = Run.IDLE
            chart.setSeries(intArrayOf())
            buildStatsPanel()
            updateStats()
        } else {
            chart.clear()
        }
    }

    private fun copyConsole() {
        if (buffer.isEmpty()) {
            Toast.makeText(activity, "Console vazio", Toast.LENGTH_SHORT).show()
            return
        }
        host.copyText(buffer.toString())
    }

    private fun prefix(session: PingSession): String =
        if (sessions.size > 1) "[${session.host}] " else ""

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                activity.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                activity.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    // =====================================================================
    // Ping (um ou vários alvos)
    // =====================================================================

    /**
     * Interpreta um alvo no formato `host [-t] [-n N] [-l TAMANHO]` (também aceita `-c` e `-s`).
     * Mostra o erro no console e devolve null se inválido.
     */
    private fun parseSegment(segment: String): Target? {
        val parts = segment.split(WHITESPACE).filter { it.isNotBlank() }
        val target = parseHost(parts.firstOrNull().orEmpty())
        if (target == null) {
            append("Endereço inválido: ${parts.firstOrNull().orEmpty()}", ui.red)
            return null
        }

        var continuous = true
        var count = 4
        var size = DEFAULT_SIZE
        var index = 1

        fun number(range: IntRange, error: String): Int? {
            val value = parts.getOrNull(index + 1)?.toIntOrNull()
            if (value == null || value !in range) {
                append(error, ui.red)
                return null
            }
            return value
        }

        while (index < parts.size) {
            when (parts[index].lowercase()) {
                "-t" -> {
                    continuous = true
                    index++
                }
                "-n", "-c" -> {
                    count = number(1..10000, "Quantidade deve estar entre 1 e 10000.") ?: return null
                    continuous = false
                    index += 2
                }
                "-l", "-s" -> {
                    size = number(1..65500, "Tamanho deve estar entre 1 e 65500 bytes.") ?: return null
                    index += 2
                }
                else -> {
                    append("Parâmetro desconhecido: ${parts[index]}", ui.red)
                    return null
                }
            }
        }
        return Target(target, continuous, count, size)
    }

    private fun startPing() {
        if (run != Run.IDLE) return

        val raw = hostInput.text.toString().trim()
        val segments = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            append("Informe um IP ou host.", ui.amber)
            return
        }
        if (segments.size > MAX_TARGETS) {
            append("No máximo $MAX_TARGETS alvos ao mesmo tempo.", ui.red)
            return
        }

        val targets = ArrayList<Target>()
        for (segment in segments) targets.add(parseSegment(segment) ?: return)

        if (!host.acquire("Ping ativo", ui.green)) return

        host.prefs().edit().putString(Prefs.HOST, raw).apply()
        addHistory(raw)

        append("")
        append(RULE, ui.muted)
        append("> $raw", ui.white)
        if (targets.size == 1) {
            val t = targets[0]
            val sizeNote = if (t.size != DEFAULT_SIZE) " com ${t.size} bytes" else ""
            append(
                if (t.continuous) "Ping contínuo para ${t.host}$sizeNote"
                else "Ping para ${t.host} (${t.count} tentativas)$sizeNote",
                ui.muted
            )
        } else {
            append("Ping simultâneo para ${targets.size} alvos", ui.muted)
        }
        append("")

        startSessions(targets)
    }

    private fun startSessions(targets: List<Target>) {
        hideKeyboard()
        stopRequested = false

        val colors = IntArray(targets.size) { ui.series[it % ui.series.size] }
        sessions = targets.mapIndexed { i, t ->
            PingSession(t.host, t.continuous, t.count, t.size, colors[i], sessionListener, sessionPool) { block ->
                host.post(block)
            }
        }

        run = Run.PING
        refreshButtons()
        chart.placeholder = "Aguardando respostas…"
        chart.setSeries(colors)
        chart.setLive(true)
        buildStatsPanel()
        updateStats()
        applyKeepScreen()
        view.postDelayed(lossTick, 500)

        sessions.forEach { it.start() }
    }

    private fun printSummary(session: PingSession, error: String?) {
        val s = session.stats
        val tag = prefix(session)
        if (s.sent > 0) {
            append("")
            append("${tag}Estatísticas do Ping para ${session.host}:", ui.white)
            append(
                "    Pacotes: Enviados = ${s.sent}, Recebidos = ${s.received}, " +
                    "Perdidos = ${s.lost} (${s.lossPct.roundToInt()}% de perda),",
                ui.white
            )
            if (s.received > 0) {
                append(
                    "    Mínimo = ${ui.formatMs(s.min)}ms, Máximo = ${ui.formatMs(s.max)}ms, " +
                        "Média = ${ui.formatMs(s.avg)}ms, Jitter = ${ui.formatMs(s.jitter)}ms",
                    ui.white
                )
            } else {
                append("    Nenhuma resposta válida recebida.", ui.amber)
            }
            if (s.outages > 0) {
                append("    Quedas = ${s.outages} (maior: ${formatDuration(s.longestOutageMs)})", ui.amber)
            }
        }
        when {
            error != null -> append("${tag}Erro: $error", ui.red)
            session.hardError -> append("${tag}Falha ao resolver ou alcançar o destino.", ui.red)
        }
    }

    private fun completeRun() {
        view.removeCallbacks(lossTick)
        chart.setLive(false)
        chart.placeholder = LatencyChartView.IDLE_TEXT
        append(if (stopRequested) "Interrompido pelo usuário." else "Concluído.", ui.muted)
        append(RULE, ui.muted)

        run = Run.IDLE
        lastRun = Run.PING
        host.setKeepScreenOn(false)
        host.release()
        refreshButtons()
        updateStats()
    }

    private fun stopRun() {
        if (run == Run.IDLE) return
        stopRequested = true
        append("Interrompendo...", ui.muted)
        sessions.forEach { it.stop() }
        activeProcesses.forEach { it.destroy() }
    }

    // =====================================================================
    // Compartilhar
    // =====================================================================

    private fun reportTitle(): String = "PingLab — relatório de ping"

    private fun reportBody(): String {
        val sb = StringBuilder()
        sb.append("Rede: ${netInfo.type} · IP ${netInfo.ip}\n")
        sb.append("Gateway ${netInfo.gateway} · DNS ${netInfo.dns}\n")
        for (s in sessions) {
            val st = s.stats
            sb.append('\n')
            sb.append("▸ ").append(s.host).append('\n')
            sb.append("  Enviados ${st.sent} · Recebidos ${st.received} · Perda ${st.lossPct.roundToInt()}%\n")
            if (st.received > 0) {
                sb.append(
                    "  Mín ${ui.formatMs(st.min)}ms · Méd ${ui.formatMs(st.avg)}ms · " +
                        "Máx ${ui.formatMs(st.max)}ms · Jitter ${ui.formatMs(st.jitter)}ms\n"
                )
            } else {
                sb.append("  Nenhuma resposta\n")
            }
            if (st.outages > 0) {
                sb.append("  Quedas ${st.outages} · maior ${formatDuration(st.longestOutageMs)}\n")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun reportText(): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())
        return "${reportTitle()}\n$date\n\n${reportBody()}"
    }

    private fun showShareDialog() {
        if (buffer.isEmpty() && sessions.isEmpty()) {
            Toast.makeText(activity, "Nada para enviar ainda", Toast.LENGTH_SHORT).show()
            return
        }
        // Depois de um ping há um resumo; depois de um traceroute (ou nada) só o texto do console.
        val pingReport = sessions.isNotEmpty() && lastRun == Run.PING
        val options = if (pingReport) {
            arrayOf("Enviar resumo (texto)", "Enviar imagem (gráfico + estatísticas)", "Copiar console")
        } else {
            arrayOf("Enviar console", "Copiar console")
        }
        AlertDialog.Builder(activity)
            .setTitle("Enviar")
            .setItems(options) { _, which ->
                when {
                    which == options.size - 1 -> copyConsole()
                    pingReport && which == 0 -> host.shareText(reportText())
                    pingReport && which == 1 -> host.shareImage(reportTitle(), reportBody(), chart)
                    else -> host.shareText(buffer.toString())
                }
            }
            .show()
    }

    // =====================================================================
    // Traceroute
    // =====================================================================

    private fun startTraceRoute() {
        if (run != Run.IDLE) return

        val firstSegment = hostInput.text.toString().split(',').firstOrNull().orEmpty().trim()
        val target = parseHost(firstSegment.split(WHITESPACE).firstOrNull().orEmpty())
        if (target == null) {
            append("Informe um IP ou hostname válido para o traceroute.", ui.amber)
            return
        }
        if (!host.acquire("Traceroute", ui.amber)) return

        host.prefs().edit().putString(Prefs.HOST, target).apply()
        hideKeyboard()

        append("")
        append(RULE, ui.muted)
        append("> traceroute $target", ui.white)
        append("Rastreando rota para $target (máx. $MAX_HOPS saltos, $PROBES_PER_HOP sondas por salto)", ui.muted)
        append("")

        stopRequested = false
        run = Run.TRACE
        refreshButtons()
        executor.execute { runTrace(target) }
    }

    private fun runTrace(target: String) {
        var failure: String? = null
        var reached = false
        val debug = traceDebug

        try {
            val overhead = Tracer.calibrateOverhead(activeProcesses)

            for (ttl in 1..MAX_HOPS) {
                if (stopRequested) break

                val futures = (1..PROBES_PER_HOP).map {
                    probePool.submit(Callable<Tracer.Probe> { Tracer.probe(target, ttl, overhead, activeProcesses) })
                }
                val probes = futures.map { it.get() }
                if (stopRequested) break

                val hardRaw = probes.firstNotNullOfOrNull { p ->
                    p.raw?.takeIf { PingSession.isHardError(it.lowercase()) }
                }
                if (hardRaw != null) {
                    failure = hardRaw
                    break
                }

                val addresses = probes.mapNotNull { it.address }.distinct()
                val cells = probes.joinToString("") { p ->
                    val text = p.ms?.let { if (it < 1.0) "<1 ms" else "${it.roundToInt()} ms" } ?: "*"
                    text.padStart(8)
                }
                val where = if (addresses.isEmpty()) "Esgotado o tempo limite" else addresses.joinToString(", ")
                val hopReached = probes.any { it.reached }
                val color = if (addresses.isEmpty()) ui.amber else ui.text
                val rawNotes: List<String> = when {
                    debug -> probes.mapNotNull { it.line }.distinct()
                    addresses.isEmpty() -> listOfNotNull(probes.firstNotNullOfOrNull { it.raw })
                    else -> emptyList()
                }

                host.post {
                    append("${ttl.toString().padStart(2)}$cells  $where", color)
                    rawNotes.forEach { append("      ↳ $it", ui.muted) }
                }

                if (hopReached) {
                    reached = true
                    break
                }
            }
        } catch (e: Exception) {
            if (!stopRequested) {
                failure = (e.cause?.message ?: e.message) ?: "falha ao executar as sondagens do traceroute"
            }
        }

        host.post {
            when {
                stopRequested -> append("Traceroute interrompido pelo usuário.", ui.muted)
                failure != null -> append("Falha no traceroute: $failure", ui.red)
                reached -> append("Destino alcançado.", ui.green)
                else -> append("Rastreamento encerrado ($MAX_HOPS saltos sem alcançar o destino).", ui.amber)
            }
            append(RULE, ui.muted)
            run = Run.IDLE
            lastRun = Run.TRACE
            host.release()
            refreshButtons()
        }
    }
}
