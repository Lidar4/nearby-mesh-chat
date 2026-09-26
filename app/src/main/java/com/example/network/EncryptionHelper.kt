package com.example.network

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

object EncryptionHelper {

    private const val TAG = "EncryptionHelper"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val IDENTITY_KEY_ALIAS = "NC_IdentityKey"

    private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
    private const val RSA_OAEP_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    private const val AES_KEY_SIZE_BYTE = 32 // 256-bit
    private const val ITERATIONS = 1000
    private const val SALT_VALUE = "NearbyChatMeshSaltKey"

    init {
        // Initialize Identity Key Pair inside Android Keystore on class load
        try {
            generateIdentityKeyPairIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Android Keystore keys: ${e.message}")
        }
    }

    /**
     * Generates a persistent hardware-backed RSA 2048-bit keypair inside the Android Keystore
     */
    private fun generateIdentityKeyPairIfNeeded() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(IDENTITY_KEY_ALIAS)) {
            Log.d(TAG, "Generating secure hardware identity keypair inside Android Keystore...")
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
            val parameterSpec = KeyGenParameterSpec.Builder(
                IDENTITY_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).run {
                setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                setKeySize(2048)
                build()
            }
            kpg.initialize(parameterSpec)
            kpg.generateKeyPair()
        }
    }

    /**
     * Exports the local RSA Public Key as a Base64-encoded string
     */
    fun getLocalPublicKey(): String {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val entry = keyStore.getEntry(IDENTITY_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            val publicKey = entry?.certificate?.publicKey ?: return ""
            Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export public key: ${e.message}")
            ""
        }
    }

    /**
     * Decrypts the digital envelope using the hardware-backed private key stored in Android Keystore
     * Format: [RSA_ENCRYPTED_AES_KEY_BASE64]:[IV_BASE64]:[CIPHERTEXT_BASE64]
     */
    fun decryptWithPrivateKey(envelopeBase64: String): String {
        try {
            val parts = envelopeBase64.split(":")
            if (parts.size != 3) {
                return envelopeBase64 // Fallback if not enveloped
            }

            val rsaEncryptedAesKey = Base64.decode(parts[0], Base64.NO_WRAP)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)

            // 1. Retrieve Private Key from Keystore
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val entry = keyStore.getEntry(IDENTITY_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            val privateKey = entry?.privateKey ?: throw Exception("Private key entry missing")

            // 2. Decrypt the AES session key using RSA-OAEP
            val rsaCipher = Cipher.getInstance(RSA_OAEP_ALGORITHM)
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
            val aesKeyBytes = rsaCipher.doFinal(rsaEncryptedAesKey)
            val aesSecretKey = SecretKeySpec(aesKeyBytes, "AES")

            // 3. Decrypt the message text using AES-GCM
            val aesCipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            aesCipher.init(Cipher.DECRYPT_MODE, aesSecretKey, parameterSpec)
            val decryptedBytes = aesCipher.doFinal(ciphertext)

            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "E2EE digital envelope decryption failed: ${e.message}")
            return "[Decryption Error - Secure Identity Mismatch]"
        }
    }

    /**
     * Encrypts the plaintext using a Digital Envelope with the recipient's RSA Public Key
     * Returns: [RSA_ENCRYPTED_AES_KEY_BASE64]:[IV_BASE64]:[CIPHERTEXT_BASE64]
     */
    fun encryptWithPublicKey(plaintext: String, recipientPublicKeyBase64: String): String {
        return try {
            // 1. Reconstruct Public Key from Base64
            val keyBytes = Base64.decode(recipientPublicKeyBase64, Base64.NO_WRAP)
            val spec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(spec)

            // 2. Generate random 256-bit AES Session key and IV
            val secureRandom = SecureRandom()
            val aesKeyBytes = ByteArray(AES_KEY_SIZE_BYTE)
            secureRandom.nextBytes(aesKeyBytes)
            val aesSecretKey = SecretKeySpec(aesKeyBytes, "AES")

            val iv = ByteArray(IV_LENGTH_BYTE)
            secureRandom.nextBytes(iv)

            // 3. Encrypt the AES Session Key using RSA-OAEP
            val rsaCipher = Cipher.getInstance(RSA_OAEP_ALGORITHM)
            rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val rsaEncryptedAesKey = rsaCipher.doFinal(aesKeyBytes)

            // 4. Encrypt the plaintext using AES-GCM
            val aesCipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            aesCipher.init(Cipher.ENCRYPT_MODE, aesSecretKey, parameterSpec)
            val ciphertextBytes = aesCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // 5. Package as Base64 digital envelope
            val aesKeyEncBase64 = Base64.encodeToString(rsaEncryptedAesKey, Base64.NO_WRAP)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ciphertextBase64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)

            "$aesKeyEncBase64:$ivBase64:$ciphertextBase64"
        } catch (e: Exception) {
            Log.e(TAG, "Digital envelope encryption failed: ${e.message}")
            "[Encryption Error]"
        }
    }

    /**
     * Derives a symmetric key using PBKDF2 from a passphrase (useful as fallback)
     */
    fun deriveKey(passphrase: String, salt: String = SALT_VALUE): SecretKey {
        return try {
            val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
            val spec = PBEKeySpec(
                passphrase.toCharArray(),
                salt.toByteArray(),
                ITERATIONS,
                256
            )
            val tmp = factory.generateSecret(spec)
            SecretKeySpec(tmp.encoded, "AES")
        } catch (e: Exception) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(passphrase.toByteArray(Charsets.UTF_8))
            SecretKeySpec(hash, "AES")
        }
    }

    /**
     * Symmetric encryption fallback (PBKDF2 + AES-GCM)
     */
    fun encryptSymmetric(plaintext: String, passphrase: String): String {
        return try {
            val key = deriveKey(passphrase)
            val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            val iv = ByteArray(IV_LENGTH_BYTE)
            SecureRandom().nextBytes(iv)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)
            val ciphertextBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ciphertextBase64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
            
            "sym:$ivBase64:$ciphertextBase64"
        } catch (e: Exception) {
            "[PlaintextFallback]:$plaintext"
        }
    }

    /**
     * Symmetric decryption fallback (PBKDF2 + AES-GCM)
     */
    fun decryptSymmetric(payload: String, passphrase: String): String {
        if (!payload.startsWith("sym:")) {
            return payload
        }
        return try {
            val parts = payload.substring(4).split(":")
            if (parts.size != 2) return payload

            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

            val key = deriveKey(passphrase)
            val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)

            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)
            val decryptedBytes = cipher.doFinal(ciphertext)

            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            "[Decryption Error - Mismatched Password]"
        }
    }
}
