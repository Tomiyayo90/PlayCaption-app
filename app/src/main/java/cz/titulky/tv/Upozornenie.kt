package cz.titulky.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Upozorní, keď sa dlho nezachytili žiadne titulky (v hre môžu byť vypnuté). */
object Upozornenie {
    private val ui = Handler(Looper.getMainLooper())
    private const val LIMIT_MS = 150_000L
    private var app: Context? = null
    @Volatile private var bezi = false
    @Volatile private var posledny = 0L
    @Volatile private var ukazane = false

    private val kontrola = object : Runnable {
        override fun run() {
            if (!bezi) return
            if (!ukazane && System.currentTimeMillis() - posledny > LIMIT_MS) {
                ukazane = true
                app?.let {
                    Toast.makeText(it, "Dlho som nezachytil žiadne titulky. Ak hráš dialógy, skontroluj, či sú v hre zapnuté anglické titulky.", Toast.LENGTH_LONG).show()
                }
            }
            ui.postDelayed(this, 15_000L)
        }
    }

    /** Streamovanie začalo alebo skončilo. */
    fun stav(c: Context, streamuje: Boolean) {
        app = c.applicationContext
        if (streamuje && !bezi) {
            bezi = true; ukazane = false; posledny = System.currentTimeMillis()
            ui.postDelayed(kontrola, 15_000L)
        } else if (!streamuje && bezi) {
            bezi = false
            ui.removeCallbacks(kontrola)
        }
    }

    /** Zavolá sa pri každom prečítanom titulku. */
    fun titulokViden() {
        posledny = System.currentTimeMillis()
        ukazane = false
    }
}
