package cz.titulky.tv

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dabing prekladu: hlas sa prehráva v telefóne (slúchadlá / reproduktor).
 * Režimy: 0 = vypnuté, 1 = Gemini TTS (používa Gemini kľúč), 2 = ElevenLabs.
 */
object Dabing {
    const val VYPNUTE = 0
    const val GEMINI = 1
    const val ELEVEN = 2
    const val VYCHODZI_TTS_MODEL = "gemini-3.8-flash-lite-tts"
    private const val ELEVEN_MODEL = "eleven_flash_v2_5"

    private var prefs: SharedPreferences? = null
    private var dir: File? = null
    private val vlakno = Executors.newSingleThreadExecutor()
    private val fronta = AtomicInteger(0)
    @Volatile private var poslednyText = ""
    @Volatile private var poslednyCas = 0L
    @Volatile var poslednaChyba = ""
        private set

    private val MENO = Regex("^([A-Z][A-Za-z0-9 .'-]{1,24}):\\s*(.+)$")
    private val ZENY = setOf("lucia", "maya", "mia", "nadia")
    private val MUZI = setOf("jason", "leo", "jay", "tomas", "boobie", "dre'quan")
    private val GEMINI_ZENY = listOf("Kore", "Leda", "Aoede", "Zephyr")
    private val GEMINI_MUZI = listOf("Charon", "Puck", "Orus", "Fenrir")

    @Synchronized
    fun init(c: Context) {
        if (prefs != null) return
        val app = c.applicationContext
        prefs = app.getSharedPreferences("dabing", Context.MODE_PRIVATE)
        dir = app.cacheDir
    }

    var rezim: Int
        get() = prefs?.getInt("rezim", VYPNUTE) ?: VYPNUTE
        set(v) { prefs?.edit()?.putInt("rezim", v)?.apply() }

    var ttsModel: String
        get() = (prefs?.getString("ttsModel", null) ?: VYCHODZI_TTS_MODEL).ifBlank { VYCHODZI_TTS_MODEL }
        set(v) { prefs?.edit()?.putString("ttsModel", v.trim())?.apply() }

    var elevenKlic: String
        get() = prefs?.getString("elevenKlic", "") ?: ""
        set(v) { prefs?.edit()?.putString("elevenKlic", v.trim())?.apply() }

    var elevenHlasZena: String
        get() = prefs?.getString("elevenZena", "") ?: ""
        set(v) { prefs?.edit()?.putString("elevenZena", v.trim())?.apply() }

    var elevenHlasMuz: String
        get() = prefs?.getString("elevenMuz", "") ?: ""
        set(v) { prefs?.edit()?.putString("elevenMuz", v.trim())?.apply() }

    /** Rýchle zapnutie/vypnutie (tlačidlo v aplikácii). Pri zapnutí sa vráti posledný zvolený hlas. */
    fun prepni() {
        val p = prefs ?: return
        if (rezim != VYPNUTE) {
            p.edit().putInt("posledny", rezim).apply()
            rezim = VYPNUTE
        } else {
            val posledny = p.getInt("posledny", GEMINI)
            rezim = if (posledny == VYPNUTE) GEMINI else posledny
        }
    }

    /** Zavolá sa s hotovým prekladom (riadky "MENO: veta"). */
    fun povedz(preklad: String) {
        if (prefs == null || rezim == VYPNUTE) return
        val ted = System.currentTimeMillis()
        if (preklad == poslednyText && ted - poslednyCas < 6000) return
        poslednyText = preklad
        poslednyCas = ted
        for (riadok in preklad.split("\n")) {
            val t = riadok.trim()
            if (t.isEmpty()) continue
            val m = MENO.find(t)
            val meno = m?.groupValues?.get(1)
            val veta = (m?.groupValues?.get(2) ?: t).trim()
            if (veta.length < 2) continue
            if (fronta.get() >= 3) continue // dabing by inak zaostával za hrou
            fronta.incrementAndGet()
            vlakno.execute {
                try {
                    prehraj(veta, meno)
                    poslednaChyba = ""
                } catch (e: Exception) {
                    poslednaChyba = e.message ?: e.javaClass.simpleName
                } finally {
                    fronta.decrementAndGet()
                }
            }
        }
    }

    /** Test z nastavení – vyhodí výnimku s popisom chyby. */
    fun test() = prehraj("Ahoj, toto je skúška dabingu.", null)

    private fun jeZena(meno: String?): Boolean {
        if (meno == null) return false
        val m = meno.lowercase()
        return when {
            m in ZENY -> true
            m in MUZI -> false
            else -> Math.abs(m.hashCode()) % 2 == 1
        }
    }

    private fun prehraj(text: String, meno: String?) {
        val zena = jeZena(meno)
        val idx = if (meno == null) 0 else Math.abs(meno.lowercase().hashCode()) % 4
        when (rezim) {
            GEMINI -> {
                val hlas = (if (zena) GEMINI_ZENY else GEMINI_MUZI)[idx]
                prehrajPcm(geminiTts(text, hlas))
            }
            ELEVEN -> {
                val hlas = if (zena) elevenHlasZena else elevenHlasMuz
                if (hlas.isBlank()) throw IllegalStateException("Zadaj ID hlasu pre ElevenLabs")
                prehrajMp3(elevenTts(text, hlas))
            }
        }
    }

    private fun post(adresa: String, hlavicky: Map<String, String>, telo: String, cakanie: Int): ByteArray {
        val s = URL(adresa).openConnection() as HttpURLConnection
        try {
            s.connectTimeout = 5000
            s.readTimeout = cakanie
            s.requestMethod = "POST"
            s.doOutput = true
            s.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            for ((k, v) in hlavicky) s.setRequestProperty(k, v)
            s.outputStream.use { it.write(telo.toByteArray(Charsets.UTF_8)) }
            val kod = s.responseCode
            val prud = if (kod in 200..299) s.inputStream else s.errorStream
            val data = prud?.use { it.readBytes() } ?: ByteArray(0)
            if (kod !in 200..299) throw IllegalStateException("HTTP $kod: " + String(data, Charsets.UTF_8).take(200))
            return data
        } finally {
            s.disconnect()
        }
    }

    private fun geminiTts(text: String, hlas: String): ByteArray {
        val kluc = Gemini.klic
        if (kluc.isBlank()) throw IllegalStateException("Chýba Gemini API kľúč")
        val telo = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", hlas)))))
        val odpoved = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$ttsModel:generateContent",
            mapOf("x-goog-api-key" to kluc), telo.toString(), 15000
        )
        val casti = JSONObject(String(odpoved, Charsets.UTF_8)).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        for (i in 0 until casti.length()) {
            val inline = casti.getJSONObject(i).optJSONObject("inlineData") ?: continue
            return Base64.decode(inline.getString("data"), Base64.DEFAULT)
        }
        throw IllegalStateException("Gemini nevrátil zvuk")
    }

    private fun elevenTts(text: String, hlas: String): ByteArray {
        if (elevenKlic.isBlank()) throw IllegalStateException("Chýba ElevenLabs API kľúč")
        val telo = JSONObject().put("text", text).put("model_id", ELEVEN_MODEL)
        return post(
            "https://api.elevenlabs.io/v1/text-to-speech/$hlas?output_format=mp3_44100_128",
            mapOf("xi-api-key" to elevenKlic), telo.toString(), 15000
        )
    }

    /** Gemini TTS vracia surový zvuk PCM 16-bit, mono, 24 kHz. */
    private fun prehrajPcm(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size / 2 * 1000L / 24000 + 150)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun prehrajMp3(data: ByteArray) {
        val d = dir ?: return
        val f = File(d, "dabing.mp3")
        f.writeBytes(data)
        val latch = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
            mp.prepare()
            mp.start()
            latch.await(30, TimeUnit.SECONDS)
        } finally {
            runCatching { mp.release() }
        }
    }
}
