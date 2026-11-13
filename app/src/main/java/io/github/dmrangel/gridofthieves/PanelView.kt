package io.github.dmrangel.gridofthieves

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.dmrangel.gridofthieves.scan.BaseMatcher
import io.github.dmrangel.gridofthieves.ui.MATCH
import io.github.dmrangel.gridofthieves.ui.Motion
import io.github.dmrangel.gridofthieves.ui.Mr
import io.github.dmrangel.gridofthieves.ui.MrButton
import io.github.dmrangel.gridofthieves.ui.MrSwitch
import io.github.dmrangel.gridofthieves.ui.Segmented
import io.github.dmrangel.gridofthieves.ui.WRAP
import io.github.dmrangel.gridofthieves.ui.anim
import io.github.dmrangel.gridofthieves.ui.dp
import io.github.dmrangel.gridofthieves.ui.dpi
import io.github.dmrangel.gridofthieves.ui.icon
import io.github.dmrangel.gridofthieves.ui.iconButton
import io.github.dmrangel.gridofthieves.ui.lp
import io.github.dmrangel.gridofthieves.ui.onHold
import io.github.dmrangel.gridofthieves.ui.roundRect
import io.github.dmrangel.gridofthieves.ui.spin
import io.github.dmrangel.gridofthieves.ui.text
import java.util.Locale

/** O que o painel pede ao serviço. */
interface PanelHost {
    fun move(dx: Int, dy: Int, multiplier: Int)
    fun zoom(direction: Int, multiplier: Int)
    fun centerGrid()
    fun resetGrid()
    fun setStep(index: Int)
    fun setTouchAdjust(on: Boolean)
    fun toggleGrid()
    fun collapse()
    fun close()
    fun scan()
    fun showBase(number: Int)
    fun openVideos(number: Int)
    fun alignToDetected()
    fun setTab(index: Int)
    fun dragPanel(dx: Float, dy: Float)
}

/**
 * Painel expandido, no visual do Modrinth: card surface-3 com borda, cabeçalho que serve
 * de alça para arrastar, abas em pílula e duas páginas (Ajustar e Base).
 * Tudo montado em código, sem XML nem bibliotecas.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class PanelView(context: Context, private val host: PanelHost) : LinearLayout(context) {
    private val subtitle = context.text(size = 11f, color = Mr.SECONDARY).apply { fontFeatureSettings = "tnum" }
    private val eyeButton = context.iconButton(R.drawable.ic_eye)
    private val tabs = Segmented(context, listOf("Ajustar", "Base"))
    private val pages = FrameLayout(context)
    private val adjustPage: LinearLayout
    private val basePage: LinearLayout
    private var tab = 0

    // Ajustar
    private val sizeValue = context.text(size = 15f, color = Mr.CONTRAST, weight = 600).apply { fontFeatureSettings = "tnum" }
    private val coords = context.text(size = 11f, color = Mr.SECONDARY).apply { fontFeatureSettings = "tnum" }
    private val steps = Segmented(context, listOf("1 px", "5 px", "20 px"), heightDp = 30f)
    private val touchSwitch = MrSwitch(context)

    // Base
    private val scanButton = MrButton(context, MrButton.Style.BRAND, heightDp = 40f).set("Detectar base", R.drawable.ic_scan_search)
    private val resultBox = LinearLayout(context)
    private val hint = context.text(size = 12f, color = Mr.SECONDARY).apply {
        isSingleLine = false
        setLineSpacing(context.dp(3f), 1f)
    }
    private val resultRow = LinearLayout(context)
    private val miniMap = MiniMap(context)
    private val baseNumber = context.text(size = 28f, color = Mr.CONTRAST, weight = 800).apply { fontFeatureSettings = "tnum" }
    private val baseScore = context.text(size = 12f, color = Mr.BRAND, weight = 600)
    private val baseVerdict = context.text(size = 11f, color = Mr.SECONDARY).apply { isSingleLine = false }
    private val altRow = LinearLayout(context)
    private val actionsRow = LinearLayout(context)
    private val alignButton = MrButton(context, MrButton.Style.BASE, heightDp = 32f).set("Alinhar grade", R.drawable.ic_magnet)
    private val videosButton = MrButton(context, MrButton.Style.BASE, heightDp = 32f).set("Vídeos", R.drawable.ic_play)
    private var shownBase = 0

    init {
        orientation = VERTICAL
        val pad = context.dpi(Mr.GAP_MD)
        setPadding(pad, pad, pad, pad)
        background = roundRect(Mr.SURFACE_3, context.dp(Mr.RADIUS_LG), Mr.SURFACE_5, context.dpi(1f))
        elevation = context.dp(8f)

        // Cabeçalho: ícone, título, status e ações. Arrastar por ele move o painel.
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val badge = FrameLayout(context).apply {
                background = roundRect(Mr.BRAND_HIGHLIGHT, context.dp(10f))
                addView(context.icon(R.drawable.ic_grid_3x3, Mr.BRAND, 18f),
                    FrameLayout.LayoutParams(context.dpi(18f), context.dpi(18f), Gravity.CENTER))
            }
            addView(badge, lp(context.dpi(32f), context.dpi(32f)))
            val titles = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(context.text("Grid of Thieves", 15f, Mr.CONTRAST, 800))
                addView(subtitle, lp(WRAP, WRAP).apply { topMargin = context.dpi(3f) })
            }
            addView(titles, lp(0, WRAP, 1f).apply { marginStart = context.dpi(10f) })
            eyeButton.setOnClickListener { host.toggleGrid() }
            addView(eyeButton)
            addView(context.iconButton(R.drawable.ic_minus).apply { setOnClickListener { host.collapse() } })
            addView(context.iconButton(R.drawable.ic_x).apply {
                setOnClickListener { host.close() }
            })
        }
        dragHandle(header)
        addView(header, lp(MATCH, WRAP))

        tabs.onSelect = { switchTab(it) }
        addView(tabs, lp(MATCH, WRAP).apply { topMargin = context.dpi(Mr.GAP_MD) })

        adjustPage = buildAdjustPage()
        basePage = buildBasePage()
        pages.addView(adjustPage, FrameLayout.LayoutParams(MATCH, WRAP))
        pages.addView(basePage, FrameLayout.LayoutParams(MATCH, WRAP))
        basePage.visibility = INVISIBLE // INVISIBLE (não GONE): o painel mede as duas e não muda de tamanho
        addView(pages, lp(MATCH, WRAP).apply { topMargin = context.dpi(Mr.GAP_MD) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragHandle(view: View) {
        var lastX = 0f
        var lastY = 0f
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    host.dragPanel(e.rawX - lastX, e.rawY - lastY)
                    lastX = e.rawX
                    lastY = e.rawY
                }
            }
            true
        }
    }

    private fun buildAdjustPage(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        val c = context
        val gap = c.dpi(6f)
        val key = 36f

        val top = LinearLayout(c).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Direcional: segure para repetir (acelera depois de um tempo).
            val pad = LinearLayout(c).apply {
                orientation = VERTICAL
                fun row(vararg views: View) = LinearLayout(c).apply {
                    orientation = HORIZONTAL
                    views.forEachIndexed { i, v ->
                        addView(v, lp(c.dpi(key), c.dpi(key)).apply { if (i > 0) marginStart = gap })
                    }
                }
                fun spacer() = View(c)
                fun arrow(res: Int, dx: Int, dy: Int) = c.iconButton(res, MrButton.Style.BASE, key, round = false).apply {
                    onHold { m -> host.move(dx, dy, m) }
                }
                val center = c.iconButton(R.drawable.ic_crosshair, MrButton.Style.QUIET, key, round = false).apply {
                    setOnClickListener { host.centerGrid() }
                }
                addView(row(spacer(), arrow(R.drawable.ic_chevron_up, 0, -1), spacer()))
                addView(row(arrow(R.drawable.ic_chevron_left, -1, 0), center, arrow(R.drawable.ic_chevron_right, 1, 0)),
                    lp(WRAP, WRAP).apply { topMargin = gap })
                addView(row(spacer(), arrow(R.drawable.ic_chevron_down, 0, 1), spacer()), lp(WRAP, WRAP).apply { topMargin = gap })
            }
            addView(pad)

            val right = LinearLayout(c).apply {
                orientation = VERTICAL
                val zoomRow = LinearLayout(c).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(c.iconButton(R.drawable.ic_zoom_out, MrButton.Style.BASE, key, round = false).apply {
                        onHold { m -> host.zoom(-1, m) }
                    })
                    val label = LinearLayout(c).apply {
                        orientation = VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(sizeValue, lp(WRAP, WRAP))
                        addView(c.text("px/célula", 10f, Mr.SECONDARY), lp(WRAP, WRAP).apply { topMargin = c.dpi(3f) })
                    }
                    addView(label, lp(0, WRAP, 1f))
                    addView(c.iconButton(R.drawable.ic_zoom_in, MrButton.Style.BASE, key, round = false).apply {
                        onHold { m -> host.zoom(1, m) }
                    })
                }
                addView(zoomRow, lp(MATCH, WRAP))
                addView(c.text("PASSO", 10f, Mr.SECONDARY, 600).apply { letterSpacing = 0.06f },
                    lp(WRAP, WRAP).apply { topMargin = c.dpi(Mr.GAP_MD); bottomMargin = c.dpi(6f) })
                steps.onSelect = { host.setStep(it) }
                addView(steps, lp(MATCH, WRAP))
            }
            addView(right, lp(0, WRAP, 1f).apply { marginStart = c.dpi(Mr.GAP_MD) })
        }
        addView(top, lp(MATCH, WRAP))

        // Ajustar com o dedo.
        val touchRow = LinearLayout(c).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(Mr.SURFACE_2, c.dp(Mr.RADIUS_MD))
            setPadding(c.dpi(10f), c.dpi(8f), c.dpi(10f), c.dpi(8f))
            addView(c.icon(R.drawable.ic_move, Mr.BASE, 18f))
            val texts = LinearLayout(c).apply {
                orientation = VERTICAL
                addView(c.text("Ajustar com o dedo", 13f, Mr.CONTRAST, 600))
                addView(c.text("Arraste e use pinça na tela", 11f, Mr.SECONDARY), lp(WRAP, WRAP).apply { topMargin = c.dpi(2f) })
            }
            addView(texts, lp(0, WRAP, 1f).apply { marginStart = c.dpi(10f) })
            addView(touchSwitch)
            touchSwitch.onChange = { host.setTouchAdjust(it) }
            setOnClickListener { touchSwitch.performClick() }
        }
        addView(touchRow, lp(MATCH, WRAP).apply { topMargin = c.dpi(Mr.GAP_MD) })

        val footer = LinearLayout(c).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(coords, lp(0, WRAP, 1f))
            addView(MrButton(c, MrButton.Style.QUIET, heightDp = 30f).set("Padrão", R.drawable.ic_rotate_ccw).apply {
                setOnClickListener { host.resetGrid() }
            })
        }
        addView(footer, lp(MATCH, WRAP).apply { topMargin = c.dpi(6f) })
    }

    private fun buildBasePage(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        val c = context
        scanButton.setOnClickListener { host.scan() }
        addView(scanButton, lp(MATCH, WRAP))

        resultBox.apply {
            orientation = VERTICAL
            background = roundRect(Mr.SURFACE_2, c.dp(Mr.RADIUS_MD))
            val p = c.dpi(10f)
            setPadding(p, p, p, p)
        }
        hint.text = "Abra a base no jogo e toque em Detectar. Na primeira vez o Android pede permissão " +
            "para capturar a tela; entre uma análise e outra a captura fica pausada."
        resultBox.addView(hint, lp(MATCH, WRAP))

        resultRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            addView(miniMap, lp(c.dpi(112f), c.dpi(64f)))
            val texts = LinearLayout(c).apply {
                orientation = VERTICAL
                addView(c.text("BASE", 10f, Mr.SECONDARY, 600).apply { letterSpacing = 0.06f })
                addView(baseNumber, lp(WRAP, WRAP).apply { topMargin = c.dpi(2f) })
                addView(baseScore, lp(WRAP, WRAP).apply { topMargin = c.dpi(4f) })
            }
            addView(texts, lp(0, WRAP, 1f).apply { marginStart = c.dpi(Mr.GAP_MD) })
        }
        resultBox.addView(resultRow, lp(MATCH, WRAP))
        baseVerdict.visibility = GONE
        resultBox.addView(baseVerdict, lp(MATCH, WRAP).apply { topMargin = c.dpi(8f) })

        altRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
        }
        resultBox.addView(altRow, lp(MATCH, WRAP).apply { topMargin = c.dpi(8f) })
        addView(resultBox, lp(MATCH, WRAP).apply { topMargin = c.dpi(10f) })

        actionsRow.apply {
            orientation = HORIZONTAL
            visibility = GONE
            alignButton.setOnClickListener { host.alignToDetected() }
            videosButton.setOnClickListener { if (shownBase > 0) host.openVideos(shownBase) }
            addView(alignButton, lp(0, WRAP, 1f))
            addView(videosButton, lp(0, WRAP, 1f).apply { marginStart = c.dpi(Mr.GAP_SM) })
        }
        addView(actionsRow, lp(MATCH, WRAP).apply { topMargin = c.dpi(Mr.GAP_SM) })
    }

    // --- Estado vindo do serviço ---

    fun setTab(index: Int, animate: Boolean) {
        tabs.select(index, animate)
        if (index != tab) switchTab(index, animate) else if (!animate) {
            adjustPage.visibility = if (index == 0) VISIBLE else INVISIBLE
            basePage.visibility = if (index == 1) VISIBLE else INVISIBLE
        }
    }

    private fun switchTab(index: Int, animate: Boolean = true) {
        val from = if (tab == 0) adjustPage else basePage
        val to = if (index == 0) adjustPage else basePage
        val dir = if (index > tab) 1 else -1
        tab = index
        host.setTab(index)
        if (from === to) return
        if (!animate) {
            from.visibility = INVISIBLE
            to.visibility = VISIBLE
            return
        }
        val shift = context.dp(18f) * dir
        from.animate().cancel()
        from.anim(120, Motion.exit).alpha(0f).translationX(-shift)
            .withEndAction { from.visibility = INVISIBLE; from.translationX = 0f; from.alpha = 1f }.start()
        to.animate().cancel()
        to.visibility = VISIBLE
        to.alpha = 0f
        to.translationX = shift
        to.anim(320, Motion.emphasized, delay = 60).alpha(1f).translationX(0f).start()
    }

    fun setStep(index: Int) = steps.select(index, animate = false)

    fun setTouchAdjust(on: Boolean) {
        if (touchSwitch.checked != on) touchSwitch.setChecked(on)
    }

    fun setGridInfo(visible: Boolean, landscape: Boolean, left: Float, top: Float, cell: Float) {
        eyeButton.set(iconRes = if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off)
        eyeButton.tint(if (visible) Mr.BASE else Mr.ORANGE)
        subtitle.text = when {
            !visible -> "Grade oculta"
            !landscape -> "Só aparece na horizontal"
            else -> "Grade 14×8 · ${fmt(cell)} px"
        }
        if (cell <= 0f) {
            sizeValue.text = "–"
            coords.text = "Abra o jogo para calibrar"
            return
        }
        sizeValue.text = fmt(cell)
        coords.text = "X ${left.toInt()} · Y ${top.toInt()} · ${(cell * 14).toInt()}×${(cell * 8).toInt()}"
    }

    private fun fmt(v: Float) = String.format(PT_BR, "%.1f", v)

    private companion object {
        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    }

    fun setScanning(on: Boolean) {
        scanButton.isEnabled = !on
        scanButton.set(if (on) "Analisando…" else "Detectar base", if (on) R.drawable.ic_loader_circle else R.drawable.ic_scan_search)
        scanButton.iconView.spin(on)
    }

    fun showMessage(message: String, error: Boolean) {
        resultRow.visibility = GONE
        altRow.visibility = GONE
        baseVerdict.visibility = GONE
        actionsRow.visibility = GONE
        hint.text = message
        hint.setTextColor(if (error) Mr.ORANGE else Mr.SECONDARY)
        hint.visibility = VISIBLE
        pop(hint)
    }

    /**
     * Mostra o resultado: mini-mapa da base, número, semelhança e as outras parecidas
     * (tocar numa delas troca o mini-mapa e o destaque na tela).
     */
    fun showResult(matches: List<BaseMatcher.Match>, walls: (Int) -> BooleanArray?, read: BooleanArray, canAlign: Boolean) {
        val (first, second) = matches
        hint.visibility = GONE
        resultRow.visibility = VISIBLE
        val confident = first.score >= BaseMatcher.MIN_SCORE
        val close = first.score - second.score < BaseMatcher.MIN_MARGIN
        baseVerdict.visibility = if (!confident || close) VISIBLE else GONE
        baseVerdict.text = when {
            !confident -> "Não reconheci essa base com segurança; estas são as mais parecidas."
            else -> "Pode ser a ${first.number} ou a ${second.number}: os desenhos são quase iguais."
        }
        altRow.removeAllViews()
        for (m in matches) {
            val chip = MrButton(context, MrButton.Style.BASE, heightDp = 28f, radiusDp = 14f).set("${m.number} · ${pct(m.score)}")
            chip.tag = m.number
            chip.setOnClickListener { host.showBase(m.number) }
            altRow.addView(chip, lp(WRAP, WRAP).apply { marginEnd = context.dpi(6f) })
        }
        altRow.visibility = VISIBLE
        alignButton.visibility = if (canAlign) VISIBLE else GONE
        actionsRow.visibility = VISIBLE
        this.walls = walls
        this.read = read
        this.scores = matches.associate { it.number to it.score }
        selectBase(first.number)
        listOf(resultRow, baseVerdict, altRow, actionsRow).forEachIndexed { i, v -> if (v.visibility == VISIBLE) pop(v, i * 50L) }
    }

    private var walls: (Int) -> BooleanArray? = { null }
    private var read: BooleanArray? = null
    private var scores: Map<Int, Float> = emptyMap()

    fun selectBase(number: Int) {
        shownBase = number
        baseNumber.text = number.toString()
        val score = scores[number] ?: 0f
        baseScore.text = "${pct(score)} de semelhança"
        baseScore.setTextColor(if (score >= BaseMatcher.MIN_SCORE) Mr.BRAND else Mr.ORANGE)
        miniMap.show(walls(number), read)
        for (i in 0 until altRow.childCount) {
            val chip = altRow.getChildAt(i) as? MrButton ?: continue
            val active = chip.tag == number
            chip.background = roundRect(if (active) Mr.BRAND_HIGHLIGHT else Mr.SURFACE_4, context.dp(14f),
                if (active) Mr.BRAND else Mr.SURFACE_5, context.dpi(1f))
            chip.tint(if (active) Mr.BRAND else Mr.CONTRAST)
        }
    }

    fun hideAlign() {
        alignButton.anim(160, Motion.exit).alpha(0f).withEndAction {
            alignButton.visibility = GONE
            alignButton.alpha = 1f
        }.start()
    }

    private fun pct(score: Float) = "${(score * 100).toInt()}%"

    private fun pop(view: View, delay: Long = 0) {
        view.alpha = 0f
        view.translationY = context.dp(8f)
        view.anim(360, Motion.emphasized, delay).alpha(1f).translationY(0f).start()
    }
}

/** Mini-mapa 14x8 da base: paredes em cinza claro; diferenças da leitura com contorno vermelho. */
class MiniMap(context: Context) : View(context) {
    private var walls: BooleanArray? = null
    private var misses: BooleanArray? = null
    private var progress = 1f
    private var anim: ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.SURFACE_1 }
    private val wall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.BASE }
    private val open = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.SURFACE_4 }
    private val miss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Mr.RED
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.2f)
    }
    private val rect = RectF()

    fun show(walls: BooleanArray?, read: BooleanArray?) {
        this.walls = walls
        misses = if (walls != null && read != null) BooleanArray(walls.size) { read[it] != walls[it] } else null
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = Motion.emphasized
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = context.dp(Mr.RADIUS_SM)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bg)
        val walls = walls ?: return
        val pad = context.dp(4f)
        val cw = (width - 2 * pad) / BaseMatcher.COLS
        val ch = (height - 2 * pad) / BaseMatcher.ROWS
        val gap = context.dp(0.75f)
        val reveal = progress * (BaseMatcher.COLS + 4)
        for (i in walls.indices) {
            val c = i % BaseMatcher.COLS
            val row = i / BaseMatcher.COLS
            val local = ((reveal - c - row * 0.5f) / 3f).coerceIn(0f, 1f)
            val x = pad + c * cw
            val y = pad + row * ch
            val s = 0.6f + 0.4f * local
            val ix = (cw - gap) * (1 - s) / 2
            val iy = (ch - gap) * (1 - s) / 2
            rect.set(x + ix, y + iy, x + cw - gap - ix, y + ch - gap - iy)
            val p = if (walls[i]) wall else open
            p.alpha = (255 * local).toInt()
            canvas.drawRoundRect(rect, gap, gap, p)
            if (misses?.get(i) == true) {
                miss.alpha = (255 * local).toInt()
                canvas.drawRect(rect, miss)
            }
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        progress = 1f
        super.onDetachedFromWindow()
    }
}
