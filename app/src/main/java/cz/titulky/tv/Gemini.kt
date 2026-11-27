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

    const val VYCHODZI_KONTEXT = """Game: Grand Theft Auto VI (Rockstar Games). Crime story set in the state of Leonida (a satire of Florida) and Vice City. Main characters: Jason and Lucia, a criminal couple.
Dialogue is street talk full of slang, jokes and profanity: translate into natural, informal spoken language, keep swearing at the same strength and keep jokes funny rather than literal.
Never translate names of people, places, brands or in-game apps (e.g. Leonida, Vice City, Port Gellhorn, Grassrivers, Ambrosia, Lucia, Jason, Bleeter, LifeInvader, Fleeca, Badger).
UI hints, button prompts and mission objectives should be short and imperative."""

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
        get() = prefs?.getString("klic", "") ?: ""
        set(v) { prefs?.edit()?.putString("klic", v.trim())?.apply() }

    var zapnute: Boolean
        get() = prefs?.getBoolean("zapnute", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("zapnute", v)?.apply() }

    var model: String
        get() = (prefs?.getString("model", null) ?: VYCHODZI_MODEL).ifBlank { VYCHODZI_MODEL }
        set(v) { prefs?.edit()?.putString("model", v.trim())?.apply() }

    var kontext: String
        get() = (prefs?.getString("kontext", null) ?: VYCHODZI_KONTEXT).ifBlank { VYCHODZI_KONTEXT }
        set(v) { prefs?.edit()?.putString("kontext", v.trim())?.apply() }

    fun pripravene(): Boolean = prefs != null && zapnute && klic.isNotBlank()

    fun pocetVPameti(): Int = synchronized(pamet) { pamet.size }

    fun vymazPamet() {
        synchronized(pamet) { pamet.clear() }
        runCatching { subor?.delete() }
    }

    /** Vráti preklad, alebo null (vypnuté / chyba) – vtedy sa použije záložný prekladač. */
    fun preloz(text: String, jazyk: String): String? {
        if (!pripravene()) return null
        val kluc = jazyk + "|" + kontext.hashCode() + "|" + text
        val zPamete = synchronized(pamet) { pamet[kluc] }
        if (zPamete != null) { zapamataj(text); return zPamete }
        if (System.currentTimeMillis() < pauzaDo) return null
        val vysledok = try {
            volaj(text, jazyk).also { poslednaChyba = "" }
        } catch (e: Exception) {
            poslednaChyba = e.message ?: e.javaClass.simpleName
            pauzaDo = System.currentTimeMillis() + 20_000L
            null
        }
        if (vysledok != null) {
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

    private fun uloz() {
        val f = subor ?: return
        runCatching {
            val o = JSONObject()
            synchronized(pamet) { for ((k, v) in pamet) o.put(k, v) }
            f.writeText(o.toString())
        }
    }

    private fun nazovJazyka(kod: String): String = when (kod) {
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
            "Game context:\n" + kontext
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
