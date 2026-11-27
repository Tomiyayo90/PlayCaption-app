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

    /** Automatický preklad: text na obrazovke sa číta v telefóne a preklad sa ukáže v okne vedľa. */
    var automat: Boolean
        get() = prefs?.getBoolean("automat", false) ?: false
        set(v) {
            prefs?.edit()?.putBoolean("automat", v)?.apply()
            if (!v) { cakajuci = null; zobrazeno = false; prazdnych = 0 }
        }

    fun init(c: Context) {
        app = c.applicationContext
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("preklad_obrazovky", Context.MODE_PRIVATE)
    }

    // ---- ladenie ----
    private const val INTERVAL_MS = 600L    // ako často sa číta obraz (OCR v telefóne je zadarmo)
    private const val MIN_PISMEN = 20       // koľko písmen musí byť na obrazovke, aby to bolo menu/správa
    private const val MAX_ZA_MINUTU = 12    // poistka proti zbytočným volaniam Gemini
    private const val SIRKA_OKNA = 0.20f    // šírka okna s prekladom (podiel šírky obrazu)
    private const val PISMO_PX = 20f        // približná veľkosť písma v okne (obraz 1920 × 1080)

    @Volatile private var posl = 0L
    @Volatile private var zobrazeno = false
    @Volatile private var zobrazeneSlova: Set<String> = emptySet()
    @Volatile private var cakajuci: Set<String>? = null
    @Volatile private var prazdnych = 0
    @Volatile private var poslanoKedy = 0L
    @Volatile private var posledneBloky: List<Propojeni.Vysilac.Blok> = emptyList()
    private val zaneprazdneny = java.util.concurrent.atomic.AtomicBoolean(false)
    private val casy = java.util.ArrayDeque<Long>()
    private val rozpoznavac by lazy {
        com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
    }

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
        if (automat) runCatching { automaticky(f, jazyk) }
    }

    private class Kus(val text: String, val l: Float, val t: Float, val r: Float, val b: Float, val moje: Boolean = false)

    private fun slova(kusy: List<Kus>): Set<String> =
        kusy.joinToString(" ") { it.text }.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 3 }.toSet()

    private fun podobne(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val spolu = a.count { it in b }
        return spolu.toDouble() / (a.size + b.size - spolu) >= 0.7
    }

    private fun automaticky(f: org.webrtc.VideoFrame, jazyk: String) {
        val now = System.currentTimeMillis()
        if (now - posl < INTERVAL_MS) return
        posl = now
        if (!Gemini.pripravene()) return
        if (!zaneprazdneny.compareAndSet(false, true)) return
        val jpeg = try { naJpeg(f) } catch (_: Throwable) { null }
        if (jpeg == null) { zaneprazdneny.set(false); return }
        Thread {
            try { okno(jpeg, jazyk) }
            catch (_: Exception) { /* automat mlčí, chyby sa neukazujú pri každom snímku */ }
            finally { zaneprazdneny.set(false) }
        }.start()
    }

    /** Prečíta text z obrazu (OCR v telefóne) a nechá si ho preložiť. */
    private fun okno(jpeg: ByteArray, jazyk: String) {
        val plna = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val mala = if (plna.width > 1280) {
            val m = android.graphics.Bitmap.createScaledBitmap(plna, 1280, plna.height * 1280 / plna.width, true)
            plna.recycle(); m
        } else plna
        val w = mala.width.toFloat(); val h = mala.height.toFloat()
        val vysledok = try {
            com.google.android.gms.tasks.Tasks.await(
                rozpoznavac.process(com.google.mlkit.vision.common.InputImage.fromBitmap(mala, 0)),
                6, java.util.concurrent.TimeUnit.SECONDS)
        } finally { mala.recycle() }
        val oblastTit = if (Gemini.hraId == "gta6") floatArrayOf(0.17f, 0.78f, 0.82f, 0.95f) else null
        val meno = Regex("^(Jason|Lucia)\\b[:\\s]*(.*)$", RegexOption.IGNORE_CASE)
        val kusy = vysledok.textBlocks.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            var text = b.text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            var moje = false
            // správa, ktorú posielam ja: pred textom je meno hráča (Jason / Lucia)
            val m = meno.find(text)
            if (m != null && m.groupValues[2].isNotBlank()) { moje = true; text = m.groupValues[2].trim() }
            if (text.count { it.isLetter() } < 3) return@mapNotNull null
            val k = Kus(text, r.left / w, r.top / h, r.right / w, r.bottom / h, moje)
            val sx = (k.l + k.r) / 2; val sy = (k.t + k.b) / 2
            // HUD: lišta kariet hore, peniaze vpravo hore, nápovedy tlačidiel vpravo dole
            if (sy < 0.13f) return@mapNotNull null
            if (sx > 0.78f && sy < 0.22f) return@mapNotNull null
            if (sx > 0.70f && sy > 0.60f) return@mapNotNull null
            // krátky nápis iba veľkými písmenami (tlačidlo, záložka) nie je obsah
            val slov = text.split(' ').size
            if (slov <= 2 && text.none { it.isLowerCase() }) return@mapNotNull null
            // dialógové titulky dole v strede rieši bežný preklad titulkov
            if (oblastTit != null && sx in oblastTit[0]..oblastTit[2] && sy in oblastTit[1]..oblastTit[3] && k.b - k.t < 0.07f)
                return@mapNotNull null
            k
        }.sortedWith(compareBy<Kus>({ it.t }, { it.l })).take(14)

        val pismen = kusy.sumOf { k -> k.text.count { it.isLetter() } }
        if (pismen < MIN_PISMEN) {
            cakajuci = null
            prazdnych++
            if (prazdnych >= 3) {
                zobrazeneSlova = emptySet()
                if (zobrazeno) {
                    zobrazeno = false; posledneBloky = emptyList()
                    Propojeni.Vysilac.poslatBloky(emptyList(), 1000)
                }
            }
            return
        }
        prazdnych = 0
        val sig = slova(kusy)
        val ted = System.currentTimeMillis()
        // už zobrazené: iba obnoviť, keď preklady na TV po dvoch minútach vypršia
        if (podobne(sig, zobrazeneSlova)) {
            cakajuci = null
            if (zobrazeno && ted - poslanoKedy > 90_000 && posledneBloky.isNotEmpty()) {
                poslanoKedy = ted
                Propojeni.Vysilac.poslatBloky(posledneBloky, 120_000)
            }
            return
        }
        // nový obsah: počkať na druhé rovnaké čítanie (kamera sa chvíľu hýbe, OCR kolíše)
        val c = cakajuci
        if (c == null || !podobne(sig, c)) { cakajuci = sig; return }
        cakajuci = null
        synchronized(casy) {
            while (casy.isNotEmpty() && ted - casy.peekFirst() > 60_000) casy.removeFirst()
            if (casy.size >= MAX_ZA_MINUTU) return
            casy.addLast(ted)
        }
        val preklady = Gemini.prelozViac(kusy.map { it.text }, jazyk)
        Spotreba.pridaj("obrazovky")
        fun cisty(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        // vynechať to, čo model označil za zbytočné (prázdne), a to, čo sa nezmenilo (mená, názvy)
        val pary = kusy.indices.filter { preklady[it].isNotBlank() && cisty(preklady[it]) != cisty(kusy[it].text) }
            .map { kusy[it] to preklady[it] }
        zobrazeneSlova = sig
        if (pary.isEmpty()) {
            if (zobrazeno) {
                zobrazeno = false; posledneBloky = emptyList()
                Propojeni.Vysilac.poslatBloky(emptyList(), 1000)
            }
            return
        }
        val bloky = rozlozOkno(pary)
        if (Propojeni.Vysilac.poslatBloky(bloky, 120_000)) {
            zobrazeno = true; posledneBloky = bloky; poslanoKedy = System.currentTimeMillis()
        }
    }

    /**
     * Okno vedľa pôvodného textu: úzke bubliny. Správy, ktoré dostávam, majú svetlosivý podklad
     * a biele písmo, moje svetlomodrý podklad a biele písmo, všetky s čiernym orámovaním.
     */
    private fun rozlozOkno(pary: List<Pair<Kus, String>>): List<Propojeni.Vysilac.Blok> {
        val kusy = pary.map { it.first }
        val ul = kusy.minOf { it.l }; val ur = kusy.maxOf { it.r }; val ut = kusy.minOf { it.t }
        val sirka = SIRKA_OKNA
        val x = if (ul >= 1f - ur) maxOf(0.02f, ul - sirka - 0.012f)
                else minOf(1f - sirka - 0.02f, ur + 0.012f)
        val vrch = ut.coerceIn(0.06f, 0.30f)
        val znakovNaRiadok = (sirka * 1920f * 0.88f / (PISMO_PX * 0.52f)).toInt().coerceAtLeast(12)
        val vysky = pary.map { (_, t) ->
            val riadkov = Math.ceil(t.length.toDouble() / znakovNaRiadok).toInt().coerceAtLeast(1)
            (riadkov * PISMO_PX * 1.15f + 12f) / 1080f
        }
        val medzera = 0.008f
        var spolu = vysky.sum() + medzera * (vysky.size - 1)
        val maxVyska = 0.88f - vrch
        val mierka = if (spolu > maxVyska) maxVyska / spolu else 1f
        val cierna = 0xFF000000.toInt(); val biela = 0xFFFFFFFF.toInt()
        val vysl = ArrayList<Propojeni.Vysilac.Blok>()
        var y = vrch
        for ((i, par) in pary.withIndex()) {
            val v = vysky[i] * mierka
            val pozadie = if (par.first.moje) 0xFF6FB1F2.toInt() else 0xFF9AA3AF.toInt()
            vysl.add(Propojeni.Vysilac.Blok(x, y, x + sirka, y + v, pozadie, biela, par.second, cierna))
            y += v + medzera * mierka
        }
        return vysl
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
