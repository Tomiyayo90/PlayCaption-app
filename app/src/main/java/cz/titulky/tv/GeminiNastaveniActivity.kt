package cz.titulky.tv

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Jednoduchá obrazovka s nastaveniami Gemini (kľúč, model, kontext hry). Má vlastnú ikonu v zozname aplikácií. */
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

        koren.addView(TextView(this).apply { text = "Gemini nastavenia"; textSize = 22f })

        val prepinac = Switch(this).apply {
            text = "Používať Gemini preklad"
            isChecked = Gemini.zapnute
            setPadding(0, sirka, 0, 0)
        }
        koren.addView(prepinac)

        koren.addView(popis("API kľúč (zo aistudio.google.com/apikey)"))
        val klic = EditText(this).apply {
            setText(Gemini.klic)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        koren.addView(klic)

        koren.addView(popis("Model"))
        val model = EditText(this).apply { setText(Gemini.model); setSingleLine(true) }
        koren.addView(model)

        koren.addView(popis("Kontext hry (pomáha prekladu)"))
        val kontext = EditText(this).apply {
            setText(Gemini.kontext)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP
        }
        koren.addView(kontext)

        val vysledok = TextView(this).apply { textSize = 14f; setPadding(0, sirka, 0, sirka) }
        fun ulozVsetko() {
            Gemini.zapnute = prepinac.isChecked
            Gemini.klic = klic.text.toString()
            Gemini.model = model.text.toString()
            Gemini.kontext = kontext.text.toString()
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
                    val text = try { "Funguje (" + jazyk + "): " + Gemini.test(jazyk) }
                    catch (e: Exception) { "Chyba: " + (e.message ?: e.javaClass.simpleName) }
                    runOnUiThread { vysledok.text = text }
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
