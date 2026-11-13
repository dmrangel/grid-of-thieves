package io.github.dmrangel.gridofthieves

import android.content.Context
import kotlin.math.min

/**
 * Posição e tamanho da grade, em pixels da tela inteira (incluindo o recorte da câmera).
 * As células do jogo são quadradas, então o tamanho é só [cell]: a grade tem 14x8 meias-células.
 *
 * Fica salvo como fração do tamanho da tela, por rotação: o jogo pode deslocar o desenho
 * quando o celular vira 180° (recorte da câmera do outro lado). Sem calibração para uma
 * rotação, usa a da rotação oposta e, sem nenhuma, o padrão calculado pelo tamanho da tela.
 */
class GridConfig(context: Context) {
    private val prefs = context.getSharedPreferences("grid", Context.MODE_PRIVATE)

    var left = 0f
        private set
    var top = 0f
        private set
    var cell = 0f
        private set
    val width get() = cell * COLS
    val height get() = cell * ROWS

    private var screenW = 1
    private var screenH = 1
    private var rotation = 0

    fun load(screenW: Int, screenH: Int, rotation: Int) {
        this.screenW = screenW
        this.screenH = screenH
        this.rotation = rotation
        val own = "r$rotation"
        val flipped = "r${(rotation + 2) % 4}"
        when {
            prefs.contains("${own}c") -> read(own)
            prefs.contains("${flipped}c") -> read(flipped)
            else -> reset()
        }
    }

    private fun read(key: String) {
        left = prefs.getFloat("${key}x", 0f) * screenW
        top = prefs.getFloat("${key}y", 0f) * screenH
        cell = prefs.getFloat("${key}c", 0f) * screenH
        clamp()
    }

    fun save() {
        if (cell <= 0f) return // nunca carregou (só ficou em retrato): não sobrescreve nada
        val key = "r$rotation"
        prefs.edit()
            .putFloat("${key}x", left / screenW)
            .putFloat("${key}y", top / screenH)
            .putFloat("${key}c", cell / screenH)
            .apply()
    }

    fun reset() {
        val d = defaults(screenW, screenH)
        set(d[0], d[1], d[2])
    }

    fun set(left: Float, top: Float, cell: Float) {
        this.left = left
        this.top = top
        this.cell = cell
        clamp()
    }

    fun move(dx: Float, dy: Float) = set(left + dx, top + dy, cell)

    /** Muda o tamanho mantendo o centro: cada borda lateral anda [px] pixels. */
    fun grow(px: Float) {
        val next = (cell + 2 * px / COLS).coerceIn(MIN_CELL, maxCell())
        val d = next - cell
        set(left - d * COLS / 2, top - d * ROWS / 2, next)
    }

    /** Pinça: escala em volta do ponto ([fx], [fy]) da tela. */
    fun scaleAround(factor: Float, fx: Float, fy: Float) {
        val next = (cell * factor).coerceIn(MIN_CELL, maxCell())
        val f = next / cell
        set(fx - (fx - left) * f, fy - (fy - top) * f, next)
    }

    fun centerHorizontally() = set((screenW - width) / 2, top, cell)

    private fun maxCell() = screenH / 4f

    /** Deixa pelo menos uma célula da grade dentro da tela. */
    private fun clamp() {
        if (cell !in MIN_CELL..maxCell()) cell = defaults(screenW, screenH)[2]
        left = left.coerceIn(cell - width, screenW - cell)
        top = top.coerceIn(cell - height, screenH - cell)
    }

    companion object {
        const val COLS = 14
        const val ROWS = 8
        private const val MIN_CELL = 8f

        /**
         * Onde o jogo desenha a base: a célula ocupa ~10% da altura da tela (calibrado num
         * Poco X6 Pro, 2712x1220: célula de 122,5 px) e, em telas mais quadradas, o jogo passa
         * a encaixar pela largura (6,68% dela, medido nos prints de iPad do chrollo). A base
         * fica centralizada na horizontal, com o topo a 7,7% da altura.
         * Devolve [left, top, cell].
         */
        fun defaults(w: Int, h: Int): FloatArray {
            val cell = min(h * 0.1004f, w * 0.0668f)
            return floatArrayOf((w - cell * COLS) / 2, h * 0.077f, cell)
        }
    }
}
