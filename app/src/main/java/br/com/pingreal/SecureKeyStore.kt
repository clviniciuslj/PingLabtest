package br.com.pingreal

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cifra e decifra bytes. Existe como interface para os testes usarem um cifrador de mentira (o Keystore só existe no aparelho). */
interface KeyCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/**
 * AES-GCM de 256 bits com a chave guardada no Android Keystore: o material da chave nunca sai do hardware/sistema
 * e só este app consegue usá-la. O que se grava em disco é só [IV + texto cifrado].
 */
class KeystoreCipher(private val alias: String = "pinglab_gemini_key") : KeyCipher {

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val ivLength = data[0].toInt() and 0xff
        val iv = data.copyOfRange(1, 1 + ivLength)
        val encrypted = data.copyOfRange(1 + ivLength, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Guarda a chave do Gemini cifrada nas preferências do app. A chave nunca é gravada em texto puro. */
class SecureKeyStore(private val prefs: SharedPreferences, private val cipher: KeyCipher) {

    /** Grava a chave cifrada. Devolve false se o aparelho não conseguiu cifrar (nada é gravado). */
    fun save(secret: String): Boolean = try {
        val encrypted = cipher.encrypt(secret.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(Prefs.AI_KEY_ENC, Base64.getEncoder().encodeToString(encrypted)).apply()
        true
    } catch (_: Exception) {
        false
    }

    /** A chave, ou null se não há nenhuma ou se ela não pôde ser lida (dado corrompido, chave do Keystore perdida). */
    fun load(): String? {
        val stored = prefs.getString(Prefs.AI_KEY_ENC, null) ?: return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // Um registro que não abre mais não serve para nada: apaga, e o app pede a chave de novo.
            clear()
            null
        }
    }

    fun hasKey(): Boolean = prefs.contains(Prefs.AI_KEY_ENC)

    fun clear() {
        prefs.edit().remove(Prefs.AI_KEY_ENC).apply()
    }

    companion object {
        /** Verificação leve de formato: não tem espaços e não é curta demais. A validade de verdade só a API diz. */
        fun looksLikeKey(text: String): Boolean = text.length >= 20 && text.none { it.isWhitespace() }

        /** Para mostrar na tela sem revelar a chave inteira. */
        fun mask(secret: String): String =
            if (secret.length <= 8) "••••" else secret.take(4) + "…" + secret.takeLast(4)
    }
}
