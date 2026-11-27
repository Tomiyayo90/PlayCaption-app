package cz.titulky.tv

import android.content.Context
import android.content.SharedPreferences

/** Počítadlo využitia (koľko volaní AI a hlasov). Čísla slúžia na orientáciu, nie ako účet od poskytovateľa. */
object Spotreba {
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("spotreba", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun pridaj(kluc: String, n: Int = 1) {
        val p = prefs ?: return
        p.edit().putLong(kluc, p.getLong(kluc, 0) + n).apply()
    }

    fun get(kluc: String): Long = prefs?.getLong(kluc, 0) ?: 0

    @Synchronized
    fun vynuluj() { prefs?.edit()?.clear()?.apply() }

    /** Riadky na zobrazenie. */
    fun riadky(): List<String> = listOf(
        "Preklady cez Gemini: " + get("preklady") + " (z pamäte: " + get("pamet") + ")",
        "Preložené obrazovky: " + get("obrazovky"),
        "Hlasy Gemini: " + get("hlasGemini"),
        "Hlasy ElevenLabs: " + get("hlasEleven") + " (" + get("znakyEleven") + " znakov)",
        "Hlasy z pamäte (zadarmo): " + get("hlasCache")
    )
}
