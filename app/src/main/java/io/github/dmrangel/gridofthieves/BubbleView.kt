package io.github.dmrangel.gridofthieves

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import io.github.dmrangel.gridofthieves.ui.Motion
import io.github.dmrangel.gridofthieves.ui.Mr
import io.github.dmrangel.gridofthieves.ui.anim
import io.github.dmrangel.gridofthieves.ui.dp

/**
 * Bolha flutuante: círculo surface-3 com borda e o ícone da grade em verde.
 * Com a grade oculta, o ícone vira um olho riscado em cinza.
 */
class BubbleView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.SURFACE_3 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Mr.SURFACE_5
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val gridIcon: Drawable = context.getDrawable(R.drawable.ic_grid_3x3)!!.mutate().apply { setTint(Mr.BRAND) }
    private val hiddenIcon: Drawable = context.getDrawable(R.drawable.ic_eye_off)!!.mutate().apply { setTint(Mr.SECONDARY) }

    var gridVisible = true
        set(value) {
            field = value
            invalidate()
        }

    /** Destaque do alvo de fechar: a borda fica vermelha. */
    var danger = false
        set(value) {
            field = value
            ring.color = if (value) Mr.RED else Mr.SURFACE_5
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - context.dp(2f)
        canvas.drawCircle(cx, cy + context.dp(1f), r + context.dp(1f), shadow)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)
        val icon = if (gridVisible) gridIcon else hiddenIcon
        val s = (r * 0.95f).toInt()
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.draw(canvas)
    }
}

/** Alvo de fechar que aparece embaixo enquanto a bolha é arrastada. */
class CloseTargetView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.SURFACE_3 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Mr.withAlpha(Mr.RED, 0.6f)
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
    }
    private val icon: Drawable = context.getDrawable(R.drawable.ic_x)!!.mutate().apply { setTint(Mr.RED) }

    var active = false
        set(value) {
            if (field == value) return
            field = value
            fill.color = if (value) Mr.RED else Mr.SURFACE_3
            icon.setTint(if (value) Mr.ACCENT_CONTRAST else Mr.RED)
            anim(260, Motion.spring).scaleX(if (value) 1.18f else 1f).scaleY(if (value) 1.18f else 1f).start()
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) / 1.2f
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)
        val s = (r * 0.8f).toInt()
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.draw(canvas)
    }
}
