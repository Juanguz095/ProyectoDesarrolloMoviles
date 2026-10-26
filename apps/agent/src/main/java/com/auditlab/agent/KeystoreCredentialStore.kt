package com.auditlab.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.auditlab.common.*
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Key remains non-exportable; unreadable data is blocked and preserved, never silently discarded. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val preferences = context.getSharedPreferences("auditlab_agent", Context.MODE_PRIVATE)
    private val protected = ProtectedValue(
        read = { preferences.getString("credential", null) },
        persist = { value ->
            val edit = preferences.edit()
            if (value == null) edit.remove("credential") else edit.putString("credential", value)
            edit.commit()
        },
        key = ::secretKey,
        context = context.packageName + ":credential:v1",
    )
    override fun load(): StoredCredential? = try {
        protected.load()?.let { plain ->
            val node = JSONObject(plain)
            StoredCredential(node.getString("deviceId"), node.getString("deviceToken"),
                EventCodec.consent(node.getJSONObject("consent")),
                if (node.has("serverUrl") && !node.isNull("serverUrl")) node.getString("serverUrl") else null)
        }
    } catch (_: Exception) { throw StorageFailure() }
    override fun save(value: StoredCredential) {
        val node = JSONObject().put("deviceId", value.deviceId).put("deviceToken", value.deviceToken)
            .put("consent", EventCodec.consent(value.consent)).put("serverUrl", value.serverUrl ?: JSONObject.NULL)
        protected.save(node.toString())
    }
    override fun clear() = protected.clear()
    private fun secretKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry("auditlab_agent_credential", null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        check(create) { "No hay clave disponible para leer esta credencial." }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("auditlab_agent_credential", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
}
