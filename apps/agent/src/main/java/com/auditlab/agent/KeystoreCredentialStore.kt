package com.auditlab.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.auditlab.common.ConsentContext
import com.auditlab.common.CredentialStore
import com.auditlab.common.StoredCredential
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda la credencial del dispositivo cifrada con AES/GCM y una clave no exportable de
 * Android Keystore. SharedPreferences solo contiene el texto cifrado y el IV.
 * No se usa criptografía propia: la clave la genera y protege el sistema.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val preferences = context.getSharedPreferences("auditlab_agent", Context.MODE_PRIVATE)

    override fun load(): StoredCredential? {
        val encoded = preferences.getString(KEY, null) ?: return null
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            val plain = cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES).toString(Charsets.UTF_8)
            val node = JSONObject(plain)
            StoredCredential(
                deviceId = node.getString("deviceId"),
                deviceToken = node.getString("deviceToken"),
                consent = ConsentContext(
                    consentId = node.getJSONObject("consent").getString("consentId"),
                    policyVersion = node.getJSONObject("consent").getString("policyVersion"),
                    acceptedAt = node.getJSONObject("consent").getString("acceptedAt"),
                ),
            )
        } catch (_: Exception) {
            // Una entrada ilegible se descarta: es preferible volver a inscribir que fallar en bucle.
            clear()
            null
        }
    }

    override fun save(credential: StoredCredential) {
        val plain = JSONObject()
            .put("deviceId", credential.deviceId)
            .put("deviceToken", credential.deviceToken)
            .put("consent", JSONObject()
                .put("consentId", credential.consent.consentId)
                .put("policyVersion", credential.consent.policyVersion)
                .put("acceptedAt", credential.consent.acceptedAt))
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        preferences.edit().putString(KEY, Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "auditlab_agent_credential"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY = "credential"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
