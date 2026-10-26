package com.auditlab.control

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.auditlab.common.*
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Key remains non-exportable; unreadable data is blocked and preserved, never silently discarded. */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences("auditlab_control", Context.MODE_PRIVATE)
    private val protected = ProtectedValue(
        read = { preferences.getString("session", null) },
        persist = { value ->
            val edit = preferences.edit()
            if (value == null) edit.remove("session") else edit.putString("session", value)
            edit.commit()
        },
        key = ::secretKey,
        context = context.packageName + ":session:v1",
    )
    override fun load(): Session? = try {
        protected.load()?.let { plain ->
            val node = JSONObject(plain)
            Session(node.getString("accessToken"), node.getString("refreshToken"), node.optString("tokenType", "Bearer"),
                node.getLong("expiresIn"), node.getString("sessionId"), node.getString("userId"),
                node.getString("username"), node.getString("role"),
                if (node.has("serverUrl") && !node.isNull("serverUrl")) node.getString("serverUrl") else null)
        }
    } catch (_: Exception) { throw StorageFailure() }
    override fun save(value: Session) {
        val node = JSONObject().put("accessToken", value.accessToken).put("refreshToken", value.refreshToken)
            .put("tokenType", value.tokenType).put("expiresIn", value.expiresIn).put("sessionId", value.sessionId)
            .put("userId", value.userId).put("username", value.username).put("role", value.role)
            .put("serverUrl", value.serverUrl ?: JSONObject.NULL)
        protected.save(node.toString())
    }
    override fun clear() = protected.clear()
    private fun secretKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry("auditlab_control_session", null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        check(create) { "No hay clave disponible para leer esta credencial." }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("auditlab_control_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
}
