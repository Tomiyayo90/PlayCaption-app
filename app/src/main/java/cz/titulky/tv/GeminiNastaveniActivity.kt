package cz.titulky.tv

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/** Nastavenia Gemini prekladu a dabingu. Má vlastnú ikonu v zozname aplikácií. */
class GeminiNastaveniActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Gemini.init(this)

        val sirka = (16 * resources.displayMetrics.density).toInt()
        val koren = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sirka, sirka * 2, sirka, sirka)
        }

        fun popis(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, sirka, 0, 4) }
        fun pole(hodnota: String, heslo: Boolean = false) = EditText(this).apply {
            setText(hodnota)
            setSingleLine(true)
            if (heslo) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        koren.addView(TextView(this).apply { text = "Gemini nastavenia"; textSize = 22f })

        val prepinac = Switch(this).apply {
            text = "Používať Gemini preklad"
            isChecked = Gemini.zapnute
            setPadding(0, sirka, 0, 0)
        }
        koren.addView(prepinac)

        koren.addView(popis("API kľúč (aistudio.google.com/apikey)"))
        val klic = pole(Gemini.klic, heslo = true)
        koren.addView(klic)

        koren.addView(popis("Model prekladu"))
        val model = pole(Gemini.model)
        koren.addView(model)

        koren.addView(popis("Kontext hry (pomáha prekladu)"))
        val kontext = EditText(this).apply {
            setText(Gemini.kontext)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP
        }
        koren.addView(kontext)

        koren.addView(TextView(this).apply { text = "Dabing"; textSize = 20f; setPadding(0, sirka * 2, 0, 0) })
        koren.addView(popis("Hlas (prehráva sa v telefóne, napr. v slúchadlách)"))
        val rezim = Spinner(this).apply {
            adapter = ArrayAdapter(this@GeminiNastaveniActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Vypnuté", "Gemini hlas", "ElevenLabs hlas"))
            setSelection(Dabing.rezim.coerceIn(0, 2))
        }
        koren.addView(rezim)
        koren.addView(popis("Model Gemini hlasu"))
        val ttsModel = pole(Dabing.ttsModel)
        koren.addView(ttsModel)
        koren.addView(popis("ElevenLabs API kľúč (len pre ElevenLabs)"))
        val elKlic = pole(Dabing.elevenKlic, heslo = true)
        koren.addView(elKlic)
        koren.addView(popis("ElevenLabs ID ženského hlasu"))
        val elZena = pole(Dabing.elevenHlasZena)
        koren.addView(elZena)
        koren.addView(popis("ElevenLabs ID mužského hlasu"))
        val elMuz = pole(Dabing.elevenHlasMuz)
        koren.addView(elMuz)

        val vysledok = TextView(this).apply { textSize = 14f; setPadding(0, sirka, 0, sirka) }
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

        koren.addView(Button(this).apply {
            text = "Uložiť"
            setOnClickListener { ulozVsetko(); vysledok.text = "Uložené." }
        })
        koren.addView(Button(this).apply {
            text = "Otestovať preklad"
            setOnClickListener {
                ulozVsetko()
                vysledok.text = "Testujem..."
                val jazyk = Nastaveni(this@GeminiNastaveniActivity).jazykNeboCs
                Thread {
                    val v = try { "Funguje (" + jazyk + "): " + Gemini.test(jazyk) }
                    catch (e: Exception) { "Chyba: " + (e.message ?: e.javaClass.simpleName) }
                    runOnUiThread { vysledok.text = v }
                }.start()
            }
        })
        koren.addView(Button(this).apply {
            text = "Otestovať dabing"
            setOnClickListener {
                ulozVsetko()
                if (Dabing.rezim == Dabing.VYPNUTE) { vysledok.text = "Dabing je vypnutý."; return@setOnClickListener }
                vysledok.text = "Generujem hlas..."
                Thread {
                    val v = try { Dabing.test(); "Dabing prehraný." }
                    catch (e: Exception) { "Chyba: " + (e.message ?: e.javaClass.simpleName) }
                    runOnUiThread { vysledok.text = v }
                }.start()
            }
        })
        koren.addView(Button(this).apply {
            text = "Vymazať pamäť prekladov"
            setOnClickListener { Gemini.vymazPamet(); vysledok.text = "Pamäť vymazaná." }
        })
        koren.addView(vysledok)
        koren.addView(TextView(this).apply {
            textSize = 12f
            text = "Prekladá sa do jazyka vybraného v hlavnej aplikácii (napr. slovenčina). Preložené vety sa ukladajú v telefóne."
        })

        setContentView(ScrollView(this).apply { addView(koren) })
    }
}
