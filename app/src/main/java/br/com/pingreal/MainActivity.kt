package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Moldura do app: cabeçalho, abas, barra inferior, rede, compartilhamento e "um teste por vez". */
class MainActivity : Activity(), PageHost {

    private companion object {
        const val STATE_RESUME_PING = "resume_ping"
        const val REQUEST_IMPORT = 2
    }

    private lateinit var ui: UiKit
    private lateinit var terminal: TerminalPage
    private lateinit var diagnostic: DiagnosticPage
    private lateinit var condo: CondoPage
    private lateinit var root: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusDot: GradientDrawable
    private val tabItems = ArrayList<LinearLayout>()
    private val pages = ArrayList<View>()
    private val tabIcons = ArrayList<IconView>()
    private val tabLabels = ArrayList<TextView>()

    private var busy = false
    private var currentTab = 0
    private var importCallback: ((String) -> Unit)? = null
    private var backgroundedAt = 0L
    private var cachedNet = NetInfo.EMPTY

    private val handler = Handler(Looper.getMainLooper())
    private val networkRefresh = Runnable { refreshNetwork() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRefresh()
        override fun onLost(network: Network) = scheduleNetworkRefresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleNetworkRefresh()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = scheduleNetworkRefresh()
    }
    private var callbackRegistered = false

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        val light = Prefs.of(this).getBoolean(Prefs.LIGHT_THEME, false)
        Palette.apply(!light)
        setTheme(if (light) R.style.AppTheme_Light else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        window.statusBarColor = ui.bg
        window.navigationBarColor = ui.bg
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        buildUi()
        setContentView(root)
        applyBarAppearance()
        selectTab(0)
        refreshNetwork()

        // O Android encerrou o app em segundo plano enquanto ele pingava: ao voltar, continua o ping.
        if (savedInstanceState?.getBoolean(STATE_RESUME_PING) == true) {
            handler.post { terminal.resumePing() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (terminal.isPinging()) outState.putBoolean(STATE_RESUME_PING, true)
    }

    override fun onStart() {
        super.onStart()
        if (backgroundedAt != 0L) terminal.onReturnedToForeground(System.currentTimeMillis() - backgroundedAt)
        backgroundedAt = 0L
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        } catch (_: Exception) {
            callbackRegistered = false
        }
        refreshNetwork()
    }

    override fun onStop() {
        backgroundedAt = System.currentTimeMillis()
        if (callbackRegistered) {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
            callbackRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::terminal.isInitialized) terminal.shutdown()
        if (::diagnostic.isInitialized) diagnostic.shutdown()
        if (::condo.isInitialized) condo.shutdown()
        super.onDestroy()
    }

    // =====================================================================
    // PageHost
    // =====================================================================

    override fun acquire(label: String, color: Int): Boolean {
        if (busy) return false
        busy = true
        setStatus(label, color)
        notifyMode()
        return true
    }

    override fun release() {
        busy = false
        setStatus("Pronto", ui.muted)
        notifyMode()
    }

    override fun netInfo(): NetInfo = try {
        readNetworkInfo(this).also { cachedNet = it }
    } catch (_: Exception) {
        cachedNet
    }

    override fun prefs(): SharedPreferences = Prefs.of(this)

    override fun post(block: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    override fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun pickTextFile(onText: (String) -> Unit) {
        importCallback = onText
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/*", "text/csv", "application/csv", "text/comma-separated-values",
                    "application/vnd.ms-excel", "application/octet-stream"
                )
            )
        }
        try {
            startActivityForResult(intent, REQUEST_IMPORT)
        } catch (_: Exception) {
            importCallback = null
            Toast.makeText(this, "Não há um seletor de arquivos disponível.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT) return
        val callback = importCallback
        importCallback = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || callback == null) return
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (text == null) {
                Toast.makeText(this, "Não foi possível ler o arquivo.", Toast.LENGTH_LONG).show()
            } else {
                callback(text)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível ler o arquivo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Enviar"))
    }

    override fun copyText(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PingLab", text))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    override fun shareImage(title: String, body: String, chart: LatencyChartView?) {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "pinglab_relatorio.png")
            FileOutputStream(file).use { out ->
                buildReportBitmap(title, body, chart).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar"))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível gerar a imagem: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun notifyMode() {
        val idle = !busy
        if (::terminal.isInitialized) terminal.onModeChanged(idle)
        if (::diagnostic.isInitialized) diagnostic.onModeChanged(idle)
        if (::condo.isInitialized) condo.onModeChanged(idle)
    }

    private fun setStatus(label: String, color: Int) {
        statusText.text = label
        statusText.setTextColor(color)
        statusDot.setColor(color)
    }

    /** Ícones escuros nas barras do sistema quando o tema é claro. */
    @Suppress("DEPRECATION")
    private fun applyBarAppearance() {
        if (Palette.isDark) return
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(mask, mask)
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun scheduleNetworkRefresh() {
        handler.removeCallbacks(networkRefresh)
        handler.postDelayed(networkRefresh, 300)
    }

    private fun refreshNetwork() {
        if (::terminal.isInitialized) terminal.onNetworkChanged()
    }

    // =====================================================================
    // Interface
    // =====================================================================

    @Suppress("DEPRECATION")
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bg)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        }

        root.setOnApplyWindowInsetsListener { v, insets ->
            val pad = IntArray(4) // esquerda, topo, direita, base
            var imeHeight = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                pad[0] = bars.left
                pad[1] = bars.top
                pad[2] = bars.right
                pad[3] = max(bars.bottom, ime.bottom)
                imeHeight = ime.bottom
            } else {
                @Suppress("DEPRECATION")
                pad[0] = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                pad[1] = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                pad[2] = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                pad[3] = insets.systemWindowInsetBottom
            }
            v.setPadding(ui.dp(16) + pad[0], ui.dp(8) + pad[1], ui.dp(16) + pad[2], ui.dp(8) + pad[3])
            // Com o teclado aberto, a barra de abas só atrapalha.
            if (::nav.isInitialized) nav.visibility = if (imeHeight > ui.dp(120)) View.GONE else View.VISIBLE
            insets
        }

        // ---- cabeçalho: marca centralizada com o status embaixo; opções à direita ----
        val header = FrameLayout(this).apply { setPadding(0, ui.dp(2), 0, ui.dp(12)) }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(BrandMarkView(this@MainActivity), ui.lp(ui.dp(30), ui.dp(30)).apply { marginEnd = ui.dp(10) })
            addView(ui.tv("PingLab", 26f, ui.white).apply {
                typeface = Fonts.bold
                letterSpacing = 0.01f
            })
        }
        statusText = ui.medium("Pronto", 12f, ui.muted)
        statusDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ui.muted)
        }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply { background = statusDot }, ui.lp(ui.dp(6), ui.dp(6)).apply { marginEnd = ui.dp(6) })
            addView(statusText)
        }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(brand, ui.lp(-2, -2))
            addView(status, ui.lp(-2, -2).apply { topMargin = ui.dp(1) })
        }
        header.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val more = IconView(this, IconView.MORE, 22f).apply {
            color = ui.muted
            background = ui.rippled(Color.TRANSPARENT, 22f)
            isClickable = true
            contentDescription = "Opções"
            setOnClickListener { showSettings() }
        }
        header.addView(more, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(header, ui.lp(-1, -2))

        // ---- abas ----
        terminal = TerminalPage(this, ui, this)
        diagnostic = DiagnosticPage(this, ui, this)
        condo = CondoPage(this, ui, this)
        val frame = FrameLayout(this)
        for (page in listOf(terminal.view, diagnostic.view, condo.view)) {
            frame.addView(page, FrameLayout.LayoutParams(-1, -1))
            pages.add(page)
        }
        root.addView(frame, ui.lp(-1, 0, 1f))

        // ---- barra de abas (pílula) ----
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.roundRect(ui.panel, 24f, ui.panelStroke)
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        nav.addView(navItem(0, IconView.TERMINAL, "Terminal"), ui.lp(0, -2, 1f))
        nav.addView(navItem(1, IconView.DIAGNOSTIC, "Diagnóstico"), ui.lp(0, -2, 1f).apply { marginStart = ui.dp(4) })
        nav.addView(navItem(2, IconView.HOUSE, "Condomínio"), ui.lp(0, -2, 1f).apply { marginStart = ui.dp(4) })
        root.addView(nav, ui.lp(-1, -2).apply { topMargin = ui.dp(10) })
    }

    private fun navItem(index: Int, kind: Int, label: String): LinearLayout {
        val icon = IconView(this, kind)
        val text = ui.medium(label, 11f, ui.muted).apply { gravity = Gravity.CENTER }
        tabIcons.add(icon)
        tabLabels.add(text)
        val item = LinearLayout(this)
        tabItems.add(item)
        return item.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(5), 0, ui.dp(4))
            isClickable = true
            addView(icon, ui.lp(ui.dp(20), ui.dp(20)))
            addView(text, ui.lp(-2, -2).apply { topMargin = ui.dp(2) })
            setOnClickListener { selectTab(index) }
        }
    }

    private fun selectTab(index: Int) {
        currentTab = index
        for ((i, page) in pages.withIndex()) page.visibility = if (i == index) View.VISIBLE else View.GONE
        for (i in tabIcons.indices) {
            val active = i == index
            val color = if (active) ui.accentInk else ui.muted
            tabItems[i].background = ui.rippled(if (active) ui.softFill(ui.accent) else Color.TRANSPARENT, 20f)
            tabIcons[i].color = color
            tabLabels[i].setTextColor(color)
            tabLabels[i].typeface = if (active) Fonts.semibold else Fonts.medium
        }
        if (index != 0) terminal.hideKeyboard()
    }

    private fun showSettings() {
        val p = prefs()
        val items = arrayOf("Manter a tela ligada durante o ping", "Vibrar quando a conexão cair e voltar", "Tema claro")
        val checked = booleanArrayOf(
            p.getBoolean(Prefs.KEEP_SCREEN, true),
            p.getBoolean(Prefs.VIBRATE, true),
            p.getBoolean(Prefs.LIGHT_THEME, false)
        )
        var themeChanged = false
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(6))
            addView(ui.tv("Opções", 20f, ui.white, bold = true))
            addView(ui.tv("Versão ${versionName()}", 13f, ui.muted).apply { setPadding(0, ui.dp(2), 0, 0) })
        }
        val builder = AlertDialog.Builder(this)
            .setCustomTitle(title)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                val key = when (which) {
                    0 -> Prefs.KEEP_SCREEN
                    1 -> Prefs.VIBRATE
                    else -> Prefs.LIGHT_THEME
                }
                if (which == 2) themeChanged = true
                p.edit().putBoolean(key, isChecked).apply()
                if (which == 0) {
                    if (isChecked) {
                        terminal.applyKeepScreen()
                        condo.applyKeepScreen()
                    } else {
                        setKeepScreenOn(false)
                    }
                }
            }
            .setPositiveButton("OK") { _, _ -> if (themeChanged) recreate() }
            .setOnCancelListener { if (themeChanged) recreate() }
        // Importar ONUs só faz sentido na aba Condomínio.
        if (currentTab == 2) builder.setNeutralButton("Importar ONUs") { _, _ -> condo.startImport() }
        builder.show()
    }

    // =====================================================================
    // Imagem do relatório
    // =====================================================================

    private fun buildReportBitmap(title: String, body: String, chart: LatencyChartView?): Bitmap {
        val width = 1080
        val pad = 48
        val inner = width - 2 * pad
        val withChart = chart != null && chart.width > 0 && chart.height > 0

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ui.white
            textSize = 52f
            typeface = Fonts.bold
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ui.muted
            textSize = 30f
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ui.white
            textSize = 30f
            typeface = Fonts.code
        }
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, inner)
            .setLineSpacing(8f, 1f)
            .build()

        val scale = if (withChart) inner.toFloat() / chart!!.width else 1f
        val scaledChartH = if (withChart) (chart!!.height * scale).toInt() else 0

        var y = pad + 52
        val titleBaseline = y.toFloat()
        y += 44
        val dateBaseline = y.toFloat()
        y += 36
        val chartTop = y
        if (withChart) y += scaledChartH + 36
        val bodyTop = y
        val totalH = bodyTop + layout.height + pad

        val bitmap = Bitmap.createBitmap(width, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ui.bg)
        canvas.drawText(title, pad.toFloat(), titleBaseline, titlePaint)
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())
        canvas.drawText(date, pad.toFloat(), dateBaseline, datePaint)

        if (withChart) {
            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.panel }
            canvas.drawRoundRect(
                RectF(pad.toFloat(), chartTop.toFloat(), (pad + inner).toFloat(), (chartTop + scaledChartH).toFloat()),
                28f, 28f, panelPaint
            )
            canvas.save()
            canvas.translate(pad.toFloat(), chartTop.toFloat())
            canvas.scale(scale, scale)
            chart!!.draw(canvas)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(pad.toFloat(), bodyTop.toFloat())
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
