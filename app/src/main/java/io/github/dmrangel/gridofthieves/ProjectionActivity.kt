package io.github.dmrangel.gridofthieves

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/**
 * Tela transparente que só mostra o pedido do sistema para capturar a tela e devolve a
 * resposta ao [OverlayService]. Aparece uma vez por sessão do overlay.
 */
class ProjectionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: pede direto a tela inteira (a opção "um app só" não serve aqui).
        val intent = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    @Deprecated("API de Activity da plataforma, sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CAPTURE) return
        startService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_PROJECTION)
                .putExtra(OverlayService.EXTRA_CODE, resultCode)
                .putExtra(OverlayService.EXTRA_DATA, data),
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REQUEST_CAPTURE = 102
    }
}
