package com.garan.tesnav.teslable

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wrapped P-256 identity for Android 23+, where hardware ECDH is not generally available.
 * Private key is stored only as ciphertext under an Android Keystore AES key; never logged/exported.
 */
internal object TeslaBleIdentity {
    @Synchronized fun loadOrCreate(context: Context): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "tesnav_tesla_ble_identity_wrap"
        val prefs = context.getSharedPreferences("tesla_ble_identity", Context.MODE_PRIVATE)
        if (!store.containsAlias(alias)) {
            check(!prefs.contains("private")) { "蓝牙钥匙保护密钥已丢失，请清除应用数据后重新配钥" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
        val wrapping = store.getKey(alias, null) as SecretKey
        val privateText = prefs.getString("private", null)
        if (privateText != null) {
            val iv = Base64.decode(prefs.getString("iv", null) ?: error("蓝牙钥匙不完整"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, iv))
            }
            val factory = KeyFactory.getInstance("EC")
            return KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.decode(prefs.getString("public", null), Base64.NO_WRAP))),
                factory.generatePrivate(PKCS8EncodedKeySpec(cipher.doFinal(Base64.decode(privateText, Base64.NO_WRAP)))))
        }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapping) }
        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        check(prefs.edit().putString("private", encode(cipher.doFinal(pair.private.encoded)))
            .putString("iv", encode(cipher.iv)).putString("public", encode(pair.public.encoded)).commit()) { "蓝牙钥匙保存失败" }
        return pair
    }
}
