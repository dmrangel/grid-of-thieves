package io.github.dmrangel.gridofthieves

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import io.github.dmrangel.gridofthieves.scan.BaseMatcher
import io.github.dmrangel.gridofthieves.scan.ScreenCapture
import io.github.dmrangel.gridofthieves.ui.Motion
import io.github.dmrangel.gridofthieves.ui.Mr
import io.github.dmrangel.gridofthieves.ui.anim
import io.github.dmrangel.gridofthieves.ui.dp
import io.github.dmrangel.gridofthieves.ui.dpi
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Serviço em primeiro plano dono de todas as janelas do overlay: a grade, a bolha, o alvo
 * de fechar e o painel. Por ser um serviço (e não uma Activity em segundo plano), o
 * Android não derruba o overlay quando o jogo está aberto, e abrir o app de novo só
 * expande o painel.
 *
 * Todas as janelas usam coordenadas da tela inteira: ocupam o recorte da câmera e ignoram
 * as barras do sistema, então a calibração não muda quando elas aparecem ou somem.
 */
class OverlayService : Service(), PanelHost {

    companion object {
        const val ACTION_SHOW = "io.github.dmrangel.gridofthieves.SHOW"
        const val ACTION_STOP = "io.github.dmrangel.gridofthieves.STOP"
        const val ACTION_PROJECTION = "io.github.dmrangel.gridofthieves.PROJECTION"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1
        private val STEPS = floatArrayOf(1f, 5f, 20f)
        /** Janela que não recebe toque precisa de alpha <= 0,8 para o toque passar para o jogo (Android 12+). */
        private const val PASSTHROUGH_ALPHA = 0.78f
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ui: Context
    private lateinit var wm: WindowManager
    private lateinit var displays: DisplayManager
    private lateinit var prefs: SharedPreferences
    private lateinit var config: GridConfig
    private lateinit var capture: ScreenCapture

    private var screenW = 0
    private var screenH = 0
    private var rotation = -1
    private var densityDpi = DisplayMetrics.DENSITY_DEFAULT
    private var cutLeft = 0
    private var cutTop = 0
    private var cutRight = 0
    private var cutBottom = 0
    private val landscape get() = screenW > screenH

    // Grade
    private lateinit var gridView: GridView
    private lateinit var gridParams: WindowManager.LayoutParams
    private var gridVisible = true
    private var touchAdjust = false
    private var gridAnim: ValueAnimator? = null

    // Bolha e alvo de fechar
    private lateinit var bubble: BubbleView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var closeTarget: CloseTargetView
    private lateinit var closeParams: WindowManager.LayoutParams
    private var bubbleAnim: ValueAnimator? = null

    // Painel (criado na primeira vez que abre)
    private var panel: PanelView? = null
    private var panelRoot: FrameLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private var expanded = false

    private var step = 1
    private var tab = 0

    // Detecção
    private var scanning = false
    private var pendingScan = false
    private var matcher: BaseMatcher? = null
    private var lastResult: BaseMatcher.Result.Found? = null
    private var lastScale = 1f

    private val attached = HashSet<View>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        displays = getSystemService(DisplayManager::class.java)
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY)
        ui = if (Build.VERSION.SDK_INT >= 30) createDisplayContext(display).createWindowContext(TYPE_APPLICATION_OVERLAY, null) else this
        wm = ui.getSystemService(WindowManager::class.java)
        prefs = getSharedPreferences("ui", MODE_PRIVATE)
        config = GridConfig(this)
        capture = ScreenCapture(this) { onProjectionStopped() }
        step = prefs.getInt("step", 1).coerceIn(0, STEPS.size - 1)
        tab = prefs.getInt("tab", 0)
        gridVisible = prefs.getBoolean("grid", true)

        if (!Settings.canDrawOverlays(this)) {
            // Permissão revogada com o serviço reiniciando sozinho: só sai.
            goForeground(false)
            stopSelf()
            return
        }
        readDisplay()
        if (landscape) config.load(screenW, screenH, rotation)
        // A ordem de adição define quem fica por cima: grade < alvo de fechar < bolha < painel.
        createGrid()
        createCloseTarget()
        createBubble()
        displays.registerDisplayListener(displayListener, main)
        if (!goForeground(false)) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SHOW -> if (::bubble.isInitialized) expand()
            ACTION_PROJECTION -> onProjectionResult(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::bubble.isInitialized) {
            config.save()
            displays.unregisterDisplayListener(displayListener)
            gridAnim?.cancel()
            bubbleAnim?.cancel()
            for (view in attached.toList()) removeWindow(view)
        }
        capture.shutdown()
        super.onDestroy()
    }

    // --- Primeiro plano e notificação ---

    private fun goForeground(projecting: Boolean): Boolean = try {
        val n = notification(projecting)
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (projecting) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0),
            )
            Build.VERSION.SDK_INT >= 29 && projecting ->
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(NOTIFICATION_ID, n)
        }
        true
    } catch (e: RuntimeException) {
        false
    }

    private fun notification(projecting: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Overlay ativo", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            })
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val show = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_SHOW), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_grid_3x3)
            .setColor(Mr.BRAND)
            .setContentTitle("Grid of Thieves ativo")
            .setContentText(if (projecting) "Captura de tela liberada (pausada entre as análises)" else "Toque para abrir o painel")
            .setContentIntent(show)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_x), "Fechar", stop).build())
            .setOngoing(true)
            .build()
    }

    // --- Tela ---

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val wasLandscape = landscape
            if (wasLandscape) flushSave() // salva na rotação antiga antes de trocar
            if (!readDisplay()) return
            gridAnim?.cancel()
            if (landscape) config.load(screenW, screenH, rotation)
            if (touchAdjust && !landscape) setTouchAdjust(false)
            layoutGrid()
            placeBubble(bubbleParams.x, bubbleParams.y, snap = true)
            if (expanded) positionPanel()
        }
    }

    /** Lê tamanho real, rotação e recorte da tela. Devolve true se algo mudou. */
    private fun readDisplay(): Boolean {
        val d = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val size = Point()
        @Suppress("DEPRECATION")
        d.getRealSize(size)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(metrics)
        densityDpi = metrics.densityDpi
        if (Build.VERSION.SDK_INT >= 29) {
            val cut = d.cutout
            cutLeft = cut?.safeInsetLeft ?: 0
            cutTop = cut?.safeInsetTop ?: 0
            cutRight = cut?.safeInsetRight ?: 0
            cutBottom = cut?.safeInsetBottom ?: 0
        }
        val changed = size.x != screenW || size.y != screenH || d.rotation != rotation
        screenW = size.x
        screenH = size.y
        rotation = d.rotation
        return changed
    }

    // --- Janelas ---

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, TYPE_APPLICATION_OVERLAY,
        flags or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS or FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun addWindow(view: View, params: WindowManager.LayoutParams) {
        wm.addView(view, params)
        attached += view
    }

    private fun removeWindow(view: View) {
        if (attached.remove(view)) try {
            wm.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun update(view: View, params: WindowManager.LayoutParams) {
        if (view in attached) wm.updateViewLayout(view, params)
    }

    /** Muda posição/tamanho e só chama o WindowManager se algo mudou de verdade. */
    private fun WindowManager.LayoutParams.place(x: Int, y: Int, w: Int = width, h: Int = height): Boolean {
        if (this.x == x && this.y == y && width == w && height == h) return false
        this.x = x
        this.y = y
        width = w
        height = h
        return true
    }

    // --- Grade ---

    @SuppressLint("ClickableViewAccessibility")
    private fun createGrid() {
        gridView = GridView(ui)
        gridParams = overlayParams(1, 1, FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE).apply {
            alpha = PASSTHROUGH_ALPHA
            title = "Grid of Thieves: grade"
        }
        gridView.setOnTouchListener { _, e ->
            if (touchAdjust) adjustTouch(e)
            touchAdjust
        }
        addWindow(gridView, gridParams)
        layoutGrid()
    }

    /** Coloca a janela da grade no lugar: do tamanho da grade, ou a tela toda ao ajustar com o dedo. */
    private fun layoutGrid() {
        val show = gridVisible && landscape
        gridView.visibility = if (show) View.VISIBLE else View.GONE
        if (::bubble.isInitialized) bubble.gridVisible = gridVisible
        panel?.setGridInfo(gridVisible, landscape, config.left, config.top, config.cell)
        if (!show) return
        var changed: Boolean
        if (touchAdjust) {
            changed = gridParams.place(0, 0, screenW, screenH)
            gridView.setGeometry(config.left, config.top, config.cell)
        } else {
            val pad = gridView.pad
            val x = floor(config.left - pad).toInt()
            val y = floor(config.top - pad).toInt()
            changed = gridParams.place(x, y, ceil(config.width + 2 * pad).toInt() + 1, ceil(config.height + 2 * pad).toInt() + 1)
            gridView.setGeometry(config.left - x, config.top - y, config.cell)
        }
        val flags = FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS or FLAG_HARDWARE_ACCELERATED or
            (if (touchAdjust) 0 else FLAG_NOT_TOUCHABLE)
        if (gridParams.flags != flags) {
            gridParams.flags = flags
            changed = true
        }
        if (changed) update(gridView, gridParams)
    }

    private fun onGridChanged() {
        layoutGrid()
        scheduleSave()
    }

    /** Leva a grade até o destino numa mola (alinhar, centralizar, padrão). */
    private fun animateGridTo(left: Float, top: Float, cell: Float) {
        gridAnim?.cancel()
        val l0 = config.left
        val t0 = config.top
        val c0 = config.cell
        gridAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480
            interpolator = Motion.settle
            addUpdateListener {
                val f = it.animatedValue as Float
                config.set(l0 + (left - l0) * f, t0 + (top - t0) * f, c0 + (cell - c0) * f)
                layoutGrid()
            }
            start()
        }
        scheduleSave()
    }

    // Salva sozinho, 600 ms depois da última mudança.
    private var savePending = false
    private val save = Runnable {
        savePending = false
        config.save()
    }

    private fun scheduleSave() {
        if (!landscape) return
        main.removeCallbacks(save)
        savePending = true
        main.postDelayed(save, 600)
    }

    private fun flushSave() {
        if (savePending) {
            main.removeCallbacks(save)
            save.run()
        }
    }

    // Ajustar com o dedo: um dedo arrasta, dois dedos arrastam e fazem pinça.
    private val scaleDetector by lazy {
        ScaleGestureDetector(ui, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                config.scaleAround(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })
    }
    private var lastFx = 0f
    private var lastFy = 0f

    private fun adjustTouch(e: MotionEvent) {
        gridAnim?.cancel()
        scaleDetector.onTouchEvent(e)
        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) {
            sx += e.getX(i)
            sy += e.getY(i)
            n++
        }
        if (n == 0) return
        val fx = sx / n
        val fy = sy / n
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastFx = fx
                lastFy = fy
            }
            MotionEvent.ACTION_MOVE -> {
                config.move(fx - lastFx, fy - lastFy)
                lastFx = fx
                lastFy = fy
                onGridChanged()
            }
        }
    }

    // --- Bolha ---

    private val bubbleSize get() = ui.dpi(52f)
    private val margin get() = ui.dpi(6f)

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        bubble = BubbleView(ui)
        bubble.gridVisible = gridVisible
        val size = bubbleSize
        bubbleParams = overlayParams(size, size, FLAG_NOT_FOCUSABLE).apply { title = "Grid of Thieves: bolha" }
        val onLeft = prefs.getBoolean("bubble_left", true)
        val y = (prefs.getFloat("bubble_y", 0.3f) * screenH).toInt()
        bubbleParams.x = if (onLeft) 0 else screenW
        bubbleParams.y = y
        placeBubble(bubbleParams.x, y, snap = true, apply = false)
        bubble.setOnTouchListener(BubbleTouch())
        bubble.setOnClickListener { expand() }
        bubble.contentDescription = "Abrir o painel da grade"
        addWindow(bubble, bubbleParams)
        scheduleIdle()
    }

    private fun minX() = margin + cutLeft
    private fun maxX() = screenW - bubbleSize - margin - cutRight
    private fun minY() = margin + cutTop
    private fun maxY() = screenH - bubbleSize - margin - cutBottom

    /** Prende a bolha na tela; com [snap], encosta na borda mais próxima. */
    private fun placeBubble(x: Int, y: Int, snap: Boolean, apply: Boolean = true) {
        val nx = if (snap) (if (x + bubbleSize / 2 < screenW / 2) minX() else maxX()) else x.coerceIn(minX(), maxX())
        val ny = y.coerceIn(minY(), max(minY(), maxY()))
        if (bubbleParams.place(nx, ny) && apply) update(bubble, bubbleParams)
    }

    private fun animateBubble(x: Int, y: Int, interpolator: android.view.animation.Interpolator = Motion.spring, end: (() -> Unit)? = null) {
        bubbleAnim?.cancel()
        val x0 = bubbleParams.x
        val y0 = bubbleParams.y
        bubbleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460
            this.interpolator = interpolator
            addUpdateListener {
                val f = it.animatedValue as Float
                if (bubbleParams.place((x0 + (x - x0) * f).roundToInt(), (y0 + (y - y0) * f).roundToInt())) update(bubble, bubbleParams)
            }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
    }

    private val idle = Runnable { if (!expanded) bubble.anim(450, Motion.emphasized).alpha(0.55f).start() }

    private fun scheduleIdle() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, 2600)
    }

    private fun wake() {
        main.removeCallbacks(idle)
        bubble.anim(120, Motion.emphasized).alpha(1f).start()
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var magnet = false
        private var longPressed = false
        private var velocity: VelocityTracker? = null
        private val slop = ViewConfiguration.get(ui).scaledTouchSlop
        private val longPress = Runnable {
            if (!dragging) {
                longPressed = true
                bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                toggleGrid()
                bubble.anim(140, Motion.emphasized).scaleX(1.12f).scaleY(1.12f).withEndAction {
                    bubble.anim(300, Motion.spring).scaleX(1f).scaleY(1f).start()
                }.start()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val tracker = velocity ?: VelocityTracker.obtain().also { velocity = it }
            // A janela se move junto com o dedo: a velocidade usa coordenadas da tela.
            val copy = MotionEvent.obtain(e)
            copy.setLocation(e.rawX, e.rawY)
            tracker.addMovement(copy)
            copy.recycle()

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleAnim?.cancel()
                    wake()
                    downX = e.rawX
                    downY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                    longPressed = false
                    magnet = false
                    v.anim(90, Motion.emphasized).scaleX(0.9f).scaleY(0.9f).start()
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop && !longPressed) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        v.anim(200, Motion.spring).scaleX(1f).scaleY(1f).start()
                        showCloseTarget()
                    }
                    if (dragging) {
                        val nx = (startX + dx).roundToInt()
                        val ny = (startY + dy).roundToInt()
                        val tx = closeParams.x + closeParams.width / 2f
                        val ty = closeParams.y + closeParams.height / 2f
                        val near = hypot(nx + bubbleSize / 2f - tx, ny + bubbleSize / 2f - ty) < ui.dp(72f)
                        if (near != magnet) {
                            magnet = near
                            closeTarget.active = near
                            bubble.danger = near
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        if (near) {
                            if (bubbleParams.place((tx - bubbleSize / 2f).roundToInt(), (ty - bubbleSize / 2f).roundToInt())) update(bubble, bubbleParams)
                        } else if (bubbleParams.place(nx, ny)) {
                            update(bubble, bubbleParams)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    tracker.computeCurrentVelocity(1000)
                    val vx = tracker.xVelocity
                    val vy = tracker.yVelocity
                    tracker.recycle()
                    velocity = null
                    if (dragging) {
                        hideCloseTarget()
                        if (magnet) {
                            dismissFromBubble()
                            return true
                        }
                        val projectedX = bubbleParams.x + vx * 0.12f + bubbleSize / 2f
                        val onLeft = projectedX < screenW / 2f
                        val ty = (bubbleParams.y + vy * 0.08f).roundToInt().coerceIn(minY(), max(minY(), maxY()))
                        animateBubble(if (onLeft) minX() else maxX(), ty)
                        prefs.edit().putBoolean("bubble_left", onLeft).putFloat("bubble_y", ty.toFloat() / screenH).apply()
                    } else {
                        v.anim(260, Motion.spring).scaleX(1f).scaleY(1f).start()
                        if (!longPressed && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    }
                    scheduleIdle()
                }
            }
            return true
        }
    }

    private fun createCloseTarget() {
        closeTarget = CloseTargetView(ui)
        val size = ui.dpi(72f)
        closeParams = overlayParams(size, size, FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE).apply {
            title = "Grid of Thieves: fechar"
            alpha = 0.8f // o sistema força no máximo 0,8 em janelas que não recebem toque
        }
        closeTarget.visibility = View.GONE
        addWindow(closeTarget, closeParams)
    }

    private fun showCloseTarget() {
        val size = closeParams.width
        if (closeParams.place((screenW - size) / 2, screenH - size - ui.dpi(28f) - cutBottom)) update(closeTarget, closeParams)
        closeTarget.animate().cancel()
        closeTarget.visibility = View.VISIBLE
        closeTarget.alpha = 0f
        closeTarget.translationY = ui.dp(28f)
        closeTarget.scaleX = 0.7f
        closeTarget.scaleY = 0.7f
        closeTarget.anim(340, Motion.spring).alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).start()
    }

    private fun hideCloseTarget() {
        closeTarget.active = false
        bubble.danger = false
        closeTarget.anim(180, Motion.exit).alpha(0f).translationY(ui.dp(28f)).withEndAction {
            closeTarget.visibility = View.GONE
        }.start()
    }

    private fun dismissFromBubble() {
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        bubble.anim(200, Motion.exit).scaleX(0f).scaleY(0f).alpha(0f).withEndAction { stopSelf() }.start()
    }

    // --- Painel ---

    @SuppressLint("ClickableViewAccessibility")
    private fun ensurePanel(): PanelView {
        panel?.let { return it }
        val card = PanelView(ui, this)
        val root = FrameLayout(ui).apply {
            clipChildren = false
            clipToPadding = false
            // Espaço para a sombra (elevation) do card.
            setPadding(ui.dpi(6f), ui.dpi(4f), ui.dpi(6f), ui.dpi(10f))
            addView(card, FrameLayout.LayoutParams(ui.dpi(296f), FrameLayout.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
            setOnTouchListener { _, e ->
                // Toque fora do painel (no jogo): recolhe para a bolha.
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && !touchAdjust && !scanning) collapse()
                false
            }
        }
        panelParams = overlayParams(WRAP_CONTENT, WRAP_CONTENT, FLAG_NOT_FOCUSABLE or FLAG_WATCH_OUTSIDE_TOUCH).apply {
            title = "Grid of Thieves: painel"
        }
        addWindow(root, panelParams)
        card.setTab(tab, animate = false)
        card.setStep(step)
        card.setGridInfo(gridVisible, landscape, config.left, config.top, config.cell)
        panel = card
        panelRoot = root
        return card
    }

    private fun expand() {
        if (expanded) return
        val card = ensurePanel()
        val root = panelRoot!!
        expanded = true
        main.removeCallbacks(idle)
        positionPanel()
        root.visibility = View.VISIBLE
        // A bolha "vira" o painel: ele cresce a partir dela.
        card.pivotX = (bubbleParams.x + bubbleSize / 2f - panelParams.x - root.paddingLeft).coerceIn(0f, ui.dp(296f))
        card.pivotY = (bubbleParams.y + bubbleSize / 2f - panelParams.y - root.paddingTop).coerceAtLeast(0f)
        card.animate().cancel()
        card.scaleX = 0.35f
        card.scaleY = 0.35f
        card.alpha = 0f
        // O overshoot da mola passa de 1 no alpha, mas o HWUI limita o alpha a 1.
        card.anim(420, Motion.spring).scaleX(1f).scaleY(1f).alpha(1f).start()
        bubble.animate().cancel()
        bubble.anim(140, Motion.exit).alpha(0f).scaleX(0.6f).scaleY(0.6f).withEndAction {
            bubble.visibility = View.INVISIBLE
        }.start()
    }

    /** Abre o painel ao lado da bolha, sem sair da tela. */
    private fun positionPanel() {
        val root = panelRoot ?: return
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val pw = root.measuredWidth
        val onLeft = bubbleParams.x + bubbleSize / 2 < screenW / 2
        val x = if (onLeft) bubbleParams.x - root.paddingLeft else bubbleParams.x + bubbleSize + root.paddingRight - pw
        movePanelTo(x, bubbleParams.y - root.paddingTop)
    }

    private fun movePanelTo(x: Int, y: Int) {
        val root = panelRoot ?: return
        val pw = if (root.width > 0) root.width else root.measuredWidth
        val ph = if (root.height > 0) root.height else root.measuredHeight
        // O card (sem a margem da sombra) fica a [margin] das bordas e fora do recorte da câmera.
        val minX = margin + cutLeft - root.paddingLeft
        val minY = margin + cutTop - root.paddingTop
        val nx = x.coerceIn(minX, max(minX, screenW - pw + root.paddingRight - margin - cutRight))
        val ny = y.coerceIn(minY, max(minY, screenH - ph + root.paddingBottom - margin - cutBottom))
        if (panelParams.place(nx, ny)) update(root, panelParams)
    }

    override fun dragPanel(dx: Float, dy: Float) {
        panelDragX += dx
        panelDragY += dy
        val ix = panelDragX.toInt()
        val iy = panelDragY.toInt()
        if (ix != 0 || iy != 0) {
            panelDragX -= ix
            panelDragY -= iy
            movePanelTo(panelParams.x + ix, panelParams.y + iy)
        }
    }

    private var panelDragX = 0f
    private var panelDragY = 0f

    override fun collapse() {
        if (!expanded) return
        expanded = false
        if (touchAdjust) setTouchAdjust(false)
        val card = panel ?: return
        val root = panelRoot ?: return
        card.pivotX = (bubbleParams.x + bubbleSize / 2f - panelParams.x - root.paddingLeft)
        card.pivotY = (bubbleParams.y + bubbleSize / 2f - panelParams.y - root.paddingTop)
        card.animate().cancel()
        card.anim(200, Motion.exit).scaleX(0.4f).scaleY(0.4f).alpha(0f).withEndAction {
            root.visibility = View.GONE
            card.scaleX = 1f
            card.scaleY = 1f
            card.alpha = 1f
        }.start()
        bubble.animate().cancel()
        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f
        bubble.scaleX = 0.6f
        bubble.scaleY = 0.6f
        bubble.anim(360, Motion.spring, delay = 90).scaleX(1f).scaleY(1f).alpha(1f).start()
        scheduleIdle()
    }

    // --- Ações do painel ---

    override fun move(dx: Int, dy: Int, multiplier: Int) {
        gridAnim?.cancel()
        val s = STEPS[step] * multiplier
        config.move(dx * s, dy * s)
        onGridChanged()
    }

    override fun zoom(direction: Int, multiplier: Int) {
        gridAnim?.cancel()
        config.grow(direction * STEPS[step] * multiplier)
        onGridChanged()
    }

    override fun centerGrid() {
        if (!landscape) return
        animateGridTo((screenW - config.width) / 2, config.top, config.cell)
    }

    override fun resetGrid() {
        if (!landscape) return
        val d = GridConfig.defaults(screenW, screenH)
        animateGridTo(d[0], d[1], d[2])
    }

    override fun setStep(index: Int) {
        step = index
        prefs.edit().putInt("step", index).apply()
    }

    override fun setTab(index: Int) {
        tab = index
        prefs.edit().putInt("tab", index).apply()
    }

    override fun setTouchAdjust(on: Boolean) {
        val value = on && landscape
        if (value && !gridVisible) {
            gridVisible = true
            prefs.edit().putBoolean("grid", true).apply()
        }
        touchAdjust = value
        gridView.adjusting = value
        layoutGrid()
        panel?.setTouchAdjust(value)
    }

    override fun toggleGrid() {
        gridVisible = !gridVisible
        if (!gridVisible && touchAdjust) setTouchAdjust(false)
        prefs.edit().putBoolean("grid", gridVisible).apply()
        layoutGrid()
    }

    override fun close() = stopSelf()

    override fun openVideos(number: Int) {
        val query = Uri.encode("king of thieves base $number")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            collapse()
        } catch (_: ActivityNotFoundException) {
            panel?.showMessage("Nenhum app abre links do YouTube neste aparelho.", error = true)
        }
    }

    override fun showBase(number: Int) {
        val result = lastResult ?: return
        panel?.selectBase(number)
        matcher?.walls(number)?.let { gridView.flash(it, result.walls) }
    }

    override fun alignToDetected() {
        val f = lastResult?.frame ?: return
        val s = lastScale
        val cell = ((f.right - f.left) / s / GridConfig.COLS + (f.bottom - f.top) / s / GridConfig.ROWS) / 2
        animateGridTo(f.left / s, f.top / s, cell)
        panel?.hideAlign()
    }

    // --- Detecção de base ---

    override fun scan() {
        if (scanning) return
        if (!landscape) {
            panel?.showMessage("Vire o celular: a detecção funciona com o jogo na horizontal.", error = true)
            return
        }
        if (!capture.isReady) {
            pendingScan = true
            startActivity(Intent(this, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        scanning = true
        if (touchAdjust) setTouchAdjust(false)
        panel?.setScanning(true)
        // Esconde o overlay para ele não aparecer na captura.
        setOverlaysHidden(true) {
            capture.grab(screenW, screenH, densityDpi) { frame ->
                setOverlaysHidden(false, null)
                if (frame == null) {
                    finishScan()
                    panel?.showMessage("A captura não respondeu. Tente de novo.", error = true)
                    return@grab
                }
                analyze(frame)
            }
        }
    }

    private fun analyze(frame: ScreenCapture.Frame) {
        val scale = frame.width.toFloat() / screenW
        // A grade calibrada entra como candidato, já na escala da captura.
        val hint = BaseMatcher.Frame(
            (config.left * scale).roundToInt(), (config.top * scale).roundToInt(),
            ((config.left + config.width) * scale).roundToInt(), ((config.top + config.height) * scale).roundToInt(),
        )
        capture.worker().post {
            val result = try {
                val m = matcher ?: BaseMatcher(BaseMatcher.parse(assets.open("bases.json").bufferedReader().use { it.readText() }))
                    .also { matcher = it }
                m.identify(frame.lum, frame.width, frame.height, hint)
            } catch (e: Exception) {
                BaseMatcher.Result.NotFound("Erro na análise: ${e.message}")
            }
            main.post { onScanResult(result, hint, scale) }
        }
    }

    private fun onScanResult(result: BaseMatcher.Result, hint: BaseMatcher.Frame, scale: Float) {
        finishScan()
        val p = panel ?: return
        when (result) {
            is BaseMatcher.Result.NotFound ->
                p.showMessage("${result.reason} Deixe a base aberta no jogo, sem menus por cima, e tente de novo.", error = true)
            is BaseMatcher.Result.Found -> {
                lastResult = result
                lastScale = scale
                val f = result.frame
                val off = maxOf(abs(f.left - hint.left), abs(f.top - hint.top), abs(f.right - hint.right), abs(f.bottom - hint.bottom))
                val m = matcher
                p.showResult(result.matches, { m?.walls(it) }, result.walls, canAlign = f != hint && off > 12)
                m?.walls(result.matches.first().number)?.let { if (gridVisible) gridView.flash(it, result.walls) }
            }
        }
    }

    private fun finishScan() {
        scanning = false
        panel?.setScanning(false)
    }

    /** Some com grade, painel e bolha (com um fade curto) e chama [then] quando já saíram da tela. */
    private fun setOverlaysHidden(hidden: Boolean, then: (() -> Unit)?) {
        val views = listOfNotNull(
            gridView.takeIf { gridVisible && landscape },
            panelRoot.takeIf { expanded },
            bubble.takeIf { !expanded },
        )
        if (hidden) {
            for (v in views) v.anim(90, Motion.exit).alpha(0f).start()
            main.postDelayed({
                for (v in views) v.visibility = View.INVISIBLE
                // Dá tempo do compositor tirar as janelas da tela antes de capturar.
                main.postDelayed({ then?.invoke() }, 90)
            }, 100)
        } else {
            for (v in views) {
                v.visibility = View.VISIBLE
                v.anim(180, Motion.emphasized).alpha(1f).start()
            }
        }
    }

    private fun onProjectionResult(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            pendingScan = false
            panel?.showMessage("Sem permissão para capturar a tela. Toque em Detectar para tentar de novo.", error = true)
            return
        }
        try {
            // No Android 14+ o serviço precisa ser do tipo mediaProjection antes de pegar a projeção.
            if (!goForeground(projecting = true)) throw IllegalStateException("o Android recusou o serviço de captura")
            capture.start(code, data)
        } catch (e: RuntimeException) {
            goForeground(false)
            pendingScan = false
            panel?.showMessage("Não consegui iniciar a captura: ${e.message}", error = true)
            return
        }
        if (pendingScan) {
            pendingScan = false
            // Espera a janela de permissão sair da tela.
            main.postDelayed({ scan() }, 650)
        }
    }

    private fun onProjectionStopped() {
        goForeground(false)
    }
}
