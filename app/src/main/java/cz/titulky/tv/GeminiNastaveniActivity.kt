package cz.titulky.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Nastavenia prekladu (Gemini) a dabingu – prehľadné sekcie v modro-čiernom vzhľade. */
class GeminiNastaveniActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        Gemini.init(this)
        window.statusBarColor = Tema.POZADIE2
        window.navigationBarColor = Tema.POZADIE

        val koren = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val p = Tema.dp(c, 16)
            setPadding(p, Tema.dp(c, 28), p, p * 2)
        }
        fun popis(t: String) = Tema.text(c, t, 13f, Tema.SLABY).apply { setPadding(0, Tema.dp(c, 12), 0, Tema.dp(c, 2)) }

        koren.addView(Tema.text(c, "Nastavenia", 28f, Color.WHITE, true))

        // ---- Preklad
        koren.addView(Tema.nadpisSekcie(c, "Preklad (Gemini)"))
        val prekl = Tema.kartaBox(c)
        val prepinac = Tema.prepinac(c, "Používať Gemini preklad", Gemini.zapnute) {}
        prekl.addView(prepinac)
        prekl.addView(popis("API kľúč (aistudio.google.com/apikey)"))
        val klic = Tema.pole(c, Gemini.klic, heslo = true); prekl.addView(klic)
        prekl.addView(popis("Model"))
        val model = Tema.pole(c, Gemini.model); prekl.addView(model)
        koren.addView(prekl, Tema.lp(c))

        // ---- Hra
        koren.addView(Tema.nadpisSekcie(c, "Kontext hry"))
        val hra = Tema.kartaBox(c)
        hra.addView(popis("Pomáha prekladu (postavy, slang, mená ktoré sa neprekladajú)"))
        val kontext = Tema.pole(c, Gemini.kontext, viac = true); hra.addView(kontext)
        hra.addView(Tema.tlacidlo(c, "Obnoviť predvolený kontext GTA 6", false) {
            kontext.setText(Gemini.VYCHODZI_KONTEXT)
        }, Tema.lp(c, 12))
        koren.addView(hra, Tema.lp(c))

        // ---- Dabing
        koren.addView(Tema.nadpisSekcie(c, "Dabing"))
        val dab = Tema.kartaBox(c)
        dab.addView(popis("Hlas (prehráva sa v telefóne alebo v slúchadlách)"))
        val rezim = Spinner(c).apply {
            adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item,
                listOf("Vypnuté (len titulky)", "Gemini hlas", "ElevenLabs hlas"))
            setSelection(Dabing.rezim.coerceIn(0, 2))
        }
        dab.addView(rezim)
        dab.addView(popis("Model Gemini hlasu"))
        val ttsModel = Tema.pole(c, Dabing.ttsModel); dab.addView(ttsModel)
        dab.addView(popis("ElevenLabs API kľúč"))
        val elKlic = Tema.pole(c, Dabing.elevenKlic, heslo = true); dab.addView(elKlic)
        dab.addView(popis("ElevenLabs ID ženského hlasu"))
        val elZena = Tema.pole(c, Dabing.elevenHlasZena); dab.addView(elZena)
        dab.addView(popis("ElevenLabs ID mužského hlasu"))
        val elMuz = Tema.pole(c, Dabing.elevenHlasMuz); dab.addView(elMuz)
        koren.addView(dab, Tema.lp(c))

        // ---- Akcie
        val vysledok: TextView = Tema.text(c, "", 14f, Tema.AKCENT2).apply { setPadding(0, Tema.dp(c, 12), 0, 0) }
        fun ulozVsetko() {
            Gemini.zapnute = prepinac.isChecked
            Gemini.klic = klic.text.toString()
            Gemini.model = model.text.toString()
            Gemini.kontext = kontext.text.toString()
            Dabing.rezim = rezim.selectedItemPosition
            Dabing.ttsModel = ttsModel.text.toString()
            Dabing.elevenKlic = elKlic.text.toString()
            Dabing.elevenHlasZena = elZena.text.toString()
            Dabing.elevenHlasMuz = elMuz.text.toString()
        }
        koren.addView(Tema.nadpisSekcie(c, "Akcie"))
        koren.addView(Tema.tlacidlo(c, "Uložiť", true) { ulozVsetko(); vysledok.text = "Uložené." }, Tema.lp(c))
        koren.addView(Tema.tlacidlo(c, "Otestovať preklad", false) {
            ulozVsetko(); vysledok.text = "Testujem..."
            val jazyk = Nastaveni(c).jazykNeboCs
            Thread {
                val v = try { "Funguje (" + jazyk + "): " + Gemini.test(jazyk) }
                catch (e: Exception) { "Chyba: " + (e.message ?: e.javaClass.simpleName) }
                runOnUiThread { vysledok.text = v }
            }.start()
        }, Tema.lp(c, 10))
        koren.addView(Tema.tlacidlo(c, "Otestovať dabing", false) {
            ulozVsetko()
            if (Dabing.rezim == Dabing.VYPNUTE) { vysledok.text = "Dabing je vypnutý."; return@tlacidlo }
            vysledok.text = "Generujem hlas..."
            Thread {
                val v = try { Dabing.test(); "Dabing prehraný." }
                catch (e: Exception) { "Chyba: " + (e.message ?: e.javaClass.simpleName) }
                runOnUiThread { vysledok.text = v }
            }.start()
        }, Tema.lp(c, 10))
        koren.addView(Tema.tlacidlo(c, "Vymazať pamäť prekladov", false) {
            Gemini.vymazPamet(); vysledok.text = "Pamäť vymazaná."
        }, Tema.lp(c, 10))
        koren.addView(vysledok)

        setContentView(ScrollView(c).apply { background = Tema.pozadie(); addView(koren) })
    }
}
