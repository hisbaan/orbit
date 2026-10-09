package com.hisbaan.orbit.history

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * The history's keys: an RSA key pair in the Android Keystore. The public half wraps each
 * conversation's key, in software, so the assistant can record on a locked phone. The private
 * half never leaves the Keystore and unwraps only within [AUTH_WINDOW_S] seconds of the user
 * proving it's them (PIN, pattern, password or a strong biometric), so the files are unreadable
 * without that, even to Orbit.
 *
 * Android only makes such keys with a screen lock set, and invalidates them when it's removed.
 */
class KeystoreHistoryKeys(context: Context) : HistoryKeys {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    override val deviceSecure: Boolean get() = keyguard.isDeviceSecure

    override fun valid(): Boolean {
        val key = privateKey() ?: return false
        return try {
            decryptCipher(key)
            true
        } catch (e: UserNotAuthenticatedException) {
            true // usable, once the user authenticates
        } catch (e: KeyPermanentlyInvalidatedException) {
            false
        }
    }

    override fun wrap(key: ByteArray): ByteArray {
        val public = publicKey() ?: generate()
        return Cipher.getInstance(RSA).apply { init(Cipher.ENCRYPT_MODE, public, OAEP) }.doFinal(key)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val key = privateKey() ?: throw HistoryKeysLostException()
        val cipher = try {
            decryptCipher(key)
        } catch (e: UserNotAuthenticatedException) {
            throw HistoryLockedException(e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw HistoryKeysLostException(e)
        }
        return cipher.doFinal(wrapped)
    }

    override fun delete() {
        keyStore().deleteEntry(ALIAS)
    }

    private fun decryptCipher(key: PrivateKey): Cipher = Cipher.getInstance(RSA).apply { init(Cipher.DECRYPT_MODE, key, OAEP) }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun privateKey(): PrivateKey? = keyStore().getKey(ALIAS, null) as? PrivateKey

    /** A plain software copy of the public key, so wrapping runs outside the Keystore and needs no authentication. */
    private fun publicKey(): PublicKey? = keyStore().getCertificate(ALIAS)?.publicKey?.let { softwareCopy(it) }

    private fun softwareCopy(key: PublicKey): PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(key.encoded))

    private fun generate(): PublicKey {
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(AUTH_WINDOW_S, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    // Adding a fingerprint would otherwise wipe the history. Whoever can add one knows the PIN anyway.
                    .setInvalidatedByBiometricEnrollment(false)
                    .build(),
            )
        }.generateKeyPair()
        return softwareCopy(pair.public)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "orbit_history_v1"

        /** Long enough to unwrap every conversation's key after one prompt. */
        const val AUTH_WINDOW_S = 30

        const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        /** The Keystore only does MGF1 with SHA-1 on many phones; spelled out so software and Keystore agree. */
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
