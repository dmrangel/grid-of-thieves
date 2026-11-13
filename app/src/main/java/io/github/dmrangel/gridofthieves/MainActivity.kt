package io.github.dmrangel.gridofthieves

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * Só abre o overlay: pede a permissão de sobreposição se faltar, liga o [OverlayService]
 * e fecha na hora. Nada fica rodando aqui (nem invisível engolindo toques): abrir o ícone
 * de novo com o overlay ativo só expande o painel.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            Settings.canDrawOverlays(this) -> start()
            savedInstanceState == null -> {
                Toast.makeText(this, "Ative \"Sobrepor a outros apps\" para o Grid of Thieves", Toast.LENGTH_LONG).show()
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQUEST_OVERLAY)
            }
        }
    }

    @Deprecated("API de Activity da plataforma, sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_OVERLAY) return
        if (Settings.canDrawOverlays(this)) return start()
        // No Android 8.0 a permissão às vezes demora um instante para valer.
        window.decorView.postDelayed({
            if (Settings.canDrawOverlays(this)) {
                start()
            } else {
                Toast.makeText(this, "Sem a permissão de sobreposição o overlay não abre.", Toast.LENGTH_LONG).show()
                finish()
            }
        }, 600)
    }

    private fun start() {
        startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REQUEST_OVERLAY = 101
    }
}
