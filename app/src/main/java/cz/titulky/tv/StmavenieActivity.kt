package cz.titulky.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/** Čierna obrazovka s minimálnym jasom – preklad beží ďalej, šetrí sa batéria. Ťuknutím sa zatvorí. */
class StmavenieActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        val koren = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@StmavenieActivity).apply {
                text = "Preklad beží. Ťukni na obrazovku pre návrat."
                setTextColor(Color.parseColor("#22344F"))
                textSize = 13f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnClickListener { finish() }
            systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        setContentView(koren)
    }
}
