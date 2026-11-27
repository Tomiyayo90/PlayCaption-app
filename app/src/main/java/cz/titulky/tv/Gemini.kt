package cz.titulky.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Preklad cez Gemini (vlastný API kľúč), s kontextom hry a trvalou pamäťou prekladov.
 * Kľúč sa ukladá len v telefóne (SharedPreferences), nikde v kóde.
 */
object Gemini {
    const val VYCHODZI_MODEL = "gemini-3.5-flash-lite"

    private var prefs: SharedPreferences? = null
    private var subor: File? = null
    private val pamet = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 5000
    }
    private val predchadzajuce = ArrayDeque<String>()
    @Volatile private var pauzaDo = 0L
    @Volatile var poslednaChyba = ""
        private set
    private var neulozene = 0

    @Synchronized
    fun init(c: Context) {
        if (prefs != null) return
        val app = c.applicationContext
        prefs = app.getSharedPreferences("gemini", Context.MODE_PRIVATE)
        val f = File(app.filesDir, "gemini_cache.json")
        subor = f
        Spotreba.init(app)
        Dabing.init(app)
        runCatching {
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val kluce = o.keys()
                while (kluce.hasNext()) {
                    val k = kluce.next()
                    pamet[k] = o.getString(k)
                }
            }
        }
    }

    var klic: String
        get() = Sifra.desifruj(prefs?.getString("klic", "") ?: "")
        set(v) { prefs?.edit()?.putString("klic", Sifra.zasifruj(v.trim()))?.apply() }

    var zapnute: Boolean
        get() = prefs?.getBoolean("zapnute", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("zapnute", v)?.apply() }

    var model: String
        get() = (prefs?.getString("model", null) ?: VYCHODZI_MODEL).ifBlank { VYCHODZI_MODEL }
        set(v) { prefs?.edit()?.putString("model", v.trim())?.apply() }

    /** Id vybranej hry z Hry.vsetky; prázdne = iná hra (všeobecný kontext). */
    var hraId: String
        get() = prefs?.getString("hra", "gta6") ?: "gta6"
        set(v) { prefs?.edit()?.putString("hra", v)?.apply() }

    /** Vlastné poznámky, ktoré sa pridajú ku kontextu hry. */
    var poznamky: String
        get() = prefs?.getString("poznamky", "") ?: ""
        set(v) { prefs?.edit()?.putString("poznamky", v.trim())?.apply() }

    fun nazovHry(): String = Hry.najdi(hraId)?.title ?: "Iná hra (všeobecne)"

    /** Kontext pre model: popis hry, mená ktoré sa neprekladajú a vlastné poznámky. */
    fun kontext(jazyk: String): String {
        val l = nazovJazyka(jazyk)
        val hra = Hry.najdi(hraId)
        val sb = StringBuilder()
        if (hra != null) {
            sb.append("Game: ").append(hra.title).append(".\n").append(hra.notes.replace("{L}", l))
            if (hra.keep.isNotEmpty()) sb.append("\nNever translate these names: ").append(hra.keep.joinToString(", ")).append(".")
        } else {
            sb.append("Game: an unknown video game. Translate naturally and keep names of characters, places and brands unchanged.")
        }
        sb.append("\nUI hints, button prompts and mission objectives should be short and imperative.")
        val extra = poznamky
        if (extra.isNotBlank()) sb.append("\nExtra notes: ").append(extra)
        return sb.toString()
    }

    fun pripravene(): Boolean = prefs != null && zapnute && klic.isNotBlank()

    fun pocetVPameti(): Int = synchronized(pamet) { pamet.size }

    fun vymazPamet() {
        synchronized(pamet) { pamet.clear() }
        runCatching { subor?.delete() }
    }

    /** Vráti preklad, alebo null (vypnuté / chyba) – vtedy sa použije záložný prekladač. */
    fun preloz(text: String, jazyk: String): String? {
        if (!pripravene()) return null
        val kluc = jazyk + "|" + kontext(jazyk).hashCode() + "|" + text
        val zPamete = synchronized(pamet) { pamet[kluc] }
        if (zPamete != null) { zapamataj(text); Spotreba.pridaj("pamet"); return zPamete }
        if (System.currentTimeMillis() < pauzaDo) return null
        val vysledok = try {
            volaj(text, jazyk).also { poslednaChyba = "" }
        } catch (e: Exception) {
            poslednaChyba = e.message ?: e.javaClass.simpleName
            pauzaDo = System.currentTimeMillis() + 20_000L
            null
        }
        if (vysledok != null) {
            Spotreba.pridaj("preklady")
            synchronized(pamet) { pamet[kluc] = vysledok }
            zapamataj(text)
            neulozene++
            if (neulozene >= 15) { neulozene = 0; Thread { uloz() }.start() }
        }
        return vysledok
    }

    /** Na test v nastaveniach – vyhodí výnimku s popisom chyby. */
    fun test(jazyk: String): String = volaj("Hey, you got the keys? We gotta bounce.", jazyk)

    private fun zapamataj(text: String) {
        synchronized(predchadzajuce) {
            predchadzajuce.addLast(text)
            while (predchadzajuce.size > 3) predchadzajuce.removeFirst()
        }
    }

    fun uloz() {
        val f = subor ?: return
        runCatching {
            val o = JSONObject()
            synchronized(pamet) { for ((k, v) in pamet) o.put(k, v) }
            f.writeText(o.toString())
        }
    }

    /** Pamäť prekladov ako JSON text (na zálohu). */
    fun exportuj(): String {
        val o = JSONObject()
        synchronized(pamet) { for ((k, v) in pamet) o.put(k, v) }
        return o.toString()
    }

    /** Pridá preklady zo zálohy; vráti počet načítaných záznamov. */
    fun importuj(json: String): Int {
        val o = JSONObject(json)
        var n = 0
        val kluce = o.keys()
        synchronized(pamet) {
            while (kluce.hasNext()) { val k = kluce.next(); pamet[k] = o.getString(k); n++ }
        }
        uloz()
        return n
    }

    private fun posliObrazok(system: String, jpeg: ByteArray, otazka: String, json: Boolean, maxTokenov: Int): String {
        val img = JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg")
            .put("data", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)))
        val konfig = JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokenov)
        if (json) konfig.put("responseMimeType", "application/json")
        val telo = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(img).put(JSONObject().put("text", otazka)))))
            .put("generationConfig", konfig)
        val s = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            s.connectTimeout = 6000; s.readTimeout = 40000
            s.requestMethod = "POST"; s.doOutput = true
            s.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            s.setRequestProperty("x-goog-api-key", klic)
            s.outputStream.use { it.write(telo.toString().toByteArray(Charsets.UTF_8)) }
            val kod = s.responseCode
            val prud = if (kod in 200..299) s.inputStream else s.errorStream
            val odp = prud?.bufferedReader()?.use { it.readText() } ?: ""
            if (kod !in 200..299) throw IllegalStateException("HTTP $kod: " + odp.take(200))
            val casti = JSONObject(odp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until casti.length()) sb.append(casti.getJSONObject(i).optString("text", ""))
            return sb.toString().trim()
        } finally { s.disconnect() }
    }

    @Volatile private var bezThinking = false

    /**
     * Preloží viac krátkych textov z obrazovky naraz (menu, správy z mobilu, ciele). Jedno volanie,
     * hotové vety sa berú z pamäte. Vráti preklady v rovnakom poradí.
     */
    fun prelozViac(texty: List<String>, jazyk: String): List<String> {
        if (!pripravene()) throw IllegalStateException("Gemini nie je pripravené")
        val kont = kontext(jazyk).hashCode()
        fun kluc(t: String) = jazyk + "|" + kont + "|" + t
        val vysl = arrayOfNulls<String>(texty.size)
        val chyba = ArrayList<Int>()
        synchronized(pamet) {
            for (i in texty.indices) {
                val z = pamet[kluc(texty[i])]
                if (z != null) vysl[i] = z else chyba.add(i)
            }
        }
        Spotreba.pridaj("pamet", texty.size - chyba.size)
        if (chyba.isNotEmpty()) {
            val system = "You translate on-screen texts of a video game interface (menus, phone messages, mission " +
                "objectives, hints) into ${nazovJazyka(jazyk)}. The input is a JSON array of strings; return ONLY a JSON " +
                "array of strings with exactly the same length and order. Short and natural, keep names, numbers and " +
                "codes unchanged. If a string is already in ${nazovJazyka(jazyk)}, return it as is. " +
                "Texts come from OCR and may contain small errors, fix them silently.\n\nGame context:\n" + kontext(jazyk)
            val vstup = JSONArray()
            for (i in chyba) vstup.put(texty[i])
            var odp = volajText(system, vstup.toString(), 2500)
            odp = odp.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val od = odp.indexOf('['); val do_ = odp.lastIndexOf(']')
            if (od < 0 || do_ <= od) throw IllegalStateException("Neplatná odpoveď: " + odp.take(120))
            val pole = JSONArray(odp.substring(od, do_ + 1))
            if (pole.length() != chyba.size) throw IllegalStateException("Počet prekladov nesedí")
            Spotreba.pridaj("preklady", chyba.size)
            synchronized(pamet) {
                for ((n, i) in chyba.withIndex()) {
                    val p = pole.optString(n, "").trim()
                    if (p.isNotEmpty()) { vysl[i] = p; pamet[kluc(texty[i])] = p }
                }
            }
            neulozene += chyba.size
            if (neulozene >= 15) { neulozene = 0; Thread { uloz() }.start() }
        }
        return vysl.mapIndexed { i, v -> v ?: texty[i] }
    }

    /** Textové volanie so zapnutým JSON režimom; rozmýšľanie sa vypína kvôli rýchlosti, ak ho model podporuje. */
    private fun volajText(system: String, user: String, maxTokenov: Int): String {
        fun pokus(bezMyslenia: Boolean): String {
            val konfig = JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokenov)
                .put("responseMimeType", "application/json")
            if (bezMyslenia) konfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val telo = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", user)))))
                .put("generationConfig", konfig)
            val s = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            try {
                s.connectTimeout = 4000; s.readTimeout = 15000
                s.requestMethod = "POST"; s.doOutput = true
                s.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                s.setRequestProperty("x-goog-api-key", klic)
                s.outputStream.use { it.write(telo.toString().toByteArray(Charsets.UTF_8)) }
                val kod = s.responseCode
                val prud = if (kod in 200..299) s.inputStream else s.errorStream
                val odp = prud?.bufferedReader()?.use { it.readText() } ?: ""
                if (kod !in 200..299) throw IllegalStateException("HTTP $kod: " + odp.take(200))
                val casti = JSONObject(odp).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until casti.length()) sb.append(casti.getJSONObject(i).optString("text", ""))
                return sb.toString().trim()
            } finally { s.disconnect() }
        }
        if (bezThinking) return pokus(false)
        return try { pokus(true) } catch (e: IllegalStateException) {
            if (e.message?.startsWith("HTTP 400") == true) { bezThinking = true; pokus(false) } else throw e
        }
    }

    /** Text z obrazovky s polohou: súradnice sú podiel šírky/výšky obrazu (0..1). */
    class Textik(val l: Float, val t: Float, val r: Float, val b: Float, val text: String)

    /** Preklad textu z obrazovky (menu, ciele misií, mobil v hre) cez obrázok – iba text. */
    fun obrazok(jpeg: ByteArray, jazyk: String): String {
        val system = "You read screenshots of a video game. Find ALL meaningful on-screen text " +
            "(menus, mission objectives, hints, in-game phone messages, map/GPS labels, subtitles) " +
            "and translate it into ${nazovJazyka(jazyk)}. Return only the translation as short lines, " +
            "no explanations. Skip logos and decoration. If there is no text, return a single dash.\n\n" +
            "Game context:\n" + kontext(jazyk)
        return posliObrazok(system, jpeg, "Translate the text on this screen.", false, 600).ifEmpty { "-" }
    }

    /**
     * Preklad textov z obrazovky spolu s ich polohou, aby sa dali nakresliť presne na pôvodné miesto.
     * Dialógové titulky v spodnej časti obrazu sa preskakujú (tie rieši bežný preklad titulkov).
     */
    fun obrazokBoxy(jpeg: ByteArray, jazyk: String): List<Textik> {
        val system = "You read screenshots of a video game and translate on-screen text into ${nazovJazyka(jazyk)}.\n" +
            "Return ONLY a JSON array. Each element is one text block (one menu row, one tab label, one paragraph " +
            "of a phone message, one objective line, one button hint) as an object: " +
            "{\"box_2d\": [ymin, xmin, ymax, xmax], \"text\": \"translation\"}. " +
            "box_2d is the tight box around the ORIGINAL text, with coordinates normalized to 0-1000. " +
            "Keep each translation short and natural; keep names, numbers, codes and brand names unchanged. " +
            "Skip: dialogue subtitles at the bottom centre of the screen, logos, car or street graffiti, " +
            "text that is part of the 3D scene, and anything already in ${nazovJazyka(jazyk)}. " +
            "If there is no text to translate, return [].\n\nGame context:\n" + kontext(jazyk)
        var odp = posliObrazok(system, jpeg, "Translate the on-screen text and return the JSON array.", true, 4000)
        odp = odp.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val od = odp.indexOf('['); val do_ = odp.lastIndexOf(']')
        if (od < 0 || do_ <= od) throw IllegalStateException("Neplatná odpoveď: " + odp.take(120))
        val pole = JSONArray(odp.substring(od, do_ + 1))
        val vysl = ArrayList<Textik>()
        for (i in 0 until pole.length()) {
            val o = pole.optJSONObject(i) ?: continue
            val b = o.optJSONArray("box_2d") ?: o.optJSONArray("box") ?: continue
            val text = o.optString("text", "").trim()
            if (b.length() < 4 || text.isEmpty()) continue
            val y0 = (b.optDouble(0) / 1000.0).toFloat(); val x0 = (b.optDouble(1) / 1000.0).toFloat()
            val y1 = (b.optDouble(2) / 1000.0).toFloat(); val x1 = (b.optDouble(3) / 1000.0).toFloat()
            if (x1 <= x0 || y1 <= y0) continue
            vysl.add(Textik(x0, y0, x1, y1, text))
        }
        return vysl
    }

    fun nazovJazyka(kod: String): String = when (kod) {
        "cs" -> "Czech"
        "sk" -> "Slovak"
        "de" -> "German"
        "pl" -> "Polish"
        "fr" -> "French"
        "es" -> "Spanish"
        "it" -> "Italian"
        else -> kod
    }

    private fun volaj(text: String, jazyk: String): String {
        val system = "You are a professional video game subtitle translator. " +
            "Translate the English game subtitle into ${nazovJazyka(jazyk)}.\n" +
            "Rules: return ONLY the translation, with no quotes, notes or explanations. " +
            "Keep it short and natural, as spoken dialogue. " +
            "The text comes from OCR and may contain small recognition errors - fix them silently.\n\n" +
            "Game context:\n" + kontext(jazyk)
        val predch = synchronized(predchadzajuce) { predchadzajuce.toList() }
        val user = if (predch.isEmpty()) text
        else "Previous lines (context only, do not translate):\n" + predch.joinToString("\n") +
            "\n\nTranslate this line:\n" + text
        val telo = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 300))
        val spojenie = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            spojenie.connectTimeout = 4000
            spojenie.readTimeout = 8000
            spojenie.requestMethod = "POST"
            spojenie.doOutput = true
            spojenie.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            spojenie.setRequestProperty("x-goog-api-key", klic)
            spojenie.outputStream.use { it.write(telo.toString().toByteArray(Charsets.UTF_8)) }
            val kod = spojenie.responseCode
            val prud = if (kod in 200..299) spojenie.inputStream else spojenie.errorStream
            val odpoved = prud?.bufferedReader()?.use { it.readText() } ?: ""
            if (kod !in 200..299) throw IllegalStateException("HTTP $kod: " + odpoved.take(200))
            val casti = JSONObject(odpoved).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until casti.length()) sb.append(casti.getJSONObject(i).optString("text", ""))
            val vysl = sb.toString().trim().trim('"', '„', '“', '”').trim()
            if (vysl.isEmpty()) throw IllegalStateException("prázdna odpoveď")
            return vysl
        } finally {
            spojenie.disconnect()
        }
    }
}
