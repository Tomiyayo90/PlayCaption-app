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

    private var prefs: android.content.SharedPreferences? = null

    /** Automatický preklad: obrazovka sa preloží sama, keď sa zastaví a zmení (menu, mobil, ciele). */
    var automat: Boolean
        get() = prefs?.getBoolean("automat", false) ?: false
        set(v) {
            prefs?.edit()?.putBoolean("automat", v)?.apply()
            if (!v) { predch = null; analyzovany = null; zobrazeno = false }
        }

    fun init(c: Context) {
        app = c.applicationContext
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("preklad_obrazovky", Context.MODE_PRIVATE)
    }

    // ---- ladenie citlivosti (podiel buniek, ktoré sa zmenili) ----
    private const val STABILNE = 0.010      // medzi dvoma vzorkami menej = obraz stojí
    private const val NOVY_OBSAH = 0.015    // oproti preloženému obrazu viac = nový obsah, treba preložiť
    private const val INA_SCENA = 0.35      // viac = menu zmizlo, preklady sa schovajú
    private const val MIN_ODSTUP_MS = 4000L // najmenší odstup medzi dvoma prekladmi
    private const val MAX_ZA_MINUTU = 8     // poistka proti zbytočnému plateniu

    @Volatile private var posl = 0L
    @Volatile private var predch: ByteArray? = null
    @Volatile private var analyzovany: ByteArray? = null
    @Volatile private var zobrazeno = false
    @Volatile private var poslanoKedy = 0L
    @Volatile private var poslednyPreklad = 0L
    @Volatile private var posledneBloky: List<Propojeni.Vysilac.Blok> = emptyList()
    private val zaneprazdneny = java.util.concurrent.atomic.AtomicBoolean(false)
    private val casy = java.util.ArrayDeque<Long>()

    private fun toast(t: String) { app?.let { ui.post { Toast.makeText(it, t, Toast.LENGTH_LONG).show() } } }

    fun spusti(c: Context) {
        init(c)
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

    /** Volá sa z vlákna obrazu pri každom snímku (jednorazový preklad aj automatický). */
    fun priSnimku(c: Context, f: org.webrtc.VideoFrame, jazyk: String, zobraz: (String) -> Unit) {
        if (prefs == null) init(c)
        if (caka) { snimek(f, jazyk, zobraz); return }
        if (automat) runCatching { automaticky(f, jazyk, zobraz) }
    }

    private fun odtlacok(f: org.webrtc.VideoFrame): ByteArray? {
        val i = f.buffer.toI420() ?: return null
        try {
            val w = i.width; val h = i.height
            val gx = 96; val gy = 54
            val out = ByteArray(gx * gy)
            val y = i.dataY.duplicate()
            for (cy in 0 until gy) {
                val py = (cy * 2 + 1) * h / (gy * 2)
                for (cx in 0 until gx) {
                    val px = (cx * 2 + 1) * w / (gx * 2)
                    out[cy * gx + cx] = y.get(py * i.strideY + px)
                }
            }
            return out
        } finally { i.release() }
    }

    /** Podiel buniek, ktoré sa výrazne zmenili. */
    private fun rozdiel(a: ByteArray, b: ByteArray): Double {
        var n = 0
        for (k in a.indices) if (Math.abs((a[k].toInt() and 0xFF) - (b[k].toInt() and 0xFF)) > 25) n++
        return n.toDouble() / a.size
    }

    private fun automaticky(f: org.webrtc.VideoFrame, jazyk: String, zobraz: (String) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - posl < 500) return
        posl = now
        if (!Gemini.pripravene()) return
        val o = odtlacok(f) ?: return
        val p = predch
        predch = o
        if (p == null) return
        val stabilny = rozdiel(p, o) < STABILNE
        val a = analyzovany
        val zmena = if (a == null) 1.0 else rozdiel(a, o)

        // menu zmizlo / úplne iná scéna → preklady schovať
        if (zobrazeno && zmena > INA_SCENA) {
            zobrazeno = false
            posledneBloky = emptyList()
            Propojeni.Vysilac.poslatBloky(emptyList(), 1000)
        }
        // dlho stojace menu: preklady na TV po dvoch minútach vypršia, tak sa obnovia
        if (zobrazeno && now - poslanoKedy > 90_000 && posledneBloky.isNotEmpty()) {
            poslanoKedy = now
            Propojeni.Vysilac.poslatBloky(posledneBloky, 120_000)
        }
        if (!stabilny || zmena < NOVY_OBSAH) return
        if (now - poslednyPreklad < MIN_ODSTUP_MS) return
        synchronized(casy) {
            while (casy.isNotEmpty() && now - casy.peekFirst() > 60_000) casy.removeFirst()
            if (casy.size >= MAX_ZA_MINUTU) return
            casy.addLast(now)
        }
        if (!zaneprazdneny.compareAndSet(false, true)) return
        val jpeg = try { naJpeg(f) } catch (_: Throwable) { null }
        if (jpeg == null) { zaneprazdneny.set(false); return }
        analyzovany = o
        poslednyPreklad = now
        Thread {
            try { spracuj(jpeg, jazyk, zobraz, true) }
            catch (_: Exception) { /* automat mlčí, chyby sa neukazujú pri každom snímku */ }
            finally { zaneprazdneny.set(false) }
        }.start()
    }

    /**
     * Jednorazový preklad (tlačidlo).
     * [zobraz] je záloha: keď televízia nie je pripojená, text sa ukáže ako bežný titulok.
     */
    fun snimek(f: org.webrtc.VideoFrame, jazyk: String, zobraz: (String) -> Unit) {
        if (!caka) return
        caka = false
        val jpeg = try { naJpeg(f) } catch (_: Throwable) { null }
        if (jpeg == null) { toast("Snímku sa nepodarilo spracovať."); return }
        Thread {
            try { spracuj(jpeg, jazyk, zobraz, false) }
            catch (e: Exception) { toast("Preklad obrazovky zlyhal: " + (e.message ?: e.javaClass.simpleName)) }
        }.start()
    }

    private fun spracuj(jpeg: ByteArray, jazyk: String, zobraz: (String) -> Unit, ticho: Boolean) {
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
        if (texty.isEmpty()) {
            plna.recycle()
            if (!ticho) toast("Na obrazovke som nenašiel text na preklad.")
            return
        }
        val bloky = texty.map { t ->
            val l = (t.l - 0.003f).coerceAtLeast(0f); val r = (t.r + 0.003f).coerceAtMost(1f)
            val u = (t.t - 0.004f).coerceAtLeast(0f); val d = (t.b + 0.004f).coerceAtMost(1f)
            val pozadie = farbaOkolo(plna, l, u, r, d)
            val jas = 0.299 * ((pozadie shr 16) and 255) + 0.587 * ((pozadie shr 8) and 255) + 0.114 * (pozadie and 255)
            Propojeni.Vysilac.Blok(l, u, r, d, pozadie, if (jas > 140) 0xFF111111.toInt() else 0xFFFFFFFF.toInt(), t.text)
        }
        plna.recycle()
        val doba = if (ticho) 120_000L else 20_000L
        if (Propojeni.Vysilac.poslatBloky(bloky, doba)) {
            if (ticho) { zobrazeno = true; posledneBloky = bloky; poslanoKedy = System.currentTimeMillis() }
        } else if (!ticho) {
            // televízia nie je pripojená – aspoň zoznam prekladov ako titulok
            ui.post { zobraz(texty.joinToString("\n") { it.text }) }
        }
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
