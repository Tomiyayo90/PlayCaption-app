package cz.titulky.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Domovská obrazovka na telefóne (modro-čierny vzhľad). Pripojenie konzoly zostáva v MainActivity. */
class DomovActivity : Activity() {
    private lateinit var koren: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Tema.POZADIE2
        window.navigationBarColor = Tema.POZADIE
        Gemini.init(this)
        koren = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Tema.dp(this@DomovActivity, 16)
            setPadding(p, Tema.dp(this@DomovActivity, 28), p, p * 2)
        }
        setContentView(ScrollView(this).apply {
            background = Tema.pozadie()
            isFillViewport = true
            addView(koren)
        })
    }

    override fun onResume() { super.onResume(); vykresli() }

    private fun vykresli() {
        val c = this
        koren.removeAllViews()
        val jazyk = Nastaveni(c).jazykNeboCs
        val kluc = Gemini.klic.isNotBlank() && Gemini.zapnute
        val dabingZap = Dabing.rezim != Dabing.VYPNUTE

        koren.addView(Tema.text(c, "SlovakCaption", 30f, Color.WHITE, true))
        koren.addView(Tema.text(c, "PS5 · " + jazykNazov(jazyk) + " · Gemini", 14f, Tema.SLABY))

        val start = Tema.kartaBox(c, Tema.AKCENT).apply { setPadding(Tema.dp(c, 20), Tema.dp(c, 20), Tema.dp(c, 20), Tema.dp(c, 20)) }
        start.addView(Tema.text(c, if (kluc) "Pripravené" else "Chýba Gemini kľúč", 13f, if (kluc) Tema.OK else Tema.CHYBA, true))
        start.addView(Tema.text(c, "Spustiť preklad", 24f, Color.WHITE, true))
        start.addView(Tema.text(c, "Pripoj PS5, zapni hru s anglickými titulkami a začni.", 14f, Tema.SLABY).apply { setPadding(0, Tema.dp(c, 4), 0, Tema.dp(c, 14)) })
        start.addView(Tema.tlacidlo(c, "▶  Pripojenie a spustenie", true) { startActivity(Intent(c, MainActivity::class.java)) })
        koren.addView(start, Tema.lp(c, 20))

        koren.addView(Tema.nadpisSekcie(c, "Rýchle akcie"))
        val dab = Tema.kartaBox(c)
        dab.addView(Tema.prepinac(c, "Dabing (hlas v slúchadlách)", dabingZap) { zap ->
            if (zap != (Dabing.rezim != Dabing.VYPNUTE)) Dabing.prepni()
            vykresli()
        })
        dab.addView(Tema.text(c, if (dabingZap) "Zapnuté: titulky + hlas" else "Vypnuté: len titulky na TV", 13f, Tema.SLABY).apply { setPadding(0, Tema.dp(c, 4), 0, 0) })
        koren.addView(dab, Tema.lp(c))

        val hra = Tema.kartaBox(c)
        hra.addView(Tema.text(c, "Hra", 13f, Tema.SLABY))
        hra.addView(Tema.text(c, Gemini.nazovHry(), 17f, Color.WHITE, true))
        hra.addView(Tema.text(c, "Kontext prekladu a mená postáv sa berú podľa hry.", 13f, Tema.SLABY).apply { setPadding(0, Tema.dp(c, 2), 0, Tema.dp(c, 12)) })
        hra.addView(Tema.tlacidlo(c, "Vybrať hru", false) { vyberHru() })
        if (Gemini.hraId == "gta6") {
            hra.addView(Tema.tlacidlo(c, "Nastaviť oblasť titulkov pre GTA 6", false) {
                OblastTitulku.rucne = true
                OblastTitulku.oblast = floatArrayOf(0.21f, 0.80f, 0.78f, 0.935f)
                OblastTitulku.uloz(c)
                Toast.makeText(c, "Oblasť titulkov pre GTA 6 nastavená", Toast.LENGTH_SHORT).show()
            }, Tema.lp(c, 10))
        }
        koren.addView(hra, Tema.lp(c, 12))

        koren.addView(Tema.tlacidlo(c, "Stmaviť obrazovku (šetrí batériu)", false) {
            startActivity(Intent(c, StmavenieActivity::class.java))
        }, Tema.lp(c, 12))

        koren.addView(Tema.nadpisSekcie(c, "Stav"))
        val stav = Tema.kartaBox(c)
        fun riadok(t: String, ok: Boolean) {
            val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Tema.dp(c, 6), 0, Tema.dp(c, 6)) }
            r.addView(Tema.bodka(c, if (ok) Tema.OK else Tema.SLABY))
            r.addView(Tema.text(c, t, 15f))
            stav.addView(r)
        }
        riadok("Gemini preklad", kluc)
        riadok("Dabing", dabingZap)
        riadok("Pamäť prekladov: " + Gemini.pocetVPameti() + " viet", Gemini.pocetVPameti() > 0)
        koren.addView(stav, Tema.lp(c))

        koren.addView(Tema.tlacidlo(c, "⚙  Nastavenia prekladu a dabingu", false) {
            startActivity(Intent(c, GeminiNastaveniActivity::class.java))
        }, Tema.lp(c, 20))
    }

    private fun vyberHru() {
        val nazvy = (listOf("Iná hra (všeobecne)") + Hry.vsetky.map { it.title }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Vybrať hru")
            .setItems(nazvy) { _, i ->
                val nova = if (i == 0) "" else Hry.vsetky[i - 1].id
                if (nova != Gemini.hraId) Dabing.zabudniHlasy()
                Gemini.hraId = nova
                vykresli()
            }
            .show()
    }

    private fun jazykNazov(k: String) = when (k) {
        "sk" -> "Slovenčina"; "cs" -> "Čeština"; "de" -> "Nemčina"; "pl" -> "Poľština"
        "fr" -> "Francúzština"; "es" -> "Španielčina"; "it" -> "Taliančina"; else -> k
    }
}
