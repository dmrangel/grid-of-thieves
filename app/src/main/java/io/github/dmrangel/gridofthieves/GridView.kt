package io.github.dmrangel.gridofthieves

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.dmrangel.gridofthieves.GridConfig.Companion.COLS
import io.github.dmrangel.gridofthieves.GridConfig.Companion.ROWS
import io.github.dmrangel.gridofthieves.ui.Motion
import io.github.dmrangel.gridofthieves.ui.Mr
import io.github.dmrangel.gridofthieves.ui.dp

/**
 * Desenha a grade 14x8: linhas das células do jogo (7x4) mais fortes, meias-células mais
 * fracas, cruzetas nos cruzamentos como no editor do jogo e a borda da masmorra.
 *
 * As linhas ficam pré-calculadas num FloatArray e cada quadro são poucas chamadas de
 * drawLines; a view só redesenha quando a grade muda.
 */
class GridView(context: Context) : View(context) {
    /** Posição da grade dentro da view (pixels) e tamanho da célula. */
    private var ox = 0f
    private var oy = 0f
    private var cell = 0f

    /** Modo "ajustar com o dedo": a view ocupa a tela toda e mostra as alças. */
    var adjusting = false
        set(value) {
            field = value
            invalidate()
        }

    private val minorPaint = stroke(Mr.withAlpha(Mr.BRAND, 0.55f), 0.8f)
    private val majorPaint = stroke(Mr.BRAND, 1.3f)
    private val crossPaint = stroke(0xFFFFFF78.toInt(), 1.1f)
    private val borderPaint = stroke(Mr.BLUE, 1.8f)
    private val handlePaint = stroke(Mr.CONTRAST, 3f).apply { strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint().apply { color = Mr.withAlpha(Mr.BRAND, 0.08f) }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.BRAND }
    private val wallStroke = stroke(Mr.BRAND, 1.6f)
    private val missPaint = stroke(Mr.RED, 2f)
    private val sweepPaint = stroke(Mr.BRAND, 2.5f)
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.withAlpha(Mr.SURFACE_3, 0.92f) }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Mr.CONTRAST
        typeface = Mr.semibold(context)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }

    private var minor = FloatArray(0)
    private var major = FloatArray(0)
    private var crosses = FloatArray(0)
    private var linesForCell = -1f
    private val rect = RectF()

    // Destaque depois de detectar a base: paredes da base em verde, diferenças em vermelho.
    private var walls: BooleanArray? = null
    private var misses: BooleanArray? = null
    private var reveal = 0f
    private var highlightAlpha = 0f
    private var highlightAnim: ValueAnimator? = null

    private fun stroke(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = context.dp(widthDp)
    }

    /** Margem para a borda não ser cortada quando a janela tem o tamanho da grade. */
    val pad: Float get() = borderPaint.strokeWidth + context.dp(2f)

    fun setGeometry(originX: Float, originY: Float, cellSize: Float) {
        ox = originX
        oy = originY
        cell = cellSize
        invalidate()
    }

    private fun rebuildLines() {
        linesForCell = cell
        val w = cell * COLS
        val h = cell * ROWS
        val minorList = ArrayList<Float>()
        val majorList = ArrayList<Float>()
        for (i in 1 until COLS) {
            val x = i * cell
            (if (i % 2 == 0) majorList else minorList).addAll(listOf(x, 0f, x, h))
        }
        for (j in 1 until ROWS) {
            val y = j * cell
            (if (j % 2 == 0) majorList else minorList).addAll(listOf(0f, y, w, y))
        }
        minor = minorList.toFloatArray()
        major = majorList.toFloatArray()
        val arm = minOf(context.dp(4f), cell * 0.18f)
        crosses = FloatArray((COLS - 1) * (ROWS - 1) * 8)
        var k = 0
        for (i in 1 until COLS) for (j in 1 until ROWS) {
            val cx = i * cell
            val cy = j * cell
            crosses[k++] = cx - arm; crosses[k++] = cy; crosses[k++] = cx + arm; crosses[k++] = cy
            crosses[k++] = cx; crosses[k++] = cy - arm; crosses[k++] = cx; crosses[k++] = cy + arm
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cell <= 0f) return
        if (cell != linesForCell) rebuildLines()
        val w = cell * COLS
        val h = cell * ROWS

        if (adjusting) drawAdjustHint(canvas)

        canvas.save()
        canvas.translate(ox, oy)
        if (adjusting) canvas.drawRect(0f, 0f, w, h, fillPaint)
        drawHighlight(canvas)
        canvas.drawLines(minor, minorPaint)
        canvas.drawLines(major, majorPaint)
        canvas.drawLines(crosses, crossPaint)
        canvas.drawRect(0f, 0f, w, h, borderPaint)
        if (adjusting) drawHandles(canvas, w, h)
        canvas.restore()
    }

    private fun drawHandles(canvas: Canvas, w: Float, h: Float) {
        val l = minOf(cell * 0.8f, context.dp(22f))
        val o = -context.dp(3f)
        for ((x, y, sx, sy) in listOf(
            Quad(o, o, 1f, 1f), Quad(w - o, o, -1f, 1f), Quad(o, h - o, 1f, -1f), Quad(w - o, h - o, -1f, -1f),
        )) {
            canvas.drawLine(x, y, x + l * sx, y, handlePaint)
            canvas.drawLine(x, y, x, y + l * sy, handlePaint)
        }
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    private fun drawAdjustHint(canvas: Canvas) {
        val text = "Arraste para mover · pinça para redimensionar"
        val tw = hintText.measureText(text)
        val ph = context.dp(10f)
        val cx = width / 2f
        val top = context.dp(14f)
        val h = hintText.textSize + 2 * ph
        rect.set(cx - tw / 2 - context.dp(16f), top, cx + tw / 2 + context.dp(16f), top + h)
        canvas.drawRoundRect(rect, h / 2, h / 2, hintBg)
        canvas.drawText(text, cx, rect.centerY() - (hintText.descent() + hintText.ascent()) / 2, hintText)
    }

    private fun drawHighlight(canvas: Canvas) {
        val walls = walls ?: return
        if (highlightAlpha <= 0f) return
        val revealCols = reveal * COLS
        val inset = cell * 0.08f
        val r = cell * 0.12f
        for (index in walls.indices) {
            val c = index % COLS
            val local = (revealCols - c).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val x = c * cell
            val y = index / COLS * cell
            rect.set(x + inset, y + inset, x + cell - inset, y + cell - inset)
            if (walls[index]) {
                wallPaint.alpha = (150 * local * highlightAlpha).toInt()
                canvas.drawRoundRect(rect, r, r, wallPaint)
                wallStroke.alpha = (255 * local * highlightAlpha).toInt()
                canvas.drawRoundRect(rect, r, r, wallStroke)
            }
            if (misses?.get(index) == true) {
                missPaint.alpha = (255 * local * highlightAlpha).toInt()
                canvas.drawRoundRect(rect, r, r, missPaint)
            }
        }
        if (reveal < 1f) {
            val x = reveal * cell * COLS
            sweepPaint.alpha = (255 * highlightAlpha).toInt()
            canvas.drawLine(x, -context.dp(4f), x, cell * ROWS + context.dp(4f), sweepPaint)
        }
    }

    /**
     * Mostra o desenho da base detectada por cima do jogo: varredura da esquerda para a
     * direita, fica um tempo e some. [read] marca, em vermelho, onde a leitura diferiu.
     */
    fun flash(baseWalls: BooleanArray, read: BooleanArray?) {
        walls = baseWalls
        misses = read?.let { r -> BooleanArray(baseWalls.size) { r[it] != baseWalls[it] } }
        highlightAnim?.cancel()
        highlightAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3400
            interpolator = LinearInterpolator()
            addUpdateListener {
                val ms = it.animatedFraction * duration
                reveal = Motion.emphasized.getInterpolation((ms / 650f).coerceAtMost(1f))
                highlightAlpha = if (ms < 2600f) 1f else 1f - (ms - 2600f) / 800f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    highlightAlpha = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        highlightAnim?.cancel()
        highlightAlpha = 0f
        super.onDetachedFromWindow()
    }
}
