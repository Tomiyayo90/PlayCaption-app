package cz.titulky.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Modro-čierny vzhľad (štýl GlossaSub) – spoločné pomocné funkcie pre obrazovky. */
object Tema {
    val POZADIE = Color.parseColor("#070B16")
    val POZADIE2 = Color.parseColor("#0E1A33")
    val KARTA = Color.parseColor("#111A30")
    val OKRAJ = Color.parseColor("#1F2D4D")
    val AKCENT = Color.parseColor("#3B82F6")
    val AKCENT2 = Color.parseColor("#22D3EE")
    val TEXT = Color.WHITE
    val SLABY = Color.parseColor("#8FA3C7")
    val OK = Color.parseColor("#34D399")
    val CHYBA = Color.parseColor("#F87171")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun pozadie() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(POZADIE2, POZADIE))

    fun karta(c: Context, farbaOkraja: Int = OKRAJ) = GradientDrawable().apply {
        setColor(KARTA); cornerRadius = dp(c, 20).toFloat(); setStroke(dp(c, 1), farbaOkraja)
    }

    fun lp(c: Context, hore: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(c, hore) }

    fun text(c: Context, t: String, velkost: Float = 15f, farba: Int = TEXT, tucne: Boolean = false) =
        TextView(c).apply {
            text = t; textSize = velkost; setTextColor(farba)
            if (tucne) typeface = Typeface.DEFAULT_BOLD
        }

    /** Karta so zvislým obsahom. */
    fun kartaBox(c: Context, okraj: Int = OKRAJ): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = karta(c, okraj)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
    }

    fun nadpisSekcie(c: Context, t: String) = text(c, t.uppercase(), 12f, SLABY, true).apply {
        setPadding(dp(c, 6), dp(c, 22), 0, dp(c, 8)); letterSpacing = 0.08f
    }

    fun tlacidlo(c: Context, t: String, vyplnene: Boolean, akcia: () -> Unit) = TextView(c).apply {
        text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(if (vyplnene) Color.WHITE else AKCENT2)
        background = GradientDrawable().apply {
            cornerRadius = dp(c, 14).toFloat()
            if (vyplnene) setColor(AKCENT) else { setColor(Color.TRANSPARENT); setStroke(dp(c, 2), AKCENT) }
        }
        setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13))
        setOnClickListener { akcia() }
    }

    fun prepinac(c: Context, t: String, zapnute: Boolean, zmena: (Boolean) -> Unit) = Switch(c).apply {
        text = t; textSize = 15f; setTextColor(TEXT); isChecked = zapnute
        val stavy = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(stavy, intArrayOf(AKCENT2, SLABY))
        trackTintList = ColorStateList(stavy, intArrayOf(Color.parseColor("#1D4ED8"), OKRAJ))
        setOnCheckedChangeListener { _, v -> zmena(v) }
    }

    fun pole(c: Context, hodnota: String, heslo: Boolean = false, viac: Boolean = false) = EditText(c).apply {
        setText(hodnota); setTextColor(TEXT); textSize = 14f
        setHintTextColor(SLABY)
        backgroundTintList = ColorStateList.valueOf(AKCENT)
        if (viac) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6; gravity = Gravity.TOP
        } else {
            setSingleLine(true)
            if (heslo) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
    }

    fun bodka(c: Context, farba: Int): View = View(c).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(farba) }
        layoutParams = LinearLayout.LayoutParams(dp(c, 10), dp(c, 10)).apply { rightMargin = dp(c, 8) }
    }

    /** Adaptér pre Spinner so svetlým textom (aby bol čitateľný na tmavom pozadí). */
    fun adapter(c: Context, polozky: List<String>): android.widget.ArrayAdapter<String> =
        object : android.widget.ArrayAdapter<String>(c, android.R.layout.simple_spinner_dropdown_item, polozky) {
            private fun uprav(v: android.view.View, pozadie: Int): android.view.View {
                (v as? android.widget.TextView)?.apply {
                    setTextColor(Color.WHITE); textSize = 16f
                    setBackgroundColor(pozadie)
                    setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
                }
                return v
            }
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) =
                uprav(super.getView(position, convertView, parent), Color.TRANSPARENT)
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) =
                uprav(super.getDropDownView(position, convertView, parent), 0xFF1B2A4D.toInt())
        }

    fun spinner(c: Context): android.widget.Spinner = android.widget.Spinner(c).apply {
        setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF1B2A4D.toInt()))
    }
}
