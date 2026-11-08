package br.com.pingreal

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Tipografia: Barlow na interface, Barlow Semi Condensed nos números, IBM Plex Mono no console. */
object Fonts {
    lateinit var regular: Typeface
        private set
    lateinit var medium: Typeface
        private set
    lateinit var semibold: Typeface
        private set
    lateinit var bold: Typeface
        private set
    lateinit var number: Typeface
        private set
    lateinit var numberBold: Typeface
        private set
    lateinit var code: Typeface
        private set

    fun init(context: Context) {
        if (::regular.isInitialized) return
        val r = context.resources
        regular = load(r, R.font.barlow_regular, Typeface.DEFAULT)
        medium = load(r, R.font.barlow_medium, Typeface.DEFAULT)
        semibold = load(r, R.font.barlow_semibold, Typeface.DEFAULT_BOLD)
        bold = load(r, R.font.barlow_bold, Typeface.DEFAULT_BOLD)
        number = load(r, R.font.barlow_sc_medium, Typeface.DEFAULT)
        numberBold = load(r, R.font.barlow_sc_semibold, Typeface.DEFAULT_BOLD)
        code = load(r, R.font.plex_mono_regular, Typeface.MONOSPACE)
    }

    private fun load(r: Resources, id: Int, fallback: Typeface): Typeface =
        try { r.getFont(id) } catch (_: Exception) { fallback }
}

/** Paleta e construtores de view usados por todas as telas (montadas em código). */
class UiKit(private val context: Context) {

    init { Fonts.init(context) }

    val bg = Palette.bg
    val panel = Palette.panel
    val panelHigh = Palette.panelHigh
    val panelStroke = Palette.panelStroke
    val consoleBg = Palette.consoleBg
    val accent = Palette.accent
    val onAccent = Palette.onAccent
    val accentInk = Palette.accentInk
    val green = Palette.green
    val cyan = Palette.cyan
    val violet = Palette.violet
    val orange = Palette.orange
    val amber = Palette.amber
    val red = Palette.red
    val white = Palette.white
    val text = Palette.text
    val muted = Palette.muted

    /** Cores das curvas quando há vários alvos. */
    val series = Palette.series

    private val density = context.resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density).toInt()
    fun dpf(value: Float): Float = value * density

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // ------------------------------------------------------------------ formas

    fun roundRect(fill: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(radiusDp)
            if (stroke != 0) setStroke(max(1, dp(1)), stroke)
        }

    /** Retângulo com cantos diferentes: [radiiDp] = superior esquerdo, superior direito, inferior direito, inferior esquerdo. */
    fun cornerRect(fill: Int, radiiDp: FloatArray, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            val tl = dpf(radiiDp[0])
            val tr = dpf(radiiDp[1])
            val br = dpf(radiiDp[2])
            val bl = dpf(radiiDp[3])
            cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
            if (stroke != 0) setStroke(max(1, dp(1)), stroke)
        }

    fun rippled(fill: Int, radiusDp: Float, stroke: Int = 0): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Palette.overlay(40)),
            roundRect(fill, radiusDp, stroke),
            roundRect(Color.WHITE, radiusDp)
        )

    /** Cor translúcida para fundos de selos e indicadores. */
    fun tint(color: Int, alpha: Int = 36): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Fundo suave de uma cor (botões secundários, avisos). */
    fun softFill(color: Int): Int = tint(color, if (Palette.isDark) 38 else 46)

    // ------------------------------------------------------------------ texto

    /**
     * Texto da interface. [bold] = Barlow SemiBold; [mono] = números (Barlow Semi Condensed com algarismos
     * tabulares, para os valores não "dançarem").
     */
    fun tv(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
        spacing: Float = 0f
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = when {
            mono && bold -> Fonts.numberBold
            mono -> Fonts.number
            bold -> Fonts.semibold
            else -> Fonts.regular
        }
        if (mono) fontFeatureSettings = "tnum"
        letterSpacing = spacing
    }

    /** Texto em Barlow Medium (rótulos, abas). */
    fun medium(text: String, size: Float, color: Int): TextView =
        tv(text, size, color).apply { typeface = Fonts.medium }

    /** Fonte do console (monoespaçada de verdade, para alinhar colunas). */
    fun code(view: TextView) {
        view.typeface = Fonts.code
        view.fontFeatureSettings = null
        view.letterSpacing = 0f
    }

    /** Rótulo de seção: frase normal, cinza, peso médio. */
    fun label(text: String): TextView =
        medium(text.lowercase(Locale("pt", "BR")).replaceFirstChar { it.uppercase() }, 13f, muted)

    /** Título de cartão. */
    fun title(text: String): TextView = tv(text, 17f, white, bold = true)

    /** Coluna "rótulo pequeno em cima, valor embaixo". */
    fun metric(label: String, value: TextView): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(medium(label, 11f, muted))
        addView(value, lp(-2, -2).apply { topMargin = dp(1) })
    }

    // ------------------------------------------------------------------ blocos

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(panel, 20f, panelStroke)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    /** Fundo de campos de texto. */
    fun field(): GradientDrawable = roundRect(panel, 14f, panelStroke)

    // ------------------------------------------------------------------ botões

    fun button(label: String, fill: Int, textColor: Int, stroke: Int = 0, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Fonts.semibold
            letterSpacing = 0.01f
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
            style(this, fill, textColor, stroke)
        }

    /** Cantos próprios de um botão (ex.: as duas metades da barra Iniciar | Traceroute). */
    fun corners(button: Button, radiiDp: FloatArray) {
        button.tag = radiiDp
    }

    /** Troca as cores de um botão já criado (ex.: Iniciar ↔ Parar). */
    fun style(button: Button, fill: Int, textColor: Int, stroke: Int = 0) {
        button.setTextColor(textColor)
        val radii = button.tag as? FloatArray
        val shape = if (radii != null) cornerRect(fill, radii, stroke) else roundRect(fill, 16f, stroke)
        val mask = if (radii != null) cornerRect(Color.WHITE, radii) else roundRect(Color.WHITE, 16f)
        button.background = RippleDrawable(ColorStateList.valueOf(Palette.overlay(40)), shape, mask)
    }

    /** Botão principal (amarelo da marca). */
    fun primary(button: Button) = style(button, accent, onAccent)

    /** Botão de parar (vermelho suave). */
    fun danger(button: Button) = style(button, softFill(red), red)

    /** Botão secundário (amarelo suave). */
    fun secondary(button: Button) = style(button, softFill(accent), accentInk)

    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }

    /** Ação de texto pequena (ex.: Enviar, Limpar). */
    fun textAction(label: String, onClick: () -> Unit): TextView =
        tv(label, 13f, accentInk, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rippled(Color.TRANSPARENT, 10f)
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun formatMs(ms: Double): String = br.com.pingreal.formatMs(ms)

    fun formatMbps(v: Double): String = br.com.pingreal.formatMbps(v)
}

/** Ícones desenhados em código (traço de 1,8 dp, cantos redondos), num quadrado centralizado de [sizeDp]. */
class IconView(context: Context, kind: Int, private val sizeDp: Float = 24f) : View(context) {

    companion object {
        const val TERMINAL = 0
        const val DIAGNOSTIC = 1
        const val HOUSE = 2
        const val STAR = 3
        const val MORE = 4
        const val CHEVRON = 5
        const val EXPAND_ALL = 6
        const val COLLAPSE_ALL = 7
        const val PLUS = 9
    }

    var kind: Int = kind
        set(value) { field = value; invalidate() }

    var color: Int = Palette.muted
        set(value) { field = value; invalidate() }

    var filled = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = min(sizeDp * density, min(width, height).toFloat())
        val l = (width - s) / 2f
        val t = (height - s) / 2f
        fun x(f: Float) = l + f * s
        fun y(f: Float) = t + f * s

        paint.color = color
        paint.style = Paint.Style.STROKE
        val inset = paint.strokeWidth

        when (kind) {
            TERMINAL -> {
                val r = s * 0.2f
                rect.set(l + inset, y(0.12f), l + s - inset, y(0.88f))
                canvas.drawRoundRect(rect, r, r, paint)
                path.reset()
                path.moveTo(x(0.27f), y(0.37f))
                path.lineTo(x(0.43f), y(0.50f))
                path.lineTo(x(0.27f), y(0.63f))
                canvas.drawPath(path, paint)
                canvas.drawLine(x(0.52f), y(0.64f), x(0.72f), y(0.64f), paint)
            }
            HOUSE -> {
                path.reset()
                path.moveTo(x(0.10f), y(0.50f))
                path.lineTo(x(0.50f), y(0.14f))
                path.lineTo(x(0.90f), y(0.50f))
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(x(0.22f), y(0.43f))
                path.lineTo(x(0.22f), y(0.86f))
                path.lineTo(x(0.78f), y(0.86f))
                path.lineTo(x(0.78f), y(0.43f))
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(x(0.42f), y(0.86f))
                path.lineTo(x(0.42f), y(0.62f))
                path.lineTo(x(0.58f), y(0.62f))
                path.lineTo(x(0.58f), y(0.86f))
                canvas.drawPath(path, paint)
            }
            DIAGNOSTIC -> {
                canvas.drawCircle(x(0.5f), y(0.5f), s / 2f - inset, paint)
                path.reset()
                path.moveTo(x(0.16f), y(0.52f))
                path.lineTo(x(0.34f), y(0.52f))
                path.lineTo(x(0.43f), y(0.30f))
                path.lineTo(x(0.55f), y(0.74f))
                path.lineTo(x(0.64f), y(0.52f))
                path.lineTo(x(0.84f), y(0.52f))
                canvas.drawPath(path, paint)
            }
            STAR -> {
                path.reset()
                for (i in 0 until 10) {
                    val angle = Math.toRadians((-90 + i * 36).toDouble())
                    val r = if (i % 2 == 0) 0.42f else 0.18f
                    val px = x(0.5f + r * cos(angle).toFloat())
                    val py = y(0.53f + r * sin(angle).toFloat())
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                if (filled) paint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, paint)
            }
            MORE -> {
                paint.style = Paint.Style.FILL
                val r = 2f * density
                for (f in floatArrayOf(0.24f, 0.5f, 0.76f)) canvas.drawCircle(x(0.5f), y(f), r, paint)
            }
            CHEVRON -> {
                path.reset()
                path.moveTo(x(0.28f), y(0.40f))
                path.lineTo(x(0.50f), y(0.62f))
                path.lineTo(x(0.72f), y(0.40f))
                canvas.drawPath(path, paint)
            }
            PLUS -> {
                canvas.drawLine(x(0.22f), y(0.5f), x(0.78f), y(0.5f), paint)
                canvas.drawLine(x(0.5f), y(0.22f), x(0.5f), y(0.78f), paint)
            }
            EXPAND_ALL, COLLAPSE_ALL -> {
                // Duas setas: para fora (expandir tudo) ou para dentro (recolher tudo).
                path.reset()
                if (kind == EXPAND_ALL) {
                    path.moveTo(x(0.30f), y(0.34f)); path.lineTo(x(0.50f), y(0.16f)); path.lineTo(x(0.70f), y(0.34f))
                    path.moveTo(x(0.30f), y(0.66f)); path.lineTo(x(0.50f), y(0.84f)); path.lineTo(x(0.70f), y(0.66f))
                } else {
                    path.moveTo(x(0.30f), y(0.16f)); path.lineTo(x(0.50f), y(0.34f)); path.lineTo(x(0.70f), y(0.16f))
                    path.moveTo(x(0.30f), y(0.84f)); path.lineTo(x(0.50f), y(0.66f)); path.lineTo(x(0.70f), y(0.84f))
                }
                canvas.drawPath(path, paint)
                canvas.drawLine(x(0.22f), y(0.5f), x(0.78f), y(0.5f), paint)
            }
        }
    }
}

/** A marca: um splitter, uma fibra se dividindo em quatro com uma saída acesa (o mesmo desenho do ícone do app). */
class BrandMarkView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val outputs = floatArrayOf(30f, 62f, 78f, 46f)  // a acesa (46) por último, por cima

    override fun onDraw(canvas: Canvas) {
        // Coordenadas do ícone (área de 108).
        val u = min(width, height) / 66f
        fun x(v: Float) = width / 2f + (v - 52.25f) * u
        fun y(v: Float) = height / 2f + (v - 54f) * u
        val ink = Palette.white
        val dim = Color.argb(64, Color.red(ink), Color.green(ink), Color.blue(ink))
        val dimDot = Color.argb(128, Color.red(ink), Color.green(ink), Color.blue(ink))

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f * u
        for (o in outputs) {
            path.reset()
            path.moveTo(x(30f), y(54f))
            path.cubicTo(x(50f), y(54f), x(54f), y(o), x(76f), y(o))
            paint.color = if (o == 46f) ink else dim
            canvas.drawPath(path, paint)
        }

        paint.style = Paint.Style.FILL
        if (Palette.isDark) {
            paint.color = Color.WHITE
            paint.shader = RadialGradient(x(76f), y(46f), 10f * u,
                Color.argb(204, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(x(76f), y(46f), 10f * u, paint)
            paint.shader = null
        }
        for (o in outputs) {
            paint.color = if (o == 46f) ink else dimDot
            canvas.drawCircle(x(76f), y(o), (if (o == 46f) 5f else 4f) * u, paint)
        }
        paint.color = ink
        canvas.drawCircle(x(30f), y(54f), 6.5f * u, paint)
    }
}

/** Selo redondo de uma etapa do diagnóstico: anel (pendente), arco girando (rodando), ✓, !, ✕ ou –. */
class StatusBadgeView(context: Context) : View(context) {

    var status: Status = Status.PENDING
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * density
    }
    private val oval = RectF()
    private val path = Path()

    private fun tint(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val s = r * 2f
        fun x(f: Float) = cx - r + f * s
        fun y(f: Float) = cy - r + f * s

        when (status) {
            Status.PENDING -> {
                stroke.color = tint(Palette.muted, 120)
                canvas.drawCircle(cx, cy, r - stroke.strokeWidth, stroke)
            }
            Status.RUNNING -> {
                stroke.color = Palette.overlay(30)
                canvas.drawCircle(cx, cy, r - stroke.strokeWidth, stroke)
                stroke.color = Palette.accentInk
                val i = stroke.strokeWidth
                oval.set(cx - r + i, cy - r + i, cx + r - i, cy + r - i)
                val start = (System.currentTimeMillis() % 900L) / 900f * 360f
                canvas.drawArc(oval, start, 100f, false, stroke)
                postInvalidateOnAnimation()
            }
            Status.OK -> {
                fill.color = tint(Palette.green, 46)
                canvas.drawCircle(cx, cy, r, fill)
                stroke.color = Palette.green
                path.reset()
                path.moveTo(x(0.30f), y(0.52f))
                path.lineTo(x(0.44f), y(0.66f))
                path.lineTo(x(0.70f), y(0.38f))
                canvas.drawPath(path, stroke)
            }
            Status.WARN -> {
                fill.color = tint(Palette.orange, 46)
                canvas.drawCircle(cx, cy, r, fill)
                stroke.color = Palette.orange
                canvas.drawLine(x(0.5f), y(0.28f), x(0.5f), y(0.56f), stroke)
                fill.color = Palette.orange
                canvas.drawCircle(x(0.5f), y(0.71f), stroke.strokeWidth * 0.75f, fill)
            }
            Status.FAIL -> {
                fill.color = tint(Palette.red, 46)
                canvas.drawCircle(cx, cy, r, fill)
                stroke.color = Palette.red
                canvas.drawLine(x(0.34f), y(0.34f), x(0.66f), y(0.66f), stroke)
                canvas.drawLine(x(0.66f), y(0.34f), x(0.34f), y(0.66f), stroke)
            }
            Status.SKIP -> {
                fill.color = Palette.overlay(16)
                canvas.drawCircle(cx, cy, r, fill)
                stroke.color = Palette.muted
                canvas.drawLine(x(0.36f), y(0.5f), x(0.64f), y(0.5f), stroke)
            }
        }
    }
}

/** Barra horizontal dividida em trechos proporcionais (ex.: online / instável / sem resposta / aguardando). */
class SegmentBar(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var values = IntArray(0)
    private var colors = IntArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()

    fun set(values: IntArray, colors: IntArray) {
        this.values = values
        this.colors = colors
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.color = Palette.overlay(22)
        canvas.drawRect(rect, paint)

        val total = values.sum()
        if (total > 0) {
            val gap = 2f * density
            var x = 0f
            for (i in values.indices) {
                val v = values[i]
                if (v <= 0) continue
                val segW = w * v / total
                paint.color = colors[i]
                val right = if (x + segW >= w - 0.5f) w else x + segW - gap
                if (right > x) canvas.drawRect(x, 0f, right, h, paint)
                x += segW
            }
        }
        canvas.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(w, resolveSize((8 * density).toInt(), heightMeasureSpec))
    }
}

/**
 * Velocímetro do teste de velocidade: arco de 240° com escala não linear (como a do Speedtest),
 * a ponta do arco acesa e o valor grande no centro.
 */
class GaugeView(context: Context) : View(context) {

    companion object {
        private val MARKS = floatArrayOf(0f, 5f, 10f, 50f, 100f, 250f, 500f, 750f, 1000f)
        private const val START_ANGLE = 150f
        private const val SWEEP = 240f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var phase = ""
    private var valueText = "—"
    private var unitText = "Mbps"
    private var accent = Palette.cyan
    private var target = 0f
    private var shown = 0f
    private var lastFrame = 0L

    private val arcRect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.overlay(22)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
        typeface = Fonts.number
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.white
        textSize = dp(46f)
        textAlign = Paint.Align.CENTER
        typeface = Fonts.numberBold
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
        typeface = Fonts.medium
    }
    private val phasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
        typeface = Fonts.semibold
    }

    /** Fase atual (ex.: "Download") e a cor do arco. O arco volta ao zero. */
    fun setPhase(label: String, color: Int) {
        phase = label
        accent = color
        target = 0f
        valueText = "—"
        unitText = "Mbps"
        animateNow()
    }

    fun setSpeed(mbps: Double) {
        target = mbps.toFloat().coerceIn(0f, MARKS.last())
        valueText = if (mbps >= 100) mbps.toInt().toString() else String.format(Locale.US, "%.1f", mbps)
        unitText = "Mbps"
        animateNow()
    }

    /** Texto no centro sem mexer no arco (ex.: latência em ms). */
    fun setText(value: String, unit: String) {
        valueText = value
        unitText = unit
        invalidate()
    }

    /** Estado final: arco no zero e um texto no centro. */
    fun settle(label: String, value: String, unit: String) {
        phase = label
        target = 0f
        valueText = value
        unitText = unit
        animateNow()
    }

    private fun animateNow() {
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    /** Posição de 0 a 1 no arco: cada intervalo entre marcas ocupa o mesmo ângulo. */
    private fun fractionOf(v: Float): Float {
        val segments = MARKS.size - 1
        for (i in 0 until segments) {
            val a = MARKS[i]
            val b = MARKS[i + 1]
            if (v <= b) return (i + (v - a) / (b - a)) / segments
        }
        return 1f
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 100L)
        lastFrame = now
        val diff = target - shown
        val animating = abs(diff) > 0.05f
        shown = if (animating) shown + diff * (1f - exp(-dt / 160f)) else target

        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = dp(12f)
        val bottomRoom = dp(8f)
        val r = min((w - 2 * stroke) / 2f, (h - stroke - bottomRoom) / 1.5f)
        val cx = w / 2f
        val cy = stroke + r

        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        arcPaint.color = accent
        arcRect.set(cx - r, cy - r, cx + r, cy + r)

        canvas.drawArc(arcRect, START_ANGLE, SWEEP, false, trackPaint)
        val frac = fractionOf(shown)
        if (frac > 0.002f) {
            canvas.drawArc(arcRect, START_ANGLE, SWEEP * frac, false, arcPaint)
            // Ponta acesa
            val a = Math.toRadians((START_ANGLE + SWEEP * frac).toDouble())
            val hx = cx + cos(a).toFloat() * r
            val hy = cy + sin(a).toFloat() * r
            headPaint.color = Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawCircle(hx, hy, stroke * 1.05f, headPaint)
            headPaint.color = Palette.white
            canvas.drawCircle(hx, hy, stroke * 0.32f, headPaint)
        }

        // Escala
        val labelR = r - stroke - dp(12f)
        for ((i, mark) in MARKS.withIndex()) {
            val angle = Math.toRadians((START_ANGLE + SWEEP * i / (MARKS.size - 1)).toDouble())
            val x = cx + cos(angle).toFloat() * labelR
            val y = cy + sin(angle).toFloat() * labelR + scalePaint.textSize / 3f
            canvas.drawText(mark.toInt().toString(), x, y, scalePaint)
        }

        // Textos
        phasePaint.color = accent
        canvas.drawText(phase, cx, cy - r * 0.16f, phasePaint)
        val valueBaseline = cy + r * 0.26f
        canvas.drawText(valueText, cx, valueBaseline, valuePaint)
        canvas.drawText(unitText, cx, valueBaseline + dp(20f), unitPaint)

        if (animating) postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val h = (210 * density).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }
}
