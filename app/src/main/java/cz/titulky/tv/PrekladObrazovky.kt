package cz.titulky.tv

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Preklad celej obrazovky na požiadanie: vezme aktuálny snímok z PS5, pošle ho Gemini
 * (obrázok) a výsledok zobrazí ako titulok na TV. Pokrýva menu, ciele misií, nápovedy,
 * správy z telefónu v hre, GPS a text, ktorý bežné čítanie titulkov nezachytí.
 * Funguje len keď obraz ide cez pamäťovú cestu (v Zázname: „obraz: priamo do pamäte“).
 */
object PrekladObrazovky {
    @Volatile var caka = false
        private set
    private val ui = Handler(Looper.getMainLooper())
    private var app: Context? = null

    private fun toast(t: String) { app?.let { ui.post { Toast.makeText(it, t, Toast.LENGTH_LONG).show() } } }

    fun spusti(c: Context) {
        app = c.applicationContext
        Gemini.init(c)
        if (!Gemini.pripravene()) { toast("Najprv zadaj Gemini API kľúč v nastaveniach."); return }
        caka = true
        toast("Prekladám obrazovku…")
        ui.postDelayed({
            if (caka) {
                caka = false
                toast("Nepodarilo sa získať snímku obrazu. Tento režim potrebuje, aby obraz išiel priamo do pamäte (pozri Záznam).")
            }
        }, 5000)
    }

    /** Volá sa z vlákna obrazu pri každom snímku, kým sa čaká na snímok. */
    fun snimek(f: org.webrtc.VideoFrame, jazyk: String, zobraz: (String) -> Unit) {
        if (!caka) return
        caka = false
        val jpeg = try { naJpeg(f) } catch (_: Throwable) { null }
        if (jpeg == null) { toast("Snímku sa nepodarilo spracovať."); return }
        Thread {
            try {
                val text = Gemini.obrazok(jpeg, jazyk)
                Spotreba.pridaj("obrazovky")
                ui.post { zobraz(text) }
            } catch (e: Exception) {
                toast("Preklad obrazovky zlyhal: " + (e.message ?: e.javaClass.simpleName))
            }
        }.start()
    }

    private fun naJpeg(f: org.webrtc.VideoFrame): ByteArray? {
        val i = f.buffer.toI420() ?: return null
        try {
            val w = i.width and 1.inv()
            val h = i.height and 1.inv()
            val nv = ByteArray(w * h * 3 / 2)
            val y = i.dataY.duplicate()
            for (r in 0 until h) { y.position(r * i.strideY); y.get(nv, r * w, w) }
            val u = i.dataU.duplicate(); val v = i.dataV.duplicate()
            var p = w * h
            for (r in 0 until h / 2) for (c in 0 until w / 2) {
                nv[p++] = v.get(r * i.strideV + c)
                nv[p++] = u.get(r * i.strideU + c)
            }
            val out = ByteArrayOutputStream()
            YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 80, out)
            return out.toByteArray()
        } finally {
            i.release()
        }
    }
}
