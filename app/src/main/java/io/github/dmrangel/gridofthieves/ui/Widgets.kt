package io.github.dmrangel.gridofthieves.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.dmrangel.gridofthieves.R

/** Fundo arredondado com borda opcional (cards e botões do Modrinth). */
fun roundRect(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

/**
 * ViewPropertyAnimator com tudo explícito: duração, curva e atraso "grudam" no animator
 * da view entre chamadas, então sempre definimos os três.
 */
fun View.anim(duration: Long, interpolator: android.view.animation.Interpolator, delay: Long = 0): android.view.ViewPropertyAnimator =
    animate().setDuration(duration).setInterpolator(interpolator).setStartDelay(delay).withLayer()

/** Escala 0,96 ao pressionar, como o ButtonFrame do Modrinth (e o MrButton do Wordlink). */
fun View.pressScale(scale: Float = 0.96f) {
    fun anim(to: Float) = ObjectAnimator().apply {
        setValues(PropertyValuesHolder.ofFloat(View.SCALE_X, to), PropertyValuesHolder.ofFloat(View.SCALE_Y, to))
        duration = if (to < 1f) 70 else 160
        interpolator = if (to < 1f) Motion.emphasized else Motion.spring
    }
    stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), anim(scale))
        addState(intArrayOf(), anim(1f))
    }
}

/** Camada clara por cima do conteúdo enquanto pressionado. */
fun pressOverlay(radius: Float): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), roundRect(Mr.PRESS_OVERLAY, radius))
    addState(intArrayOf(), ColorDrawable(0))
    setExitFadeDuration(120)
}

fun Context.text(
    value: CharSequence = "",
    size: Float = 13f,
    color: Int = Mr.BASE,
    weight: Int = 500,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    typeface = when {
        weight >= 800 -> Mr.extrabold(context)
        weight >= 600 -> Mr.semibold(context)
        else -> Mr.medium(context)
    }
    includeFontPadding = false
    isSingleLine = true
    ellipsize = android.text.TextUtils.TruncateAt.END
}

fun Context.icon(res: Int, color: Int = Mr.BASE, sizeDp: Float = 20f): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    val s = dpi(sizeDp)
    layoutParams = LinearLayout.LayoutParams(s, s)
}

fun lp(width: Int, height: Int, weight: Float = 0f, gravity: Int = -1) =
    LinearLayout.LayoutParams(width, height, weight).apply { if (gravity != -1) this.gravity = gravity }

const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

/**
 * Botão no formato do ButtonFrame do Modrinth.
 * [Style.BASE] = surface-4 com borda surface-5; [Style.BRAND] = verde com texto preto;
 * [Style.QUIET] = sem fundo.
 */
@SuppressLint("ViewConstructor") // montado só em código
class MrButton(context: Context, style: Style, heightDp: Float = 36f, radiusDp: Float = Mr.RADIUS_MD) : LinearLayout(context) {
    enum class Style { BASE, BRAND, QUIET }

    val iconView: ImageView
    val label: TextView
    private val content = if (style == Style.BRAND) Mr.ACCENT_CONTRAST else if (style == Style.QUIET) Mr.BASE else Mr.CONTRAST

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val r = context.dp(radiusDp)
        background = when (style) {
            Style.BASE -> roundRect(Mr.SURFACE_4, r, Mr.SURFACE_5, context.dpi(1f))
            Style.BRAND -> roundRect(Mr.BRAND, r)
            Style.QUIET -> null
        }
        foreground = pressOverlay(r)
        minimumHeight = context.dpi(heightDp)
        val pad = context.dpi(if (heightDp <= 32f) 10f else 12f)
        setPadding(pad, 0, pad, 0)
        iconView = context.icon(0, content, if (heightDp <= 32f) 16f else 18f).apply { visibility = GONE }
        label = context.text(size = if (heightDp <= 32f) 12f else 13f, color = content, weight = 600).apply { visibility = GONE }
        addView(iconView)
        addView(label, lp(WRAP, WRAP).apply { marginStart = context.dpi(6f) })
        pressScale()
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    fun set(text: CharSequence? = null, iconRes: Int = 0): MrButton {
        label.text = text
        label.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        if (iconRes != 0) iconView.setImageResource(iconRes)
        iconView.visibility = if (iconRes != 0) VISIBLE else GONE
        (label.layoutParams as LayoutParams).marginStart = if (iconRes != 0 && !text.isNullOrEmpty()) context.dpi(6f) else 0
        return this
    }

    fun tint(color: Int) {
        iconView.imageTintList = ColorStateList.valueOf(color)
        label.setTextColor(color)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        anim(150, Motion.emphasized).alpha(if (enabled) 1f else 0.5f).start()
    }
}

/** Botão só de ícone, quadrado ou redondo. */
fun Context.iconButton(res: Int, style: MrButton.Style = MrButton.Style.QUIET, sizeDp: Float = 32f, round: Boolean = true): MrButton =
    MrButton(this, style, sizeDp, if (round) sizeDp / 2 else Mr.RADIUS_MD).apply {
        set(iconRes = res)
        setPadding(0, 0, 0, 0)
        layoutParams = lp(dpi(sizeDp), dpi(sizeDp))
    }

/**
 * Repete a ação enquanto o dedo estiver no botão, acelerando depois de um tempo
 * (multiplicador 1 → 3 → 6). Dá o toque curto de vibração só no começo.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.onHold(action: (multiplier: Int) -> Unit) {
    var start = 0L
    val repeat = object : Runnable {
        override fun run() {
            val held = SystemClock.uptimeMillis() - start
            action(if (held > 2200) 6 else if (held > 1100) 3 else 1)
            postDelayed(this, 60)
        }
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                start = SystemClock.uptimeMillis()
                action(1)
                v.postDelayed(repeat, 360)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                v.removeCallbacks(repeat)
            }
        }
        true
    }
}

/** Pílula segmentada (chips do Modrinth) com indicador que desliza numa mola. */
@SuppressLint("ViewConstructor") // montado só em código
class Segmented(context: Context, private val labels: List<String>, private val heightDp: Float = 36f) : View(context) {
    var onSelect: ((Int) -> Unit)? = null
    var selected = 0
        private set

    private var pos = 0f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.SURFACE_1 }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.SURFACE_5; style = Paint.Style.STROKE; strokeWidth = context.dp(1f) }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mr.BRAND_HIGHLIGHT }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Mr.semibold(context)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (heightDp <= 32f) 12f else 13f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val inset = context.dp(3f)

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    fun select(index: Int, animate: Boolean = true) {
        if (index == selected && animate) return
        selected = index
        anim?.cancel()
        if (!animate) {
            pos = index.toFloat()
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(pos, index.toFloat()).apply {
            duration = 380
            interpolator = Motion.spring
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpi(heightDp))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, track)
        rect.inset(border.strokeWidth / 2, border.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, border)

        val seg = (width - 2 * inset) / labels.size
        val left = inset + pos * seg
        rect.set(left, inset, left + seg, h - inset)
        canvas.drawRoundRect(rect, r - inset, r - inset, pill)

        val baseline = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        for ((i, label) in labels.withIndex()) {
            val closeness = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
            textPaint.color = Mr.blend(Mr.BASE, Mr.BRAND, closeness)
            canvas.drawText(label, inset + seg * (i + 0.5f), baseline, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val seg = (width - 2 * inset) / labels.size
            val index = ((event.x - inset) / seg).toInt().coerceIn(0, labels.size - 1)
            if (index != selected) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                select(index)
                onSelect?.invoke(index)
            }
        }
        return true
    }
}

/** Interruptor do Modrinth: trilho verde quando ligado, bolinha deslizando numa mola. */
class MrSwitch(context: Context) : View(context) {
    var onChange: ((Boolean) -> Unit)? = null
    var checked = false
        private set

    private var pos = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            setChecked(!checked)
            onChange?.invoke(checked)
        }
    }

    fun setChecked(value: Boolean, animate: Boolean = true) {
        checked = value
        anim?.cancel()
        val to = if (value) 1f else 0f
        if (!animate) {
            pos = to
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(pos, to).apply {
            duration = 320
            interpolator = Motion.spring
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dpi(40f), context.dpi(24f))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val t = pos.coerceIn(0f, 1f)
        paint.color = Mr.blend(Mr.SURFACE_5, Mr.BRAND, t)
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val knob = h / 2 - context.dp(3f)
        val cx = h / 2 + pos * (width - h)
        paint.color = Mr.blend(Mr.CONTRAST, Mr.ACCENT_CONTRAST, t)
        canvas.drawCircle(cx, h / 2, knob, paint)
    }
}

/** Gira o ícone de carregamento enquanto estiver visível. */
fun ImageView.spin(on: Boolean) {
    val tag = R.id.spin_animator
    (getTag(tag) as? ObjectAnimator)?.cancel()
    rotation = 0f
    if (!on) return
    setTag(tag, ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
        duration = 800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        start()
    })
}
