package cz.titulky.tv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Šifrovanie API kľúčov kľúčom z Android Keystore (kľúč nikdy neopustí telefón). */
object Sifra {
    private const val ALIAS = "slovakcaption-kluce-v1"
    private const val PREDPONA = "enc:"

    private fun klic(): SecretKey = synchronized(this) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    /** Ak šifrovanie zlyhá, vráti text nezmenený (aby appka fungovala). */
    fun zasifruj(text: String): String {
        if (text.isEmpty()) return ""
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, klic())
            PREDPONA + Base64.getEncoder().encodeToString(c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8)))
        }.getOrDefault(text)
    }

    /** Staré nezašifrované hodnoty sa vrátia tak, ako sú. */
    fun desifruj(text: String): String {
        if (!text.startsWith(PREDPONA)) return text
        return runCatching {
            val b = Base64.getDecoder().decode(text.substring(PREDPONA.length))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, klic(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            String(c.doFinal(b.copyOfRange(12, b.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
