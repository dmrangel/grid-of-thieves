package io.github.dmrangel.gridofthieves.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Identifica a base pelo desenho das paredes. Porte direto de `src/kot/base-match.js`
 * do chrollo: as bases são um grid de 7x4 células, guardado em meias-células (14x8)
 * em `assets/bases.json` (tabela de bases 6.9), com '#' para parede e '.' para área aberta.
 *
 * Diferença em relação ao bot: aqui o app já sabe onde a grade está na tela, então o
 * retângulo calibrado entra como mais um candidato, além dos que [findFrames] acha sozinho.
 *
 * Código puro (sem APIs do Android) para rodar nos testes da JVM.
 */
class BaseMatcher(private val bases: List<Base>) {

    class Base(val number: Int, val walls: BooleanArray) {
        internal val bits: LongArray = pack(walls)
    }

    /** [score] de 0 a 1: fração das 112 meias-células iguais. */
    data class Match(val number: Int, val score: Float)

    /** Retângulo em pixels da imagem analisada: [left, top) até [right, bottom). */
    data class Frame(val left: Int, val top: Int, val right: Int, val bottom: Int)

    sealed interface Result {
        /** [walls] é o que foi lido na tela, no [frame] que deu o melhor resultado. */
        data class Found(val matches: List<Match>, val walls: BooleanArray, val frame: Frame) : Result
        data class NotFound(val reason: String) : Result
    }

    fun walls(number: Int): BooleanArray? = bases.firstOrNull { it.number == number }?.walls

    /**
     * Compara a imagem com todas as bases da tabela.
     * [lum] é a luminância (0..255) linha a linha; [hint] é o retângulo da grade calibrada,
     * já na escala da imagem.
     */
    fun identify(lum: ByteArray, width: Int, height: Int, hint: Frame? = null, top: Int = 3): Result {
        require(lum.size >= width * height)
        val threshold = otsu(lum, width * height)
        val open = ByteArray(width * height)
        for (i in open.indices) if ((lum[i].toInt() and 0xFF) > threshold) open[i] = 1

        val frames = ArrayList<Frame>(3)
        if (hint != null && hint.right - hint.left >= COLS * 2 && hint.bottom - hint.top >= ROWS * 2) frames += hint
        val reason = findFrames(open, width, height, frames)
        if (frames.isEmpty()) return Result.NotFound(reason ?: NOT_FOUND)

        val best = FloatArray(bases.size)
        var bestOverall = -1f
        var bestWalls = BooleanArray(0)
        var bestFrame = frames[0]
        for (frame in frames) {
            val walls = sampleGrid(open, width, height, frame)
            val bits = pack(walls)
            for ((i, base) in bases.withIndex()) {
                val diff = java.lang.Long.bitCount(bits[0] xor base.bits[0]) + java.lang.Long.bitCount(bits[1] xor base.bits[1])
                val score = (CELLS - diff).toFloat() / CELLS
                if (score > best[i]) best[i] = score
                if (score > bestOverall) {
                    bestOverall = score
                    bestWalls = walls
                    bestFrame = frame
                }
            }
        }
        val matches = bases.indices.sortedByDescending { best[it] }.take(top).map { Match(bases[it].number, best[it]) }
        return Result.Found(matches, bestWalls, bestFrame)
    }

    companion object {
        const val COLS = 14
        const val ROWS = 8
        const val CELLS = COLS * ROWS
        /** A captura vem reduzida para esta largura, como o print no bot. */
        const val WORK_WIDTH = 1000

        /** Abaixo disso o desenho não bate o suficiente com nenhuma base da tabela. */
        const val MIN_SCORE = 0.85f
        /** Com menos diferença que isso entre as duas primeiras, as duas são possíveis. */
        const val MIN_MARGIN = 0.04f

        private const val RATIO = 7f / 4f
        private const val BLOCK = 4
        private const val NOT_FOUND = "Não achei a área da base na tela."

        private val ENTRY = Regex("\"(\\d+)\"\\s*:\\s*\\[([^\\]]*)]")
        private val ROW = Regex("\"([#.]+)\"")

        /** Lê o `bases.json` do chrollo sem org.json (que não existe nos testes da JVM). */
        fun parse(json: String): List<Base> = ENTRY.findAll(json).map { entry ->
            val rows = ROW.findAll(entry.groupValues[2]).map { it.groupValues[1] }.toList()
            val cells = rows.joinToString("")
            require(rows.size == ROWS && cells.length == CELLS) { "base ${entry.groupValues[1]} não é 14x8" }
            Base(entry.groupValues[1].toInt(), BooleanArray(CELLS) { cells[it] == '#' })
        }.toList()

        private fun pack(walls: BooleanArray): LongArray {
            val bits = LongArray(2)
            for (i in walls.indices) if (walls[i]) bits[i shr 6] = bits[i shr 6] or (1L shl (i and 63))
            return bits
        }

        /** Limiar de Otsu: separa os pixels claros (área aberta) dos escuros (paredes e fundo). */
        internal fun otsu(lum: ByteArray, count: Int): Int {
            val hist = IntArray(256)
            for (i in 0 until count) hist[lum[i].toInt() and 0xFF]++
            var sum = 0.0
            for (i in 0 until 256) sum += i.toDouble() * hist[i]
            var sumB = 0.0
            var wB = 0L
            var best = 0.0
            var threshold = 0
            for (i in 0 until 256) {
                wB += hist[i]
                if (wB == 0L) continue
                val wF = count - wB
                if (wF == 0L) break
                sumB += i.toDouble() * hist[i]
                val d = sumB / wB - (sum - sumB) / wF
                val between = wB.toDouble() * wF * d * d
                if (between > best) {
                    best = between
                    threshold = i
                }
            }
            return threshold
        }

        /**
         * Retângulo da base na imagem; acrescenta em [out] um ou dois candidatos (dois quando
         * um lado está encoberto). Devolve o motivo quando não acha nada.
         */
        internal fun findFrames(open: ByteArray, width: Int, height: Int, out: MutableList<Frame>): String? {
            val bw = width / BLOCK
            val bh = height / BLOCK
            if (bw < 3 || bh < 3) return NOT_FOUND
            val blocks = ByteArray(bw * bh)
            val half = BLOCK * BLOCK / 2
            for (by in 0 until bh) for (bx in 0 until bw) {
                var n = 0
                for (y in by * BLOCK until (by + 1) * BLOCK) {
                    val row = y * width
                    for (x in bx * BLOCK until (bx + 1) * BLOCK) n += open[row + x]
                }
                if (n > half) blocks[by * bw + bx] = 1
            }

            // Erosão: corta as pontes finas entre a base e a interface do jogo (moedas, poções...).
            val eroded = ByteArray(bw * bh)
            for (y in 1 until bh - 1) for (x in 1 until bw - 1) {
                val i = y * bw + x
                eroded[i] = (blocks[i].toInt() and blocks[i - 1].toInt() and blocks[i + 1].toInt() and
                    blocks[i - bw].toInt() and blocks[i + bw].toInt()).toByte()
            }

            // Maior região clara conectada = área aberta da base.
            val label = IntArray(bw * bh)
            val stack = IntArray(bw * bh)
            var bestLabel = 0
            var bestSize = 0
            var next = 0
            for (start in eroded.indices) {
                if (eroded[start].toInt() == 0 || label[start] != 0) continue
                next++
                var size = 0
                var sp = 0
                stack[sp++] = start
                label[start] = next
                while (sp > 0) {
                    val i = stack[--sp]
                    size++
                    val x = i % bw
                    if (i >= bw) sp = visit(i - bw, eroded, label, next, stack, sp)
                    if (i + bw < eroded.size) sp = visit(i + bw, eroded, label, next, stack, sp)
                    if (x > 0) sp = visit(i - 1, eroded, label, next, stack, sp)
                    if (x < bw - 1) sp = visit(i + 1, eroded, label, next, stack, sp)
                }
                if (size > bestSize) {
                    bestSize = size
                    bestLabel = next
                }
            }
            if (bestLabel == 0) return NOT_FOUND

            // Linhas e colunas da base têm pelo menos uma célula aberta inteira; as da interface, não.
            val rowCount = IntArray(bh)
            val colCount = IntArray(bw)
            for (i in label.indices) if (label[i] == bestLabel) {
                rowCount[i / bw]++
                colCount[i % bw]++
            }
            val rows = rowCount.indices.filter { rowCount[it] >= 0.08 * bw }
            val cols = colCount.indices.filter { colCount[it] >= 0.08 * bh }
            if (rows.isEmpty() || cols.isEmpty()) return NOT_FOUND
            // A erosão encolheu a região em um bloco de cada lado; aqui ela volta.
            val y0 = (rows.first() - 1) * BLOCK
            val y1 = (rows.last() + 2) * BLOCK
            val x0 = (cols.first() - 1) * BLOCK
            val x1 = (cols.last() + 2) * BLOCK
            val w = x1 - x0
            val h = y1 - y0

            // Proporção fora de 7:4: um lado ficou coberto (baú, porta, armadilha). Testa os dois encaixes.
            when {
                w.toFloat() / h < RATIO * 0.97f -> {
                    val nw = (h * RATIO).roundToInt()
                    out += Frame(x0, y0, x0 + nw, y1)
                    out += Frame(x1 - nw, y0, x1, y1)
                }
                w.toFloat() / h > RATIO * 1.03f -> {
                    val nh = (w / RATIO).roundToInt()
                    out += Frame(x0, y0, x1, y0 + nh)
                    out += Frame(x0, y1 - nh, x1, y1)
                }
                else -> out += Frame(x0, y0, x1, y1)
            }
            return null
        }

        private fun visit(j: Int, eroded: ByteArray, label: IntArray, next: Int, stack: IntArray, sp: Int): Int {
            if (eroded[j].toInt() == 0 || label[j] != 0) return sp
            label[j] = next
            stack[sp] = j
            return sp + 1
        }

        /** Lê o grid 14x8: cada meia-célula é parede se o miolo dela for quase todo escuro. */
        internal fun sampleGrid(open: ByteArray, width: Int, height: Int, f: Frame): BooleanArray {
            val cw = (f.right - f.left).toDouble() / COLS
            val ch = (f.bottom - f.top).toDouble() / ROWS
            return BooleanArray(CELLS) { index ->
                val r = index / COLS
                val c = index % COLS
                val ya = max(0, (f.top + (r + 0.2) * ch).toInt())
                val yb = min(height, (f.top + (r + 0.8) * ch).toInt())
                val xa = max(0, (f.left + (c + 0.2) * cw).toInt())
                val xb = min(width, (f.left + (c + 0.8) * cw).toInt())
                var n = 0
                var total = 0
                for (y in ya until yb) {
                    val row = y * width
                    for (x in xa until xb) {
                        n += open[row + x]
                        total++
                    }
                }
                total == 0 || n.toFloat() / total < 0.45f
            }
        }
    }
}
