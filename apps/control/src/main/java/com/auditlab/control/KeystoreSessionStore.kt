package com.auditlab.control

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.auditlab.common.Session
import com.auditlab.common.SessionStore
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sesión del centro de control cifrada con AES/GCM y una clave no exportable de Android Keystore.
 * El refresh token nunca queda en texto claro en el almacenamiento de la app.
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences("auditlab_control", Context.MODE_PRIVATE)

    override fun load(): Session? {
        val encoded = preferences.getString(KEY, null) ?: return null
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            val node = JSONObject(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES).toString(Charsets.UTF_8))
            Session(
                accessToken = node.getString("accessToken"),
                refreshToken = node.getString("refreshToken"),
                tokenType = node.optString("tokenType", "Bearer"),
                expiresIn = node.optLong("expiresIn"),
                sessionId = node.getString("sessionId"),
                userId = node.getString("userId"),
                username = node.getString("username"),
                role = node.getString("role"),
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    override fun save(session: Session) {
        val plain = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("tokenType", session.tokenType)
            .put("expiresIn", session.expiresIn)
            .put("sessionId", session.sessionId)
            .put("userId", session.userId)
            .put("username", session.username)
            .put("role", session.role)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val combined = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
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
        const val ALIAS = "auditlab_control_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY = "session"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
