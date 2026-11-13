package io.github.dmrangel.gridofthieves.scan

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlin.math.roundToInt

/**
 * Captura de tela sob demanda via MediaProjection.
 *
 * O Android 14+ só deixa criar um VirtualDisplay por consentimento, então o display é
 * criado uma vez e, entre uma análise e outra, fica sem superfície (`setSurface(null)`):
 * o sistema para de compor a tela para ele e o custo vai a zero. Cada captura liga um
 * ImageReader novo, pega um quadro e desliga de novo.
 *
 * A captura já sai reduzida para [BaseMatcher.WORK_WIDTH] px de largura (a GPU reduz),
 * e vira luminância numa thread própria.
 */
class ScreenCapture(private val context: Context, private val onStopped: () -> Unit) {
    class Frame(val lum: ByteArray, val width: Int, val height: Int)

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var projection: MediaProjection? = null
    @Volatile private var display: VirtualDisplay? = null
    private var displayW = 0
    private var displayH = 0
    private var pending = false

    val isReady: Boolean get() = projection != null

    /** Thread de trabalho, compartilhada com a análise da base. */
    fun worker(): Handler = worker ?: HandlerThread("got-scan").also { it.start(); thread = it }
        .let { Handler(it.looper) }.also { worker = it }

    fun start(resultCode: Int, data: Intent) {
        release()
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("Sem MediaProjection")
        // No Android 14+ o callback tem que existir antes do createVirtualDisplay.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === p) {
                    release()
                    onStopped()
                }
            }
        }, main)
        projection = p
    }

    /**
     * Pega um quadro da tela inteira ([screenW]x[screenH] px) e devolve a luminância na
     * thread principal; devolve null se o sistema não mandar nenhum quadro a tempo.
     */
    fun grab(screenW: Int, screenH: Int, densityDpi: Int, callback: (Frame?) -> Unit) {
        val p = projection ?: return callback(null)
        if (pending) return
        pending = true
        val w = BaseMatcher.WORK_WIDTH
        val h = (w.toFloat() * screenH / screenW).roundToInt()
        val handler = worker()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        var done = false
        fun finish(frame: Frame?) {
            if (done) return
            done = true
            reader.setOnImageAvailableListener(null, null)
            display?.surface = null
            reader.close()
            main.post {
                pending = false
                callback(frame)
            }
        }

        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return@setOnImageAvailableListener
            val frame = try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val lum = ByteArray(w * h)
                val rowBuf = ByteArray(rowStride)
                for (y in 0 until h) {
                    buffer.position(y * rowStride)
                    buffer.get(rowBuf, 0, minOf(rowStride, buffer.remaining()))
                    var o = 0
                    val row = y * w
                    for (x in 0 until w) {
                        val sum = (rowBuf[o].toInt() and 0xFF) + (rowBuf[o + 1].toInt() and 0xFF) + (rowBuf[o + 2].toInt() and 0xFF)
                        lum[row + x] = ((sum + 1) / 3).toByte()
                        o += pixelStride
                    }
                }
                Frame(lum, w, h)
            } finally {
                image.close()
            }
            finish(frame)
        }, handler)

        main.post {
            try {
                val d = display
                if (d == null) {
                    display = p.createVirtualDisplay(
                        "grid-of-thieves-scan", w, h, densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
                    )
                } else {
                    if (w != displayW || h != displayH) d.resize(w, h, densityDpi)
                    d.surface = reader.surface
                }
                displayW = w
                displayH = h
            } catch (e: RuntimeException) {
                // Projeção revogada no meio do caminho (SecurityException e afins).
                handler.post { finish(null) }
                return@post
            }
            handler.postDelayed({ finish(null) }, 2500)
        }
    }

    fun release() {
        display?.release()
        display = null
        val p = projection
        projection = null
        p?.stop()
    }

    fun shutdown() {
        release()
        thread?.quitSafely()
        thread = null
        worker = null
    }
}
