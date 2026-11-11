package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * A tela de chat com o Gemini. Abre por cima do app a partir do Diagnóstico, manda o diagnóstico como contexto
 * inicial e segue como uma conversa livre de assistente técnico de redes. Fica só na memória.
 */
class ChatPage(
    private val activity: Activity,
    private val ui: UiKit,
    private val host: PageHost,
    private val keys: SecureKeyStore,
    private val client: GeminiClient,
    private val currentSnapshot: () -> DiagnosticSnapshot?,
    private val onClose: () -> Unit
) {
    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ui.bg)
        isClickable = true // por cima das abas: não deixa o toque passar
    }

    private val session = ChatSession()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var busy = false
    private var closed = false
    private var pendingSnapshot: DiagnosticSnapshot? = null
    private var waitingStart = 0L
    private var lastViaMobile = false

    private lateinit var subtitle: TextView
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var setupBox: LinearLayout
    private lateinit var keyInput: EditText
    private lateinit var setupStatus: TextView
    private lateinit var setupButtons: LinearLayout
    private lateinit var savedKeyRow: LinearLayout
    private lateinit var savedKeyText: TextView
    private lateinit var inputArea: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: android.widget.Button
    private var waitingView: TextView? = null
    private var countdown: Runnable? = null

    init {
        build()
        updateSubtitle()
    }

    // =====================================================================
    // API usada pela activity
    // =====================================================================

    /** Abre o chat com [snapshot]. Se já há conversa, pergunta se continua, anexa ou começa outra. */
    fun open(snapshot: DiagnosticSnapshot) {
        if (session.isEmpty) {
            startFresh(snapshot)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Já existe uma conversa")
            .setItems(arrayOf("Continuar a conversa", "Anexar este diagnóstico à conversa", "Começar outra conversa")) { _, which ->
                when (which) {
                    1 -> attach(snapshot)
                    2 -> startFresh(snapshot)
                    else -> Unit
                }
            }
            .setOnCancelListener { }
            .show()
    }

    fun shutdown() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    // =====================================================================
    // Interface
    // =====================================================================

    private fun build() {
        // ---- barra do topo ----
        val back = IconView(activity, IconView.CHEVRON, 24f).apply {
            rotation = 90f // a seta para baixo vira seta para a esquerda
            color = ui.text
            background = ui.rippled(android.graphics.Color.TRANSPARENT, 22f)
            isClickable = true
            contentDescription = "Voltar"
            setOnClickListener { onClose() }
        }
        subtitle = ui.medium("", 12f, ui.muted)
        val titles = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.title("Assistente"))
            addView(subtitle)
        }
        val more = IconView(activity, IconView.MORE, 22f).apply {
            color = ui.muted
            background = ui.rippled(android.graphics.Color.TRANSPARENT, 22f)
            isClickable = true
            contentDescription = "Opções do chat"
            setOnClickListener { showMenu() }
        }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, ui.lp(ui.dp(44), ui.dp(44)))
            addView(titles, ui.lp(0, -2, 1f).apply { marginStart = ui.dp(4) })
            addView(more, ui.lp(ui.dp(44), ui.dp(44)))
        }
        view.addView(top, ui.lp(-1, -2))

        // ---- conversa ----
        list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        buildSetupBox()
        list.addView(setupBox, ui.lp(-1, -2).apply { bottomMargin = ui.dp(10) })
        scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        view.addView(scroll, ui.lp(-1, 0, 1f).apply { topMargin = ui.dp(8) })

        // ---- campo de mensagem ----
        inputArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val attach = ui.textAction("+ Enviar outro diagnóstico") { attachLatest() }
        inputArea.addView(attach, ui.lp(-2, -2))
        input = EditText(activity).apply {
            textSize = 15f
            typeface = Fonts.regular
            setTextColor(ui.white)
            setHintTextColor(ui.muted)
            hint = "Pergunte sobre o diagnóstico ou sobre redes"
            background = ui.field()
            setPadding(ui.dp(14), ui.dp(8), ui.dp(10), ui.dp(8))
            minHeight = ui.dp(46)
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        sendButton = ui.button("Enviar", ui.accent, ui.onAccent) { sendTyped() }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(input, ui.lp(0, -2, 1f))
            addView(sendButton, ui.lp(ui.dp(84), ui.dp(46)).apply { marginStart = ui.dp(8) })
        }
        inputArea.addView(row, ui.lp(-1, -2))
        view.addView(inputArea, ui.lp(-1, -2).apply { topMargin = ui.dp(6) })
    }

    private fun buildSetupBox() {
        setupBox = ui.card()
        setupBox.addView(ui.title("Chave do Gemini"))
        setupBox.addView(
            ui.tv(
                "O assistente usa a API do Google Gemini com a sua própria chave gratuita. A chave fica guardada só neste aparelho, cifrada pelo Android Keystore, e não vai para o código nem para o GitHub. No plano gratuito, o Google pode usar o conteúdo enviado para melhorar os produtos dele.",
                13f, ui.muted
            ).apply { setLineSpacing(0f, 1.1f) },
            ui.lp(-1, -2).apply { topMargin = ui.dp(6) }
        )
        savedKeyText = ui.medium("", 13f, ui.text)
        val remove = ui.textAction("Remover chave") {
            keys.clear()
            refreshSavedKey()
            setStatus("Chave removida.", false)
        }
        savedKeyRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(savedKeyText, ui.lp(0, -2, 1f))
            addView(remove)
        }
        setupBox.addView(savedKeyRow, ui.lp(-1, -2).apply { topMargin = ui.dp(8) })

        keyInput = EditText(activity).apply {
            setSingleLine(true)
            textSize = 14f
            typeface = Fonts.code
            setTextColor(ui.white)
            setHintTextColor(ui.muted)
            hint = "Cole a chave aqui"
            background = ui.field()
            setPadding(ui.dp(14), 0, ui.dp(10), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        setupBox.addView(keyInput, ui.lp(-1, ui.dp(46)).apply { topMargin = ui.dp(10) })

        val create = ui.button("Criar chave grátis", ui.softFill(ui.accent), ui.accentInk) {
            host.openUrl("https://aistudio.google.com/apikey")
        }
        val save = ui.button("Salvar e testar", ui.accent, ui.onAccent) { saveAndTest(keyInput.text.toString()) }
        setupButtons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(create, ui.lp(0, ui.dp(46), 1f))
            addView(save, ui.lp(0, ui.dp(46), 1f).apply { marginStart = ui.dp(8) })
        }
        setupBox.addView(setupButtons, ui.lp(-1, -2).apply { topMargin = ui.dp(8) })

        setupStatus = ui.medium("", 13f, ui.muted).apply { visibility = View.GONE }
        setupBox.addView(setupStatus, ui.lp(-1, -2).apply { topMargin = ui.dp(8) })
        setupBox.visibility = View.GONE
    }

    private fun refreshSavedKey() {
        val key = keys.load()
        savedKeyRow.visibility = if (key == null) View.GONE else View.VISIBLE
        if (key != null) savedKeyText.text = "Chave salva: ${SecureKeyStore.mask(key)}"
    }

    private fun showSetup() {
        refreshSavedKey()
        setupBox.visibility = View.VISIBLE
        inputArea.visibility = View.GONE
        scrollToTop()
    }

    private fun hideSetup() {
        setupBox.visibility = View.GONE
        inputArea.visibility = View.VISIBLE
        keyInput.setText("")
        setupStatus.visibility = View.GONE
    }

    private fun setStatus(text: String, error: Boolean) {
        setupStatus.text = text
        setupStatus.setTextColor(if (error) ui.red else ui.muted)
        setupStatus.visibility = View.VISIBLE
    }

    private fun model(): String = host.prefs().getString(Prefs.AI_MODEL, null) ?: GeminiConfig.DEFAULT_MODEL

    private fun includePublicIp(): Boolean = host.prefs().getBoolean(Prefs.AI_INCLUDE_PUBLIC_IP, false)

    private fun updateSubtitle() {
        val m = model()
        subtitle.text = if (client.thinkingConfig(m) != null) "$m · raciocínio alto" else m
    }

    // =====================================================================
    // Mensagens na tela
    // =====================================================================

    private fun maxBubbleWidth() = (activity.resources.displayMetrics.widthPixels * 0.84f).toInt()

    private fun addRow(child: View, gravity: Int) {
        val row = FrameLayout(activity)
        row.addView(child, FrameLayout.LayoutParams(-2, -2, gravity))
        list.addView(row, ui.lp(-1, -2).apply { bottomMargin = ui.dp(8) })
        scrollToBottom()
    }

    private fun addUserBubble(text: String) {
        val bubble = ui.tv(text, 15f, ui.white).apply {
            background = ui.roundRect(ui.softFill(ui.accent), 16f)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            maxWidth = maxBubbleWidth()
            setTextIsSelectable(true)
        }
        addRow(bubble, Gravity.END)
    }

    private fun addModelBubble(text: String) {
        val bubble = ui.tv("", 15f, ui.white).apply {
            this.text = MarkdownLite.render(text, ui.panelHigh)
            setLineSpacing(0f, 1.12f)
            background = ui.roundRect(ui.panel, 16f, ui.panelStroke)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            maxWidth = (activity.resources.displayMetrics.widthPixels * 0.94f).toInt()
            setTextIsSelectable(true)
        }
        addRow(bubble, Gravity.START)
    }

    private fun addNote(text: String) {
        val note = ui.medium(text, 12f, ui.muted).apply { gravity = Gravity.CENTER }
        list.addView(note, ui.lp(-1, -2).apply { bottomMargin = ui.dp(8) })
        scrollToBottom()
    }

    /** O diagnóstico enviado aparece recolhido; ao tocar, mostra exatamente o texto que foi para a IA. */
    private fun addSnapshotCard(message: ChatMessage) {
        val body = ui.tv(message.text, 12f, ui.text).apply {
            ui.code(this)
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(10))
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        val head = ui.medium("▸ ${message.snapshotLabel} enviado", 13f, ui.accentInk).apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            isClickable = true
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                text = (if (open) "▾ " else "▸ ") + "${message.snapshotLabel} enviado"
                scrollToBottom()
            }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.roundRect(ui.softFill(ui.accent), 14f)
            addView(head)
            addView(body)
        }
        val row = FrameLayout(activity)
        row.addView(card, FrameLayout.LayoutParams(maxBubbleWidth(), -2, Gravity.END))
        list.addView(row, ui.lp(-1, -2).apply { bottomMargin = ui.dp(8) })
        scrollToBottom()
    }

    /** Bolha de erro com ações (por exemplo "Tentar de novo"). Cada ação é um rótulo e o que fazer. */
    private fun addError(text: String, actions: List<Pair<String, () -> Unit>>): LinearLayout {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.roundRect(ui.softFill(ui.red), 14f)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(6))
        }
        box.addView(ui.tv(text, 14f, ui.white).apply { setTextIsSelectable(true) })
        val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in actions) actionRow.addView(ui.textAction(label) { action() })
        box.addView(actionRow, ui.lp(-2, -2).apply { topMargin = ui.dp(2) })
        val row = FrameLayout(activity)
        row.addView(box, FrameLayout.LayoutParams(maxBubbleWidth(), -2, Gravity.START))
        list.addView(row, ui.lp(-1, -2).apply { bottomMargin = ui.dp(8) })
        scrollToBottom()
        errorBoxes.add(row)
        return box
    }

    private val errorBoxes = ArrayList<View>()

    private fun clearErrors() {
        for (row in errorBoxes) list.removeView(row)
        errorBoxes.clear()
    }

    private fun scrollToBottom() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun scrollToTop() = scroll.post { scroll.fullScroll(View.FOCUS_UP) }

    private fun rebuildList() {
        list.removeAllViews()
        list.addView(setupBox, ui.lp(-1, -2).apply { bottomMargin = ui.dp(10) })
        errorBoxes.clear()
        waitingView = null
        for (m in session.messages) {
            when {
                m.snapshotAt != null -> addSnapshotCard(m)
                m.role == Role.USER -> addUserBubble(m.text)
                else -> addModelBubble(m.text)
            }
        }
    }

    // =====================================================================
    // Fluxo da conversa
    // =====================================================================

    private fun startFresh(snapshot: DiagnosticSnapshot) {
        cancelCountdown()
        session.clear()
        rebuildList()
        busy = false
        updateInput()
        if (keys.load() == null) {
            pendingSnapshot = snapshot
            addNote("Para conversar, informe a sua chave do Gemini.")
            showSetup()
        } else {
            pendingSnapshot = null
            hideSetup()
            sendSnapshot(snapshot)
        }
    }

    private fun attach(snapshot: DiagnosticSnapshot) {
        if (session.hasSnapshot(snapshot.atMillis)) {
            Toast.makeText(activity, "Esse diagnóstico já está na conversa", Toast.LENGTH_SHORT).show()
            return
        }
        if (keys.load() == null) {
            pendingSnapshot = snapshot
            showSetup()
            return
        }
        sendSnapshot(snapshot)
    }

    private fun attachLatest() {
        if (busy) return
        val snapshot = currentSnapshot()
        if (snapshot == null) {
            Toast.makeText(activity, "Rode um diagnóstico primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        attach(snapshot)
    }

    private fun sendSnapshot(snapshot: DiagnosticSnapshot) {
        val message = session.addSnapshot(snapshot, includePublicIp())
        addSnapshotCard(message)
        if (!includePublicIp()) addNote("O IP público foi omitido do texto enviado.")
        requestReply()
    }

    private fun sendTyped() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (keys.load() == null) {
            showSetup()
            return
        }
        input.setText("")
        session.addUser(text)
        addUserBubble(text)
        requestReply()
    }

    private fun updateInput() {
        ui.setEnabled(sendButton, !busy)
        input.isEnabled = !busy
        input.alpha = if (busy) 0.6f else 1f
    }

    private fun showWaiting() {
        waitingStart = System.currentTimeMillis()
        val w = ui.medium("Raciocinando… 0 s", 13f, ui.muted).apply { gravity = Gravity.START }
        waitingView = w
        list.addView(w, ui.lp(-1, -2).apply { bottomMargin = ui.dp(8); marginStart = ui.dp(4) })
        scrollToBottom()
        handler.post(object : Runnable {
            override fun run() {
                if (closed || waitingView !== w) return
                w.text = "Raciocinando… ${(System.currentTimeMillis() - waitingStart) / 1000} s"
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun hideWaiting() {
        waitingView?.let { list.removeView(it) }
        waitingView = null
    }

    private fun requestReply() {
        val key = keys.load()
        if (key == null) {
            showSetup()
            return
        }
        clearErrors()
        busy = true
        updateInput()
        showWaiting()
        val model = model()
        val turns = session.turns()
        executor.execute {
            val result = client.generate(key, model, ChatPrompts.SYSTEM, turns)
            host.post { handleResult(result, model) }
        }
    }

    private fun handleResult(result: GeminiResult, model: String) {
        if (closed) return
        busy = false
        hideWaiting()
        updateInput()
        when (result) {
            is GeminiResult.Reply -> {
                session.addModel(result.text)
                addModelBubble(result.text)
                if (result.viaMobile && !lastViaMobile) addNote("Respondeu pelos dados móveis, porque a rede atual está sem internet.")
                if (!result.viaMobile && lastViaMobile) addNote("Voltou a usar a rede atual.")
                lastViaMobile = result.viaMobile
                if (result.truncated) addNote("A resposta foi cortada pelo limite de tamanho do modelo.")
                if (result.thinkingDropped) addNote("Este modelo recusou o ajuste de raciocínio e respondeu no padrão.")
            }
            is GeminiResult.Blocked -> addError(GeminiClient.describeBlock(result.reason), emptyList())
            is GeminiResult.Failure -> showFailure(result, model)
        }
    }

    private fun showFailure(f: GeminiResult.Failure, model: String) {
        val text = GeminiClient.describe(f, model)
        val retry = "Tentar de novo" to { requestReply() }
        when (f.kind) {
            GeminiError.INVALID_KEY -> addError(text, listOf("Trocar a chave" to { showSetup() }))
            GeminiError.MODEL_UNAVAILABLE ->
                addError(text, listOf("Escolher modelo" to { chooseModel { requestReply() } }))
            GeminiError.RATE_LIMIT -> {
                val wait = f.retryAfterSeconds
                if (wait == null || wait <= LONG_WAIT_SECONDS) {
                    // Espera curta (limite por minuto): informa e libera o botão quando o tempo acabar.
                    val box = addError(text, emptyList())
                    startCountdown(box, wait ?: DEFAULT_WAIT_SECONDS)
                } else {
                    // Espera longa (limite diário): contagem não adianta; deixa escolher outro modelo à mão ou tentar depois.
                    addError(text, listOf("Escolher outro modelo" to { chooseModel { requestReply() } }, retry))
                }
            }
            else -> addError(text, listOf(retry))
        }
    }

    /** Limite gratuito: informa e aguarda. O botão só libera quando o tempo pedido pelo Google acaba. */
    private fun startCountdown(box: LinearLayout, seconds: Int) {
        cancelCountdown()
        val label = ui.medium("", 13f, ui.muted)
        val retry = ui.textAction("Tentar de novo") { requestReply() }
        ui.setEnabled(retry, false)
        box.addView(label)
        box.addView(retry)
        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (closed) return
                if (left > 0) {
                    label.text = "Aguarde ${GeminiClient.formatWait(left)} para tentar de novo."
                    left--
                    handler.postDelayed(this, 1000)
                } else {
                    label.text = "Já pode tentar de novo."
                    ui.setEnabled(retry, true)
                }
            }
        }
        countdown = tick
        handler.post(tick)
    }

    private fun cancelCountdown() {
        countdown?.let { handler.removeCallbacks(it) }
        countdown = null
    }

    // =====================================================================
    // Menu, modelo e chave
    // =====================================================================

    private fun showMenu() {
        val ipLabel = if (includePublicIp()) "IP público: incluído (tocar para omitir)" else "IP público: omitido (tocar para incluir)"
        AlertDialog.Builder(activity)
            .setTitle("Assistente")
            .setItems(arrayOf("Nova conversa", "Chave do Gemini", "Trocar modelo", ipLabel)) { _, which ->
                when (which) {
                    0 -> newConversation()
                    1 -> showSetup()
                    2 -> chooseModel(null)
                    else -> {
                        val now = !includePublicIp()
                        host.prefs().edit().putBoolean(Prefs.AI_INCLUDE_PUBLIC_IP, now).apply()
                        addNote(
                            if (now) "O IP público será incluído nos próximos diagnósticos enviados."
                            else "O IP público será omitido nos próximos diagnósticos enviados."
                        )
                    }
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun newConversation() {
        cancelCountdown()
        session.clear()
        pendingSnapshot = null
        busy = false
        rebuildList()
        updateInput()
        hideSetup()
        addNote("Conversa nova. Pergunte o que quiser sobre redes, ou envie um diagnóstico.")
    }

    /** Lista os modelos que a chave enxerga e deixa escolher à mão. Nunca troca sozinho. */
    private fun chooseModel(after: (() -> Unit)?) {
        val key = keys.load()
        if (key == null) {
            showSetup()
            return
        }
        addNote("Buscando os modelos da sua chave…")
        executor.execute {
            val result = client.listModels(key)
            host.post {
                when (result) {
                    is ModelsResult.Failure -> addError(
                        GeminiClient.describe(GeminiResult.Failure(result.kind, result.message), model()),
                        emptyList()
                    )
                    is ModelsResult.Ok -> showModelDialog(result.models, after)
                }
            }
        }
    }

    private fun showModelDialog(models: List<ModelInfo>, after: (() -> Unit)?) {
        if (models.isEmpty()) {
            addNote("A sua chave não enxerga nenhum modelo de conversa.")
            return
        }
        val current = model()
        val labels = models.map { (if (it.id == current) "✔ " else "") + it.displayName + "\n" + it.id }.toTypedArray()
        addNote("Atenção: alguns modelos não estão no plano gratuito e podem responder com erro de limite.")
        AlertDialog.Builder(activity)
            .setTitle("Escolher modelo")
            .setItems(labels) { _, which ->
                val chosen = models[which].id
                host.prefs().edit().putString(Prefs.AI_MODEL, chosen).apply()
                updateSubtitle()
                addNote("Modelo escolhido: $chosen")
                after?.invoke()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setSetupBusy(on: Boolean) {
        setupButtons.alpha = if (on) 0.5f else 1f
        setupButtons.isEnabled = !on
        for (i in 0 until setupButtons.childCount) setupButtons.getChildAt(i).isEnabled = !on
    }

    private fun saveAndTest(raw: String) {
        val key = raw.trim()
        if (!SecureKeyStore.looksLikeKey(key)) {
            setStatus("Cole a chave inteira, sem espaços.", true)
            return
        }
        setSetupBusy(true)
        setStatus("Verificando a chave…", false)
        executor.execute {
            val models = client.listModels(key)
            host.post {
                when (models) {
                    is ModelsResult.Failure -> {
                        setSetupBusy(false)
                        setStatus(GeminiClient.describe(GeminiResult.Failure(models.kind, models.message), model()), true)
                    }
                    is ModelsResult.Ok -> {
                        if (!keys.save(key)) {
                            setSetupBusy(false)
                            setStatus("Este aparelho não conseguiu guardar a chave com segurança (Android Keystore). Nada foi salvo.", true)
                        } else {
                            keyInput.setText("")
                            refreshSavedKey()
                            afterKeySaved(key, models.models)
                        }
                    }
                }
            }
        }
    }

    private fun afterKeySaved(key: String, models: List<ModelInfo>) {
        val wanted = model()
        if (models.none { it.id == wanted }) {
            setSetupBusy(false)
            setStatus("A chave é válida, mas o modelo $wanted não está disponível para ela. Escolha outro à mão.", true)
            showModelDialog(models) { finishSetup() }
            return
        }
        setStatus("Chave válida. Testando o modelo $wanted…", false)
        executor.execute {
            val test = client.generate(key, wanted, "Responda apenas: ok", listOf(ChatTurn("user", "ok")))
            host.post {
                setSetupBusy(false)
                when {
                    test is GeminiResult.Reply -> finishSetup()
                    test is GeminiResult.Failure && test.kind == GeminiError.RATE_LIMIT -> {
                        addNote("Chave salva, mas o limite gratuito está no fim agora. Tente conversar em instantes.")
                        finishSetup()
                    }
                    test is GeminiResult.Failure && test.kind == GeminiError.MODEL_UNAVAILABLE -> {
                        setStatus(GeminiClient.describe(test, wanted), true)
                        showModelDialog(models) { finishSetup() }
                    }
                    test is GeminiResult.Failure -> setStatus(GeminiClient.describe(test, wanted), true)
                    else -> setStatus("O Gemini não respondeu ao teste. Tente de novo.", true)
                }
            }
        }
    }

    private fun finishSetup() {
        hideSetup()
        addNote("Chave salva. Modelo: ${model()}")
        val pending = pendingSnapshot
        pendingSnapshot = null
        if (pending != null) sendSnapshot(pending)
    }

    private companion object {
        const val DEFAULT_WAIT_SECONDS = 60
        const val LONG_WAIT_SECONDS = 600
    }
}
