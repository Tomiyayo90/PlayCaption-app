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

    /**
     * Volá sa z vlákna obrazu pri každom snímku, kým sa čaká na snímok.
     * [zobraz] je záloha: keď televízia nie je pripojená, text sa ukáže ako bežný titulok.
     */
    fun snimek(f: org.webrtc.VideoFrame, jazyk: String, zobraz: (String) -> Unit) {
        if (!caka) return
        caka = false
        val jpeg = try { naJpeg(f) } catch (_: Throwable) { null }
        if (jpeg == null) { toast("Snímku sa nepodarilo spracovať."); return }
        Thread {
            try {
                val plna = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: throw IllegalStateException("snímku sa nepodarilo dekódovať")
                // pre Gemini stačí menší obraz (rýchlejšie), farby sa vzorkujú z plného
                val posielany = if (plna.width > 1280) {
                    val m = android.graphics.Bitmap.createScaledBitmap(plna, 1280, plna.height * 1280 / plna.width, true)
                    val o = ByteArrayOutputStream()
                    m.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, o)
                    if (m !== plna) m.recycle()
                    o.toByteArray()
                } else jpeg
                val texty = Gemini.obrazokBoxy(posielany, jazyk)
                Spotreba.pridaj("obrazovky")
                if (texty.isEmpty()) { toast("Na obrazovke som nenašiel text na preklad."); plna.recycle(); return@Thread }
                val bloky = texty.map { t ->
                    val l = (t.l - 0.003f).coerceAtLeast(0f); val r = (t.r + 0.003f).coerceAtMost(1f)
                    val u = (t.t - 0.004f).coerceAtLeast(0f); val d = (t.b + 0.004f).coerceAtMost(1f)
                    val pozadie = farbaOkolo(plna, l, u, r, d)
                    val jas = 0.299 * ((pozadie shr 16) and 255) + 0.587 * ((pozadie shr 8) and 255) + 0.114 * (pozadie and 255)
                    Propojeni.Vysilac.Blok(l, u, r, d, pozadie, if (jas > 140) 0xFF111111.toInt() else 0xFFFFFFFF.toInt(), t.text)
                }
                plna.recycle()
                if (!Propojeni.Vysilac.poslatBloky(bloky, 20000)) {
                    // televízia nie je pripojená – aspoň zoznam prekladov ako titulok
                    ui.post { zobraz(texty.joinToString("\n") { it.text }) }
                }
            } catch (e: Exception) {
                toast("Preklad obrazovky zlyhal: " + (e.message ?: e.javaClass.simpleName))
            }
        }.start()
    }

    /** Farba pozadia: medián pixelov tesne okolo rámčeka (mimo písma), aby sa pôvodný text prekryl. */
    private fun farbaOkolo(b: android.graphics.Bitmap, l: Float, t: Float, r: Float, d: Float): Int {
        val w = b.width; val h = b.height
        val pad = maxOf(3, (w * 0.004f).toInt())
        val x0 = (l * w).toInt() - pad; val x1 = (r * w).toInt() + pad
        val y0 = (t * h).toInt() - pad; val y1 = (d * h).toInt() + pad
        val cr = ArrayList<Int>(); val cg = ArrayList<Int>(); val cb = ArrayList<Int>()
        fun bod(x: Int, y: Int) {
            val px = b.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            cr.add((px shr 16) and 255); cg.add((px shr 8) and 255); cb.add(px and 255)
        }
        val krokX = maxOf(1, (x1 - x0) / 60); val krokY = maxOf(1, (y1 - y0) / 30)
        var x = x0
        while (x <= x1) { bod(x, y0); bod(x, y1); x += krokX }
        var y = y0
        while (y <= y1) { bod(x0, y); bod(x1, y); y += krokY }
        fun med(v: ArrayList<Int>): Int { v.sort(); return v[v.size / 2] }
        return (0xFF shl 24) or (med(cr) shl 16) or (med(cg) shl 8) or med(cb)
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
