package cz.titulky.tv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/** Nastavenia prekladu (Gemini), hry a dabingu – prehľadné sekcie v modro-čiernom vzhľade. */
class GeminiNastaveniActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        Gemini.init(this)
        val nast = Nastaveni(this)
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
        koren.addView(Tema.nadpisSekcie(c, "Hra"))
        val hra = Tema.kartaBox(c)
        hra.addView(popis("Vybraná hra (kontext prekladu, mená ktoré sa neprekladajú)"))
        val nazvy = listOf("Iná hra (všeobecne)") + Hry.vsetky.map { it.title }
        val hraSpinner = Spinner(c).apply {
            adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, nazvy)
            val i = Hry.vsetky.indexOfFirst { it.id == Gemini.hraId }
            setSelection(if (i >= 0) i + 1 else 0)
        }
        hra.addView(hraSpinner)
        hra.addView(popis("Vlastné poznámky (nepovinné, napr. „postavu X preklad ako ženu“)"))
        val poznamky = Tema.pole(c, Gemini.poznamky, viac = true); hra.addView(poznamky)
        koren.addView(hra, Tema.lp(c))

        // ---- Čo prekladať
        koren.addView(Tema.nadpisSekcie(c, "Čo prekladať"))
        val co = Tema.kartaBox(c)
        val dialogy = Tema.prepinac(c, "Dialógy (titulky)", nast.dialogy) {}
        co.addView(dialogy)
        val napovedy = Tema.prepinac(c, "Nápovedy a úlohy", nast.napovedy) {}
        co.addView(napovedy, Tema.lp(c, 10))
        co.addView(Tema.text(c, "Nápovedy a úlohy sa v hre zobrazujú žltým textom.", 12f, Tema.SLABY).apply { setPadding(0, Tema.dp(c, 4), 0, 0) })
        koren.addView(co, Tema.lp(c))

        // ---- Dabing
        koren.addView(Tema.nadpisSekcie(c, "Dabing"))
        val dab = Tema.kartaBox(c)
        dab.addView(popis("Zdroj hlasu (prehráva sa v telefóne alebo v slúchadlách)"))
        val rezim = Spinner(c).apply {
            adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item,
                listOf("Vypnuté (len titulky)", "Gemini hlas", "ElevenLabs hlas"))
            setSelection(Dabing.rezim.coerceIn(0, 2))
        }
        dab.addView(rezim)
        dab.addView(popis("Výber hlasu"))
        val hlasVolba = Spinner(c).apply {
            adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item,
                listOf("Podľa postavy (každá má svoj hlas)", "Vždy ženský", "Vždy mužský"))
            setSelection(Dabing.hlasVolba.coerceIn(0, 2))
        }
        dab.addView(hlasVolba)
        dab.addView(Tema.text(c, "Pri voľbe „Podľa postavy“ dostane každé meno stály hlas. Riadky bez mena číta rozprávač.", 12f, Tema.SLABY).apply { setPadding(0, Tema.dp(c, 4), 0, 0) })

        val hlasitostText = popis("Hlasitosť: " + Dabing.hlasitost + " %")
        dab.addView(hlasitostText)
        val seek = SeekBar(c).apply {
            max = 100; progress = Dabing.hlasitost
            progressTintList = ColorStateList.valueOf(Tema.AKCENT)
            thumbTintList = ColorStateList.valueOf(Tema.AKCENT2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, z: Boolean) { hlasitostText.text = "Hlasitosť: $p %" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        dab.addView(seek)
        val prerusit = Tema.prepinac(c, "Nový riadok preruší predchádzajúci", Dabing.prerusit) {}
        dab.addView(prerusit, Tema.lp(c, 12))

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
            val novaHra = if (hraSpinner.selectedItemPosition == 0) "" else Hry.vsetky[hraSpinner.selectedItemPosition - 1].id
            if (novaHra != Gemini.hraId) Dabing.zabudniHlasy()
            Gemini.hraId = novaHra
            Gemini.poznamky = poznamky.text.toString()
            nast.dialogy = dialogy.isChecked
            nast.napovedy = napovedy.isChecked
            Dabing.rezim = rezim.selectedItemPosition
            Dabing.hlasVolba = hlasVolba.selectedItemPosition
            Dabing.hlasitost = seek.progress
            Dabing.prerusit = prerusit.isChecked
            Dabing.ttsModel = ttsModel.text.toString()
            Dabing.elevenKlic = elKlic.text.toString()
            Dabing.elevenHlasZena = elZena.text.toString()
            Dabing.elevenHlasMuz = elMuz.text.toString()
        }
        koren.addView(Tema.nadpisSekcie(c, "Akcie"))
        koren.addView(Tema.tlacidlo(c, "Uložiť", true) { ulozVsetko(); vysledok.text = "Uložené." }, Tema.lp(c))
        koren.addView(Tema.tlacidlo(c, "Otestovať preklad", false) {
            ulozVsetko(); vysledok.text = "Testujem..."
            val jazyk = nast.jazykNeboCs
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
