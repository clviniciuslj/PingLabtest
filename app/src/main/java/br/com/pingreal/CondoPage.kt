package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Aba "Condomínio" (a casinha): as ONUs cadastradas por quadra e lote, como no Monitor de ONUs do PC.
 * Dá para cadastrar, pingar uma ONU (ping individual), pingar uma quadra inteira ou pingar tudo ao mesmo
 * tempo (monitoramento contínuo com a regra das 3 falhas seguidas).
 */
class CondoPage(
    private val activity: Activity,
    private val ui: UiKit,
    private val host: PageHost
) {
    private class OnuRow(var onu: Onu, val prefix: String) {
        val ip: String get() = onu.ip(prefix)
        val identificacao: String get() = onu.identificacao.orEmpty()

        var state = OnuState.DESCONHECIDO
        var tx = 0
        var rx = 0
        var latencyText = "—"
        var lastResponseText = "—"
        var lossText = "—"

        val statusText: String
            get() = when (state) {
                OnuState.ONLINE -> "Online"
                OnuState.INSTAVEL -> "Instável"
                OnuState.SEM_RESPOSTA -> "Sem resposta"
                OnuState.ERRO -> "Erro"
                OnuState.DESCONHECIDO -> "Aguardando"
            }

        fun apply(s: OnuSnapshot) {
            state = s.state
            tx = s.tx
            rx = s.rx
            latencyText = s.latencyMs?.let { PingLineFormatter.latencyText(it) } ?: "—"
            lastResponseText = s.lastResponseAt?.let { formatTime(it) } ?: "—"
            lossText = "${formatPercent(s.lossPercent)}%"
        }

        /** Outro endereço = outro equipamento: o estado anterior não vale mais. */
        fun reset() {
            state = OnuState.DESCONHECIDO
            tx = 0
            rx = 0
            latencyText = "—"
            lastResponseText = "—"
            lossText = "—"
        }
    }

    private class GroupState {
        var expanded = false
        var pinging = false
        var job: QuadraPingJob? = null
        var failures: List<HostSummary> = emptyList()
    }

    private class RowViews(val root: LinearLayout, val dot: GradientDrawable, val latency: TextView, val detail: TextView)

    private class GroupViews(
        val quadra: Int,
        val chevron: IconView,
        val flag: View,
        val summary: TextView,
        val button: Button,
        val rowsBox: LinearLayout
    ) {
        val rows = HashMap<String, RowViews>()
        var built = false
    }

    private class ConsoleLine(val text: String, val kind: Int, val ip: String?)

    companion object {
        private const val OK = 0
        private const val FAIL = 1
        private const val WARN = 2
        private const val INFO = 3

        private const val MAX_CONSOLE_LINES = 1500
        private const val CONSOLE_VISIBLE_MAX = 1200
        private const val CONSOLE_TRIM = 200
        private const val FLUSH_MS = 250L
        private const val REBUILD_MIN_MS = 1000L

        private fun formatTime(at: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

        private fun formatPercent(v: Double): String {
            val text = String.format(Locale.US, "%.1f", v)
            return if (text.endsWith(".0")) text.dropLast(2) else text
        }
    }

    val view: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val store = OnuStore(java.io.File(activity.filesDir, "onus.json"))
    private val prefix = CondoDefaults.IP_PREFIX

    private val rows = ArrayList<OnuRow>()
    private val rowById = HashMap<String, OnuRow>()
    private val groups = TreeMap<Int, GroupState>()
    private val groupViews = HashMap<Int, GroupViews>()

    private val sampleQueue = ConcurrentLinkedQueue<OnuSample>()
    private val extraConsole = ConcurrentLinkedQueue<ConsoleLine>()
    private val dirtyRows = HashSet<OnuRow>()

    private val monitorPinger = OnuPinger()
    private val monitoring = MonitoringService(monitorPinger) { sampleQueue.add(it) }
    private val terminals = HashMap<String, Terminal>()

    private var busyHeld = false
    private var idleGlobal = true
    private var searchText = ""
    private var onlyErrors = false
    private var needsRebuild = false
    private var lastRebuild = 0L
    private var closed = false

    // Console
    private val allLines = ArrayDeque<ConsoleLine>()
    private val consoleBuffer = SpannableStringBuilder()
    private var consoleCount = 0
    private var consoleFilter: String? = null
    private var consoleOpen = true

    private lateinit var statTotal: TextView
    private lateinit var statResponding: TextView
    private lateinit var statNoResponse: TextView
    private lateinit var statSent: TextView
    private lateinit var statReceived: TextView
    private lateinit var statLoss: TextView
    private lateinit var statPercent: TextView
    private lateinit var summaryBar: SegmentBar
    private lateinit var consoleChevron: IconView
    private lateinit var pingAllButton: Button
    private lateinit var searchInput: EditText
    private lateinit var errorsToggle: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var consoleBody: LinearLayout
    private lateinit var consoleToggle: TextView
    private lateinit var consoleFilterAction: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var consoleOutput: TextView

    private val searchDebounce = Runnable { filterChanged() }

    private val flushTick = object : Runnable {
        override fun run() {
            if (closed) return
            flush()
            handler.postDelayed(this, FLUSH_MS)
        }
    }

    init {
        view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        build()

        val loaded = store.load()
        for (onu in loaded.onus) addRow(onu)
        sortRows()
        loaded.warning?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }

        rebuildList()
        updateSummary()
        updateControls()
        handler.postDelayed(flushTick, FLUSH_MS)
    }

    // =====================================================================
    // API usada pela activity
    // =====================================================================

    fun onModeChanged(idle: Boolean) {
        idleGlobal = idle
        updateControls()
    }

    fun applyKeepScreen() {
        if (monitoring.isRunning) host.setKeepScreenOn(host.prefs().getBoolean(Prefs.KEEP_SCREEN, true))
    }

    fun importText(text: String) {
        val result = OnuImporter.parse(text, prefix, rows.map { it.onu })
        for (onu in result.valid) {
            addRow(onu)
            monitoring.add(onu)
        }
        var saveError: String? = null
        if (result.valid.isNotEmpty()) saveError = afterChange()

        val sb = StringBuilder("${result.valid.size} ONU(s) importada(s).")
        if (result.errors.isNotEmpty()) {
            sb.append("\n\n${result.errors.size} linha(s) ignorada(s):")
            for (e in result.errors.take(12)) sb.append("\nLinha ${e.line}: ${e.reason}")
            if (result.errors.size > 12) sb.append("\n… e mais ${result.errors.size - 12}.")
        }
        if (saveError != null) sb.append("\n\n$saveError")
        AlertDialog.Builder(activity).setTitle("Importação").setMessage(sb.toString()).setPositiveButton("OK", null).show()
    }

    fun shutdown() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        monitoring.stop()
        groups.values.forEach { it.job?.cancel() }
        terminals.values.toList().forEach { it.close() }
    }

    // =====================================================================
    // Interface
    // =====================================================================

    private fun build() {
        // ---- resumo: quantas respondem, barra por estado e os totais ----
        val summary = ui.card()
        statResponding = ui.tv("0", 36f, ui.white, bold = true, mono = true)
        statTotal = ui.tv("de 0", 18f, ui.muted, mono = true)
        statPercent = ui.medium("respondendo", 13f, ui.muted)
        val hero = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statResponding)
            addView(statTotal, ui.lp(-2, -2).apply { marginStart = ui.dp(8) })
            addView(View(activity), ui.lp(0, 1, 1f))
            addView(statPercent)
        }
        summary.addView(hero, ui.lp(-1, -2))
        summaryBar = SegmentBar(activity)
        summary.addView(summaryBar, ui.lp(-1, ui.dp(8)).apply { topMargin = ui.dp(8) })
        val totals = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun metric(label: String): TextView {
            val value = ui.tv("0", 15f, ui.white, bold = true, mono = true).apply { maxLines = 1 }
            totals.addView(ui.metric(label, value), ui.lp(0, -2, if (label == "Sem resposta") 1.35f else 1f))
            return value
        }
        statNoResponse = metric("Sem resposta")
        statSent = metric("Enviados")
        statReceived = metric("Recebidos")
        statLoss = metric("Perda")
        summary.addView(totals, ui.lp(-1, -2).apply { topMargin = ui.dp(14) })
        view.addView(summary, ui.lp(-1, -2))

        // ---- ações ----
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        pingAllButton = ui.button("Pingar tudo", ui.accent, ui.onAccent) { toggleMonitoring() }
        val addButton = ui.button("Cadastrar", ui.softFill(ui.accent), ui.accentInk) { showOnuDialog(null) }
        val importButton = ui.button("Importar", ui.softFill(ui.accent), ui.accentInk) {
            host.pickTextFile { text -> importText(text) }
        }
        actions.addView(pingAllButton, ui.lp(0, ui.dp(52), 1.3f))
        actions.addView(addButton, ui.lp(0, ui.dp(52), 1f).apply { marginStart = ui.dp(8) })
        actions.addView(importButton, ui.lp(0, ui.dp(52), 1f).apply { marginStart = ui.dp(8) })
        view.addView(actions, ui.lp(-1, -2).apply { topMargin = ui.dp(12) })

        // ---- busca e filtros ----
        val filters = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchInput = EditText(activity).apply {
            setSingleLine(true)
            textSize = 14f
            typeface = Fonts.regular
            setTextColor(ui.white)
            setHintTextColor(ui.muted)
            hint = "Buscar IP ou nome"
            background = ui.field()
            setPadding(ui.dp(14), 0, ui.dp(8), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchText = s?.toString().orEmpty().trim()
                    handler.removeCallbacks(searchDebounce)
                    handler.postDelayed(searchDebounce, 250)
                }
            })
        }
        filters.addView(searchInput, ui.lp(0, ui.dp(46), 1f))
        errorsToggle = filterAction("Erros") {
            onlyErrors = !onlyErrors
            styleErrorsToggle()
            filterChanged()
        }
        filters.addView(errorsToggle, ui.lp(-2, ui.dp(46)).apply { marginStart = ui.dp(8) })
        filters.addView(iconAction(IconView.EXPAND_ALL, "Abrir todas as quadras") { setAllExpanded(true) }, ui.lp(ui.dp(46), ui.dp(46)).apply { marginStart = ui.dp(8) })
        filters.addView(iconAction(IconView.COLLAPSE_ALL, "Fechar todas as quadras") { setAllExpanded(false) }, ui.lp(ui.dp(46), ui.dp(46)).apply { marginStart = ui.dp(8) })
        view.addView(filters, ui.lp(-1, -2).apply { topMargin = ui.dp(12) })
        styleErrorsToggle()

        // ---- lista agrupada por quadra ----
        listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(listBox)
        }
        view.addView(listScroll, ui.lp(-1, 0, 1f).apply { topMargin = ui.dp(12) })

        // ---- console ----
        val consoleCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.roundRect(ui.consoleBg, 18f, ui.panelStroke)
            setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(6))
        }
        val consoleHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        consoleChevron = IconView(activity, IconView.CHEVRON, 18f).apply { color = ui.muted }
        consoleToggle = ui.tv("Console", 15f, ui.white, bold = true)
        val toggleArea = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), ui.dp(10), ui.dp(12), ui.dp(10))
            background = ui.rippled(Color.TRANSPARENT, 12f)
            isClickable = true
            setOnClickListener { setConsoleOpen(!consoleOpen) }
            addView(consoleChevron, ui.lp(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(6) })
            addView(consoleToggle)
        }
        consoleHeader.addView(toggleArea)
        consoleHeader.addView(View(activity), ui.lp(0, 1, 1f))
        consoleFilterAction = ui.textAction("Todas") { showConsoleFilterDialog() }
        consoleHeader.addView(consoleFilterAction)
        consoleHeader.addView(ui.textAction("Limpar") { clearConsole() })
        consoleHeader.addView(ui.textAction("Copiar") { copyConsole() })
        consoleCard.addView(consoleHeader, ui.lp(-1, -2))

        consoleBody = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        consoleScroll = ScrollView(activity).apply { isFillViewport = true }
        consoleOutput = ui.tv("", 11.5f, ui.text).apply {
            ui.code(this)
            setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(2))
            setTextIsSelectable(true)
        }
        consoleScroll.addView(consoleOutput, ViewGroup.LayoutParams(-1, -2))
        consoleBody.addView(consoleScroll, ui.lp(-1, ui.dp(110)))
        consoleCard.addView(consoleBody, ui.lp(-1, -2))
        view.addView(consoleCard, ui.lp(-1, -2).apply { topMargin = ui.dp(12) })
    }

    private fun filterAction(label: String, onClick: () -> Unit): TextView =
        ui.medium(label, 14f, ui.text).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun iconAction(kind: Int, description: String, onClick: () -> Unit): IconView =
        IconView(activity, kind, 20f).apply {
            color = ui.text
            background = ui.rippled(ui.panel, 14f, ui.panelStroke)
            isClickable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }

    private fun styleErrorsToggle() {
        errorsToggle.setTextColor(if (onlyErrors) ui.red else ui.text)
        errorsToggle.background =
            if (onlyErrors) ui.rippled(ui.softFill(ui.red), 14f) else ui.rippled(ui.panel, 14f, ui.panelStroke)
    }

    private fun updateControls() {
        val canStart = monitoring.isRunning || idleGlobal || busyHeld
        if (monitoring.isRunning) {
            pingAllButton.text = "Parar"
            ui.danger(pingAllButton)
        } else {
            pingAllButton.text = "Pingar tudo"
            ui.primary(pingAllButton)
        }
        ui.setEnabled(pingAllButton, canStart)
        for ((quadra, gv) in groupViews) {
            val gs = groups[quadra] ?: continue
            gv.button.text = if (gs.pinging) "Cancelar" else "Pingar quadra"
            if (gs.pinging) ui.danger(gv.button) else ui.secondary(gv.button)
            ui.setEnabled(gv.button, gs.pinging || canStart)
        }
    }

    // =====================================================================
    // Dados
    // =====================================================================

    private fun addRow(onu: Onu): OnuRow {
        val row = OnuRow(onu, prefix)
        rows.add(row)
        rowById[onu.id] = row
        return row
    }

    private fun sortRows() {
        rows.sortWith(compareBy({ it.onu.quadra }, { it.onu.lote }))
        for (row in rows) groups.getOrPut(row.onu.quadra) { GroupState() }
    }

    /** Atualiza lista, resumo e grava em disco. Devolve a mensagem de erro de gravação, ou null. */
    private fun afterChange(): String? {
        sortRows()
        rebuildList()
        updateSummary()
        return try {
            store.save(rows.map { it.onu })
            null
        } catch (e: Exception) {
            "Falha ao salvar os dados: ${e.message}".also { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun validate(quadra: Int, lote: Int, ignoreId: String?): String? {
        if (quadra !in 0..255 || lote !in 0..255) return "Quadra e lote devem estar entre 0 e 255."
        if (rows.any { it.onu.id != ignoreId && it.onu.quadra == quadra && it.onu.lote == lote }) {
            return "Quadra $quadra lote $lote já cadastrado."
        }
        return null
    }

    private fun tryAdd(quadra: Int, lote: Int, identificacao: String?): String? {
        validate(quadra, lote, null)?.let { return it }
        val onu = Onu.create(quadra, lote, identificacao)
        addRow(onu)
        monitoring.add(onu)
        afterChange()
        return null
    }

    private fun tryEdit(row: OnuRow, quadra: Int, lote: Int, identificacao: String?): String? {
        validate(quadra, lote, row.onu.id)?.let { return it }
        val onu = row.onu.copy(
            quadra = quadra,
            lote = lote,
            identificacao = identificacao?.trim()?.takeIf { it.isNotEmpty() }
        )
        if (quadra == row.onu.quadra && lote == row.onu.lote) {
            // Só o rótulo mudou: é o mesmo equipamento, então o monitoramento e as estatísticas seguem intactos.
            row.onu = onu
        } else {
            // Outro endereço = outro equipamento: o estado anterior não vale mais.
            monitoring.remove(onu.id)
            monitoring.forget(onu.id)
            row.onu = onu
            row.reset()
            monitoring.add(onu)
        }
        afterChange()
        return null
    }

    private fun remove(row: OnuRow) {
        monitoring.remove(row.onu.id)
        monitoring.forget(row.onu.id)
        rows.remove(row)
        rowById.remove(row.onu.id)
        dirtyRows.remove(row)
        afterChange()
    }

    // =====================================================================
    // Lista
    // =====================================================================

    private fun matches(row: OnuRow): Boolean {
        if (onlyErrors && row.state != OnuState.INSTAVEL && row.state != OnuState.SEM_RESPOSTA && row.state != OnuState.ERRO) {
            return false
        }
        if (searchText.isNotEmpty()) {
            if (!row.ip.contains(searchText, ignoreCase = true) &&
                !row.identificacao.contains(searchText, ignoreCase = true)
            ) return false
        }
        return true
    }

    private fun filterActive() = searchText.isNotEmpty() || onlyErrors

    private fun filterChanged() {
        if (filterActive()) {
            // Com filtro ativo, as quadras que têm resultado ficam abertas.
            for (row in rows.filter { matches(it) }) groups.getOrPut(row.onu.quadra) { GroupState() }.expanded = true
        }
        rebuildList()
    }

    private fun setAllExpanded(expanded: Boolean) {
        for (gs in groups.values) gs.expanded = expanded
        rebuildList()
    }

    private fun rebuildList() {
        lastRebuild = System.currentTimeMillis()
        needsRebuild = false
        listBox.removeAllViews()
        groupViews.clear()

        if (rows.isEmpty()) {
            listBox.addView(ui.tv("Nenhuma ONU cadastrada.", 14f, ui.muted).apply { setPadding(ui.dp(4), ui.dp(8), 0, 0) })
            updateControls()
            return
        }

        for (quadra in groups.keys.toList()) {
            val shown = rows.filter { it.onu.quadra == quadra && matches(it) }
            if (shown.isEmpty()) continue
            listBox.addView(buildGroup(quadra), ui.lp(-1, -2).apply { bottomMargin = ui.dp(10) })
        }
        updateControls()
    }

    private fun buildGroup(quadra: Int): View {
        val gs = groups.getOrPut(quadra) { GroupState() }

        val chevron = IconView(activity, IconView.CHEVRON, 18f).apply {
            color = ui.muted
            rotation = if (gs.expanded) 0f else -90f
        }
        val flag = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ui.red)
            }
            isClickable = true
            contentDescription = "Último ping da quadra teve perda"
            setOnClickListener { showFailureDialog(quadra) }
        }
        val summary = ui.tv("", 13f, ui.muted).apply { setPadding(ui.dp(26), ui.dp(2), 0, 0) }
        val button = ui.button("Pingar quadra", ui.softFill(ui.accent), ui.accentInk) { toggleQuadraPing(quadra) }
        button.textSize = 13f
        button.setPadding(ui.dp(12), 0, ui.dp(12), 0)
        val rowsBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (gs.expanded) View.VISIBLE else View.GONE
        }
        val gv = GroupViews(quadra, chevron, flag, summary, button, rowsBox)
        groupViews[quadra] = gv

        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(chevron, ui.lp(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(6) })
        top.addView(ui.tv("Quadra $quadra", 16f, ui.white, bold = true), ui.lp(-2, -2))
        top.addView(flag, ui.lp(ui.dp(9), ui.dp(9)).apply { marginStart = ui.dp(8) })
        top.addView(View(activity), ui.lp(0, 1, 1f))
        top.addView(button, ui.lp(-2, ui.dp(36)).apply { marginStart = ui.dp(8) })

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            background = ui.rippled(Color.TRANSPARENT, 18f)
            isClickable = true
            setOnClickListener { toggleExpanded(quadra) }
            addView(top, ui.lp(-1, -2))
            addView(summary, ui.lp(-1, -2))
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.roundRect(ui.panel, 18f, ui.panelStroke)
            addView(header, ui.lp(-1, -2))
            addView(rowsBox, ui.lp(-1, -2))
        }
        if (gs.expanded) buildRows(gv)
        refreshGroup(quadra)
        return card
    }

    private fun toggleExpanded(quadra: Int) {
        val gs = groups[quadra] ?: return
        val gv = groupViews[quadra] ?: return
        gs.expanded = !gs.expanded
        gv.chevron.animate().rotation(if (gs.expanded) 0f else -90f).setDuration(160).start()
        gv.rowsBox.visibility = if (gs.expanded) View.VISIBLE else View.GONE
        if (gs.expanded && !gv.built) buildRows(gv)
    }

    private fun buildRows(gv: GroupViews) {
        gv.rowsBox.removeAllViews()
        gv.rows.clear()
        for (row in rows.filter { it.onu.quadra == gv.quadra && matches(it) }) {
            gv.rowsBox.addView(
                View(activity).apply { setBackgroundColor(ui.panelStroke) },
                ui.lp(-1, 1).apply { marginStart = ui.dp(16); marginEnd = ui.dp(16) }
            )
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ui.muted)
            }
            val latency = ui.tv("—", 14f, ui.white, bold = true, mono = true).apply { gravity = Gravity.END }
            val detail = ui.tv("", 12f, ui.muted, mono = true).apply { setPadding(ui.dp(18), ui.dp(3), 0, 0) }
            val title = ui.tv("Lote ${row.onu.lote}", 15f, ui.white, bold = true)
            val name = ui.tv(row.identificacao, 13f, ui.muted).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.dp(8), 0, ui.dp(8), 0)
            }

            val line1 = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(activity).apply { background = dot }, ui.lp(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(10) })
                addView(title, ui.lp(-2, -2))
                addView(name, ui.lp(0, -2, 1f))
                addView(latency, ui.lp(-2, -2))
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
                background = ui.rippled(Color.TRANSPARENT, 0f)
                isClickable = true
                setOnClickListener { openTerminal(row) }
                setOnLongClickListener {
                    showRowMenu(row)
                    true
                }
                addView(line1, ui.lp(-1, -2))
                addView(detail, ui.lp(-1, -2))
            }
            gv.rowsBox.addView(root, ui.lp(-1, -2))
            gv.rows[row.onu.id] = RowViews(root, dot, latency, detail)
            refreshRow(row)
        }
        gv.rowsBox.addView(View(activity), ui.lp(-1, ui.dp(4)))
        gv.built = true
    }

    private fun stateColor(state: OnuState): Int = when (state) {
        OnuState.ONLINE -> ui.green
        OnuState.INSTAVEL -> ui.orange
        OnuState.SEM_RESPOSTA -> ui.red
        OnuState.ERRO -> ui.violet
        OnuState.DESCONHECIDO -> ui.muted
    }

    private fun refreshRow(row: OnuRow) {
        val rv = groupViews[row.onu.quadra]?.rows?.get(row.onu.id) ?: return
        val color = stateColor(row.state)
        rv.dot.setColor(color)
        rv.latency.text = row.latencyText

        val gap = "    "
        val sb = SpannableStringBuilder()
        sb.append(row.ip).append(gap)
        val start = sb.length
        sb.append(row.statusText)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("$gap${row.tx}/${row.rx}$gap${row.lossText}$gap${row.lastResponseText}")
        rv.detail.text = sb
    }

    private fun refreshGroup(quadra: Int) {
        val gv = groupViews[quadra] ?: return
        val gs = groups[quadra] ?: return
        val list = rows.filter { it.onu.quadra == quadra }
        val online = list.count { it.state == OnuState.ONLINE }
        val down = list.count { it.state == OnuState.SEM_RESPOSTA || it.state == OnuState.ERRO }
        val unstable = list.count { it.state == OnuState.INSTAVEL }

        val count = if (list.size == 1) "1 ONU" else "${list.size} ONUs"
        val text = "$count, $online online, $down sem resposta"
        gv.summary.text = if (unstable > 0) "$text, $unstable instável(is)" else text
        gv.flag.visibility = if (gs.failures.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateSummary() {
        val total = rows.size
        val responding = rows.count { it.state == OnuState.ONLINE }
        val unstable = rows.count { it.state == OnuState.INSTAVEL }
        val noResponse = rows.count { it.state == OnuState.SEM_RESPOSTA || it.state == OnuState.ERRO }
        val waiting = total - responding - unstable - noResponse
        val sent = rows.sumOf { it.tx.toLong() }
        val received = rows.sumOf { it.rx.toLong() }
        val loss = if (sent == 0L) 0.0 else (sent - received) * 100.0 / sent

        statResponding.text = "$responding"
        statResponding.setTextColor(if (responding > 0) ui.green else ui.white)
        statTotal.text = "de $total"
        statPercent.text = if (total == 0) "respondendo" else "${formatPercent(responding * 100.0 / total)}% respondendo"
        summaryBar.set(
            intArrayOf(responding, unstable, noResponse, waiting),
            intArrayOf(ui.green, ui.orange, ui.red, Color.TRANSPARENT)
        )
        statNoResponse.text = "$noResponse"
        statNoResponse.setTextColor(if (noResponse > 0) ui.red else ui.white)
        statSent.text = String.format(Locale("pt", "BR"), "%,d", sent)
        statReceived.text = String.format(Locale("pt", "BR"), "%,d", received)
        statLoss.text = "${formatPercent(loss)}%"
    }

    // =====================================================================
    // Atualização em lotes (a cada ~250 ms, como no PC)
    // =====================================================================

    private fun flush() {
        var any = false
        val groupsToRefresh = HashSet<Int>()

        while (true) {
            val s = sampleQueue.poll() ?: break
            val row = rowById[s.onu.id] ?: continue
            any = true
            row.apply(s.snapshot)
            dirtyRows.add(row)
            groupsToRefresh.add(row.onu.quadra)
            val kind = if (s.outcome.success) OK else if (s.outcome.failure == PingFailure.TIMED_OUT) FAIL else WARN
            addConsoleLine(ConsoleLine(PingLineFormatter.format(s.at, s.ip, s.outcome), kind, s.ip))
        }
        while (true) {
            val line = extraConsole.poll() ?: break
            addConsoleLine(line)
        }

        if (any) {
            for (row in dirtyRows) refreshRow(row)
            dirtyRows.clear()
            for (q in groupsToRefresh) refreshGroup(q)
            updateSummary()
            if (onlyErrors) needsRebuild = true
        }
        if (needsRebuild && System.currentTimeMillis() - lastRebuild >= REBUILD_MIN_MS) rebuildList()

        flushConsole()
    }

    // =====================================================================
    // Monitoramento (pingar tudo)
    // =====================================================================

    private fun acquireBusy(): Boolean {
        if (busyHeld) return true
        if (!host.acquire("Condomínio", ui.accentInk)) {
            Toast.makeText(activity, "Já existe um teste em andamento.", Toast.LENGTH_SHORT).show()
            return false
        }
        busyHeld = true
        return true
    }

    private fun activeCount(): Int =
        (if (monitoring.isRunning) 1 else 0) + groups.values.count { it.pinging } + terminals.size

    private fun releaseBusyIfIdle() {
        if (busyHeld && activeCount() == 0) {
            busyHeld = false
            host.setKeepScreenOn(false)
            host.release()
        }
        updateControls()
    }

    private fun toggleMonitoring() {
        if (monitoring.isRunning) {
            monitoring.stop()
            releaseBusyIfIdle()
            return
        }
        if (rows.isEmpty()) {
            Toast.makeText(activity, "Cadastre ao menos uma ONU.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!acquireBusy()) return
        monitoring.options = MonitorOptions()
        monitoring.start(rows.map { it.onu })
        applyKeepScreen()
        updateControls()
    }

    // =====================================================================
    // Pingar quadra
    // =====================================================================

    private fun toggleQuadraPing(quadra: Int) {
        val gs = groups.getOrPut(quadra) { GroupState() }
        if (gs.pinging) {
            gs.job?.cancel()
            return
        }
        val ips = rows.filter { it.onu.quadra == quadra }.sortedBy { it.onu.lote }.map { it.ip }
        if (ips.isEmpty()) return
        if (!acquireBusy()) return

        gs.pinging = true
        gs.failures = emptyList()
        enqueueInfo("▶ Quadra $quadra: ${ips.size} ONU(s), ${CondoDefaults.QUADRA_PING_COUNT} pings cada…")

        val job = QuadraPingJob(
            hosts = ips,
            count = CondoDefaults.QUADRA_PING_COUNT,
            intervalMs = CondoDefaults.QUADRA_PING_INTERVAL_MS,
            timeoutMs = CondoDefaults.TIMEOUT_MS,
            concurrency = CondoDefaults.CONCURRENCY,
            onLine = { l -> extraConsole.add(lineOf(l)) },
            onSummary = { s -> extraConsole.add(summaryLine(quadra, s)) },
            onDone = { done, cancelled -> host.post { finishQuadraPing(quadra, ips.size, done, cancelled) } }
        )
        gs.job = job
        job.start()
        refreshGroup(quadra)
        updateControls()
    }

    private fun finishQuadraPing(quadra: Int, total: Int, done: List<HostSummary>, cancelled: Boolean) {
        val gs = groups[quadra] ?: return
        gs.pinging = false
        gs.job = null
        gs.failures = done.filter { it.received < it.sent }
        if (cancelled) {
            enqueueInfo("■ Quadra $quadra: ping cancelado (${done.size}/$total ONU(s) concluída(s)).")
        } else {
            val clean = done.count { it.received == it.sent }
            enqueueInfo("✔ Quadra $quadra concluída: $clean de $total ONU(s) sem perda.")
        }
        refreshGroup(quadra)
        releaseBusyIfIdle()
    }

    private fun showFailureDialog(quadra: Int) {
        val failures = groups[quadra]?.failures.orEmpty()
        if (failures.isEmpty()) return
        val lines = failures.sortedBy { f -> rows.firstOrNull { it.ip == f.host }?.onu?.lote ?: Int.MAX_VALUE }.map { f ->
            val lote = rows.firstOrNull { it.ip == f.host }?.onu?.lote
            val who = if (lote != null) "Lote $lote (${f.host})" else f.host
            "$who: ${f.received}/${f.sent} recebidos (${formatPercent(f.lossPercent)}% de perda)"
        }
        AlertDialog.Builder(activity)
            .setTitle("Quadra $quadra")
            .setMessage("Último ping da quadra: ${failures.size} ONU(s) com perda\n\n${lines.joinToString("\n")}")
            .setPositiveButton("OK", null)
            .show()
    }

    // =====================================================================
    // Console
    // =====================================================================

    private fun lineOf(l: PingLine): ConsoleLine {
        val kind = if (l.outcome.success) OK else if (l.outcome.failure == PingFailure.TIMED_OUT) FAIL else WARN
        return ConsoleLine(PingLineFormatter.format(l.at, l.host, l.outcome), kind, l.host)
    }

    private fun summaryLine(quadra: Int, s: HostSummary): ConsoleLine {
        val avg = s.averageMs?.let { "${formatPercent(it)} ms" } ?: "—"
        val kind = if (s.received == s.sent) OK else if (s.received == 0) FAIL else WARN
        val text = "[${formatTime(System.currentTimeMillis())}]  Quadra $quadra · ${s.host}: " +
            "${s.received}/${s.sent} recebidos, ${formatPercent(s.lossPercent)}% de perda, média $avg"
        return ConsoleLine(text, kind, s.host)
    }

    private fun enqueueInfo(text: String) {
        extraConsole.add(ConsoleLine("[${formatTime(System.currentTimeMillis())}]  $text", INFO, null))
    }

    private fun colorOf(kind: Int): Int = when (kind) {
        OK -> ui.green
        FAIL -> ui.red
        WARN -> ui.amber
        else -> ui.muted
    }

    private fun passesFilter(line: ConsoleLine): Boolean {
        val f = consoleFilter ?: return true
        return line.ip == f
    }

    private fun spanOf(line: ConsoleLine): SpannableString {
        val s = SpannableString(line.text + "\n")
        s.setSpan(ForegroundColorSpan(colorOf(line.kind)), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private val pendingChunk = SpannableStringBuilder()

    private fun addConsoleLine(line: ConsoleLine) {
        allLines.addLast(line)
        while (allLines.size > MAX_CONSOLE_LINES) allLines.removeFirst()
        if (!passesFilter(line)) return
        val span = spanOf(line)
        consoleBuffer.append(span)
        pendingChunk.append(span)
        consoleCount++
    }

    private fun flushConsole() {
        if (consoleCount > CONSOLE_VISIBLE_MAX) {
            var cut = 0
            repeat(CONSOLE_TRIM) {
                val idx = consoleBuffer.indexOf('\n', cut)
                if (idx >= 0) cut = idx + 1
            }
            if (cut > 0) {
                consoleBuffer.delete(0, cut)
                consoleCount -= CONSOLE_TRIM
                pendingChunk.clear()
                if (consoleOpen) consoleOutput.text = consoleBuffer
                if (consoleOpen) consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
                return
            }
        }
        if (pendingChunk.isEmpty()) return
        if (consoleOpen) {
            val atBottom = consoleScroll.getChildAt(0).let { child ->
                child == null || child.bottom - (consoleScroll.height + consoleScroll.scrollY) < ui.dp(40)
            }
            consoleOutput.append(pendingChunk)
            if (atBottom) consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
        }
        pendingChunk.clear()
    }

    private fun rebuildConsole() {
        consoleBuffer.clear()
        pendingChunk.clear()
        consoleCount = 0
        for (line in allLines) {
            if (!passesFilter(line)) continue
            consoleBuffer.append(spanOf(line))
            consoleCount++
        }
        if (consoleOpen) {
            consoleOutput.text = consoleBuffer
            consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setConsoleOpen(open: Boolean) {
        consoleOpen = open
        consoleBody.visibility = if (open) View.VISIBLE else View.GONE
        consoleChevron.animate().rotation(if (open) 0f else -90f).setDuration(160).start()
        if (open) {
            pendingChunk.clear()
            consoleOutput.text = consoleBuffer
            consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearConsole() {
        allLines.clear()
        consoleBuffer.clear()
        pendingChunk.clear()
        consoleCount = 0
        consoleOutput.text = ""
    }

    private fun copyConsole() {
        if (consoleBuffer.isEmpty()) {
            Toast.makeText(activity, "Console vazio", Toast.LENGTH_SHORT).show()
            return
        }
        host.copyText(consoleBuffer.toString())
    }

    private fun showConsoleFilterDialog() {
        val ips = rows.map { it.ip }
        val options = arrayOf("Todas as ONUs") + ips.toTypedArray()
        val selected = consoleFilter?.let { ips.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
        AlertDialog.Builder(activity)
            .setTitle("Console")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                consoleFilter = if (which == 0) null else ips[which - 1]
                consoleFilterAction.text = consoleFilter?.substringAfter("$prefix.") ?: "Todas"
                rebuildConsole()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =====================================================================
    // Cadastro
    // =====================================================================

    /** Aceita "5", "1-8" ou "1,2,5-9". Devolve null se algo for inválido. */
    private fun parseLots(text: String): List<Int>? {
        val result = sortedSetOf<Int>()
        for (token in text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val range = token.split('-')
            when (range.size) {
                1 -> result.add(range[0].toIntOrNull() ?: return null)
                2 -> {
                    val a = range[0].trim().toIntOrNull() ?: return null
                    val b = range[1].trim().toIntOrNull() ?: return null
                    if (a > b || b - a > 255) return null
                    for (n in a..b) result.add(n)
                }
                else -> return null
            }
        }
        return if (result.isEmpty()) null else result.toList()
    }

    private fun showOnuDialog(editing: OnuRow?) {
        fun field(hint: String, number: Boolean) = EditText(activity).apply {
            setSingleLine(true)
            this.hint = hint
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }

        val quadraIn = field("Quadra", true)
        val loteIn = field("Lote  (vários: 1-8)", false)
        val identIn = field("Identificação (opcional)", false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val ipView = ui.tv("IP: —", 12f, ui.muted, mono = true).apply { setPadding(0, ui.dp(10), 0, 0) }
        val errorView = ui.tv("", 12f, ui.red).apply { setPadding(0, ui.dp(6), 0, 0) }

        if (editing != null) {
            quadraIn.setText(editing.onu.quadra.toString())
            loteIn.setText(editing.onu.lote.toString())
            identIn.setText(editing.onu.identificacao.orEmpty())
        } else {
            // Como no PC: o formulário lembra a última quadra.
            groups.keys.lastOrNull()?.let { quadraIn.setText(it.toString()) }
        }

        fun refreshIp() {
            val q = quadraIn.text.toString().trim().toIntOrNull()
            val l = loteIn.text.toString().trim().toIntOrNull()
            ipView.text = if (q != null && l != null) "IP: $prefix.$q.$l" else "IP: —"
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshIp()
        }
        quadraIn.addTextChangedListener(watcher)
        loteIn.addTextChangedListener(watcher)
        refreshIp()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0)
            addView(quadraIn, ui.lp(-1, -2))
            addView(loteIn, ui.lp(-1, -2))
            addView(identIn, ui.lp(-1, -2))
            addView(ipView, ui.lp(-1, -2))
            addView(errorView, ui.lp(-1, -2))
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (editing == null) "Cadastro de ONU" else "Editar ONU")
            .setView(box)
            .setPositiveButton(if (editing == null) "Adicionar" else "Salvar", null)
            .setNegativeButton(if (editing == null) "Fechar" else "Cancelar", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val quadra = quadraIn.text.toString().trim().toIntOrNull()
            val loteText = loteIn.text.toString().trim()
            val ident = identIn.text.toString()

            if (quadra == null) {
                errorView.setTextColor(ui.red)
                errorView.text = "Informe a quadra."
                return@setOnClickListener
            }

            if (editing != null) {
                val lote = loteText.toIntOrNull()
                val error = if (lote == null) "Informe o lote." else tryEdit(editing, quadra, lote, ident)
                if (error == null) dialog.dismiss() else {
                    errorView.setTextColor(ui.red)
                    errorView.text = error
                }
                return@setOnClickListener
            }

            val lots = parseLots(loteText)
            if (lots == null) {
                errorView.setTextColor(ui.red)
                errorView.text = "Informe o lote (ex.: 7 ou 1-8)."
                return@setOnClickListener
            }

            // Um lote: usa a identificação. Vários: cadastra todos sem identificação, pulando os já cadastrados.
            if (lots.size == 1) {
                val error = tryAdd(quadra, lots[0], ident)
                if (error != null) {
                    errorView.setTextColor(ui.red)
                    errorView.text = error
                    return@setOnClickListener
                }
                identIn.setText("")
                if (lots[0] < 255) loteIn.setText((lots[0] + 1).toString())
                errorView.setTextColor(ui.green)
                errorView.text = "✔ $prefix.$quadra.${lots[0]} cadastrada."
            } else {
                var added = 0
                var firstError: String? = null
                for (lote in lots) {
                    val error = tryAdd(quadra, lote, null)
                    if (error == null) added++ else if (firstError == null) firstError = error
                }
                val last = lots.last()
                if (last < 255) loteIn.setText((last + 1).toString())
                errorView.setTextColor(if (firstError == null) ui.green else ui.amber)
                errorView.text = "✔ $added cadastrada(s)." + (firstError?.let { "\n$it" } ?: "")
            }
        }
    }

    private fun showRowMenu(row: OnuRow) {
        AlertDialog.Builder(activity)
            .setTitle("${row.ip}${if (row.identificacao.isEmpty()) "" else " (${row.identificacao})"}")
            .setItems(arrayOf("Ping individual", "Editar…", "Remover")) { _, which ->
                when (which) {
                    0 -> openTerminal(row)
                    1 -> showOnuDialog(row)
                    else -> confirmRemove(row)
                }
            }
            .show()
    }

    private fun confirmRemove(row: OnuRow) {
        AlertDialog.Builder(activity)
            .setTitle("Remover ${row.ip}?")
            .setMessage("Quadra ${row.onu.quadra}, lote ${row.onu.lote}${if (row.identificacao.isEmpty()) "" else " (${row.identificacao})"}.")
            .setPositiveButton("Remover") { _, _ -> remove(row) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =====================================================================
    // Ping individual (a "janela" de terminal de uma ONU)
    // =====================================================================

    private fun openTerminal(row: OnuRow) {
        if (terminals.containsKey(row.onu.id)) return
        if (!acquireBusy()) return
        val terminal = Terminal(row)
        terminals[row.onu.id] = terminal
        terminal.show()
        updateControls()
    }

    /** Ping contínuo (1 por segundo) de uma única ONU. Fechar a janela cancela só este ping. */
    private inner class Terminal(val row: OnuRow) {
        private val text = SpannableStringBuilder()
        private var lineCount = 0
        private var closed = false
        private lateinit var output: TextView
        private lateinit var scroll: ScrollView
        private lateinit var dialog: AlertDialog

        private val loop = PingLoop(row.ip, CondoDefaults.TERMINAL_INTERVAL_MS, CondoDefaults.TIMEOUT_MS) { line ->
            host.post { if (!closed) append(line) }
        }

        fun show() {
            output = ui.tv("", 12f, ui.text).apply {
                ui.code(this)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                setTextIsSelectable(true)
            }
            scroll = ScrollView(activity).apply {
                background = ui.roundRect(ui.consoleBg, 14f, ui.panelStroke)
                addView(output, ViewGroup.LayoutParams(-1, -2))
            }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0)
                addView(scroll, ui.lp(-1, ui.dp(300)))
            }
            val name = if (row.identificacao.isEmpty()) "" else " (${row.identificacao})"

            dialog = AlertDialog.Builder(activity)
                .setTitle("Ping ${row.ip}$name")
                .setView(box)
                .setPositiveButton("Parar", null)
                .setNegativeButton("Fechar", null)
                .create()
            dialog.setOnDismissListener { close() }
            dialog.show()

            val toggle = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            toggle.setOnClickListener {
                if (loop.isRunning) {
                    loop.stop()
                    toggle.text = "Iniciar"
                } else {
                    loop.start()
                    toggle.text = "Parar"
                }
            }
            loop.start()
        }

        private fun append(line: PingLine) {
            val kind = if (line.outcome.success) OK else if (line.outcome.failure == PingFailure.TIMED_OUT) FAIL else WARN
            val span = spanOf(ConsoleLine(PingLineFormatter.format(line.at, line.host, line.outcome), kind, line.host))
            val atBottom = scroll.getChildAt(0).let { child ->
                child == null || child.bottom - (scroll.height + scroll.scrollY) < ui.dp(40)
            }
            text.append(span)
            output.append(span)
            lineCount++
            if (lineCount > CONSOLE_VISIBLE_MAX) {
                var cut = 0
                repeat(CONSOLE_TRIM) {
                    val idx = text.indexOf('\n', cut)
                    if (idx >= 0) cut = idx + 1
                }
                text.delete(0, cut)
                lineCount -= CONSOLE_TRIM
                output.text = text
            }
            if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        fun close() {
            if (closed) return
            closed = true
            loop.stop()
            terminals.remove(row.onu.id)
            if (dialog.isShowing) dialog.dismiss()
            releaseBusyIfIdle()
        }
    }
}
