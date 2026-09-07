package com.blackcloudgroup.securemessenger

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS_PREFIX = "secure_messenger_peer_"
private const val GCM_IV_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128

sealed class RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : RepositoryResult<Nothing>()
}

class MessageRepository(private val dao: SecureMessengerDao) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKeyForPeer(peerId: String): RepositoryResult<SecretKey> {
        val alias = KEY_ALIAS_PREFIX + peerId
        return try {
            val existingKey = keyStore.getKey(alias, null) as? SecretKey
            if (existingKey != null) {
                RepositoryResult.Success(existingKey)
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                RepositoryResult.Success(keyGenerator.generateKey())
            }
        } catch (e: Exception) {
            RepositoryResult.Failure("Failed to get/create Keystore key for peer $peerId", e)
        }
    }

    suspend fun getMessagesForPeer(peerId: String): RepositoryResult<List<EncryptedMessageEntity>> {
        return try {
            RepositoryResult.Success(dao.getMessagesForPeer(peerId))
        } catch (e: Exception) {
            RepositoryResult.Failure("Failed to load messages for peer $peerId", e)
        }
    }

    suspend fun insertMessage(message: EncryptedMessageEntity): RepositoryResult<Unit> {
        return try {
            dao.insertMessage(message)
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Failure("Failed to insert message for peer ${message.recipientPeerId}", e)
        }
    }

    fun encryptPayload(peerId: String, plaintext: String): RepositoryResult<ByteArray> {
        val keyResult = getOrCreateKeyForPeer(peerId)
        val key = when (keyResult) {
            is RepositoryResult.Success -> keyResult.value
            is RepositoryResult.Failure -> return keyResult
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            RepositoryResult.Success(iv + ciphertext)
        } catch (e: Exception) {
            RepositoryResult.Failure("Encryption failed for peer $peerId", e)
        }
    }

    fun decryptPayload(peerId: String, combined: ByteArray): RepositoryResult<String> {
        if (combined.size <= GCM_IV_LENGTH_BYTES) {
            return RepositoryResult.Failure(
                "Payload too short to contain IV + ciphertext for peer $peerId"
            )
        }
        val keyResult = getOrCreateKeyForPeer(peerId)
        val key = when (keyResult) {
            is RepositoryResult.Success -> keyResult.value
            is RepositoryResult.Failure -> return keyResult
        }
        return try {
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            RepositoryResult.Success(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (e: Exception) {
            RepositoryResult.Failure("Decryption failed for peer $peerId", e)
        }
    }

    suspend fun processAndSaveMessage(
        incomingPayload: String,
        fromPeerId: String,
        toPeerId: String
    ): RepositoryResult<Unit> {
        val encryptResult = encryptPayload(fromPeerId, incomingPayload)
        val encrypted = when (encryptResult) {
            is RepositoryResult.Success -> encryptResult.value
            is RepositoryResult.Failure -> return encryptResult
        }
        return insertMessage(
            EncryptedMessageEntity(
                senderPeerId = fromPeerId,
                recipientPeerId = toPeerId,
                encryptedPayload = encrypted,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun wipeKeyForPeer(peerId: String): RepositoryResult<Unit> {
        val alias = KEY_ALIAS_PREFIX + peerId
        return try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Failure("Failed to wipe Keystore key for peer $peerId", e)
        }
    }

    fun wipeAllKeys(): RepositoryResult<Unit> {
        return try {
            val aliasesToDelete = keyStore.aliases().toList().filter { it.startsWith(KEY_ALIAS_PREFIX) }
            aliasesToDelete.forEach { keyStore.deleteEntry(it) }
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Failure("Failed to wipe all Keystore keys", e)
        }
    }
}