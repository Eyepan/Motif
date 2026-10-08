package app.motif.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the refresh token in shared preferences, encrypted with an AES key that never
 * leaves the Android Keystore. One entry per server address.
 */
class KeystoreTokenStore(context: Context, server: String) : TokenStore {
    private val prefs = context.getSharedPreferences("motif_session", Context.MODE_PRIVATE)
    private val prefKey = "session:$server"

    override fun load(): StoredSession? = runCatching {
        val stored = prefs.getString(prefKey, null) ?: return null
        val (iv, data) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val obj = JSONObject(String(cipher.doFinal(data)))
        StoredSession(obj.getString("user_id"), obj.getString("refresh_token"))
    }.getOrNull()

    override fun save(session: StoredSession?) {
        if (session == null) {
            prefs.edit().remove(prefKey).apply()
            return
        }
        val plain = JSONObject().put("user_id", session.userId).put("refresh_token", session.refreshToken).toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
        // commit, not apply: the old refresh token stops working the moment the server rotates it.
        prefs.edit().putString(prefKey, encoded).commit()
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "motif_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
