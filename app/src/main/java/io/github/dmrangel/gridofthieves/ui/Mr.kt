package io.github.dmrangel.gridofthieves.ui

import android.content.Context
import android.graphics.Typeface
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import io.github.dmrangel.gridofthieves.R
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tokens do design system do Modrinth App (tema escuro), os mesmos do Wordlink:
 * `packages/assets/styles/variables.scss` e `packages/ui` em github.com/modrinth/code.
 * Nenhum código, logo ou marca do Modrinth é usado.
 */
object Mr {
    const val SURFACE_1 = 0xFF16181C.toInt()
    const val SURFACE_2 = 0xFF1D1F23.toInt()
    const val SURFACE_3 = 0xFF27292E.toInt()
    const val SURFACE_4 = 0xFF34363C.toInt()
    const val SURFACE_5 = 0xFF42444A.toInt()

    /** --color-text-primary. */
    const val CONTRAST = 0xFFFFFFFF.toInt()
    /** --color-text-default. */
    const val BASE = 0xFFB0BAC5.toInt()
    /** --color-text-tertiary. */
    const val SECONDARY = 0xFF96A2B0.toInt()

    const val BRAND = 0xFF1BD96A.toInt()
    const val BRAND_HIGHLIGHT = 0x401BD96A
    const val RED = 0xFFFF496E.toInt()
    const val RED_HIGHLIGHT = 0x40FF496E
    const val ORANGE = 0xFFFFA347.toInt()
    const val BLUE = 0xFF4F9CFF.toInt()
    const val ACCENT_CONTRAST = 0xFF000000.toInt()
    /** --hover-brightness, usado aqui no estado pressionado. */
    const val PRESS_OVERLAY = 0x1AFFFFFF

    // --radius-* e --gap-* (dp).
    const val RADIUS_SM = 8f
    const val RADIUS_MD = 12f
    const val RADIUS_LG = 16f
    const val RADIUS_XL = 20f
    const val GAP_XS = 4f
    const val GAP_SM = 8f
    const val GAP_MD = 12f
    const val GAP_LG = 16f

    private var medium: Typeface? = null
    private var semibold: Typeface? = null
    private var extrabold: Typeface? = null

    /** Inter 500: corpo do texto. */
    fun medium(ctx: Context): Typeface = medium ?: ctx.resources.getFont(R.font.inter_medium).also { medium = it }
    /** Inter 600: rótulos e botões. */
    fun semibold(ctx: Context): Typeface = semibold ?: ctx.resources.getFont(R.font.inter_semibold).also { semibold = it }
    /** Inter 800: títulos. */
    fun extrabold(ctx: Context): Typeface = extrabold ?: ctx.resources.getFont(R.font.inter_extrabold).also { extrabold = it }

    fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

    fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int) = ((from shr shift and 0xFF) + ((to shr shift and 0xFF) - (from shr shift and 0xFF)) * t).toInt() shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Curvas de animação. As molas dão o "peso" das animações do Modrinth sem dependências. */
object Motion {
    /** Mola com um leve overshoot (~4%): bolha, painel, pílulas. */
    val spring: Interpolator = Spring(damping = 0.72f, omega = 11f)
    /** Mola sem overshoot visível: grade e tamanhos. */
    val settle: Interpolator = Spring(damping = 0.92f, omega = 9.5f)
    val emphasized: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val exit: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /** Oscilador amortecido normalizado: vai de 0 a 1 e assenta antes de t = 1. */
    private class Spring(private val damping: Float, private val omega: Float) : Interpolator {
        private val wd = omega * sqrt(1f - damping * damping)
        private val k = damping * omega / wd
        override fun getInterpolation(t: Float): Float {
            if (t >= 1f) return 1f
            val decay = exp(-damping * omega * t)
            return 1f - decay * (cos(wd * t) + k * sin(wd * t))
        }
    }
}
