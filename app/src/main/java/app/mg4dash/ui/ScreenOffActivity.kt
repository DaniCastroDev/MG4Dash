package app.mg4dash.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import app.mg4dash.car.CarBridge

/**
 * Écran éteint (double toucher sur Énergie dans la barre verticale, issue #12) : le rétroéclairage
 * est coupé par la même commande que le launcher d'origine ; un écran noir reste par-dessus pour
 * capter le toucher qui le rallume. Rallumage aussi dès que la voiture roule, ou si l'activité
 * perd la main (autre appli, appel), pour ne jamais laisser l'écran éteint par erreur.
 */
class ScreenOffActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var off = false

    private val watch = object : Runnable {
        override fun run() {
            if ((CarBridge.state.value.speedKmh ?: 0f) > 5f) { wake(); return }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        Fullscreen.apply(this)
        // Petit délai : le doigt du double toucher est levé avant l'extinction
        handler.postDelayed({ off = CarBridge.setDisplay(false); if (!off) finish() }, 300)
        handler.postDelayed(watch, 1000)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) wake()
        return true
    }

    private fun wake() {
        handler.removeCallbacksAndMessages(null)
        if (off) { CarBridge.setDisplay(true); off = false }
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) wake()
    }

    override fun onDestroy() {
        if (off) CarBridge.setDisplay(true)
        super.onDestroy()
    }
}
