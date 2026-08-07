package com.blackcloudgroup.securemessenger

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MessageRepository(private val dao: SecureMessengerDao) {

    // Helper for local payload encryption using AES-GCM
    private val secretKey: SecretKey by lazy {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    suspend fun getMessagesForPeer(peerId: String): List<EncryptedMessageEntity> {
        return dao.getMessagesForPeer(peerId)
    }

    suspend fun insertMessage(message: EncryptedMessageEntity) {
        dao.insertMessage(message)
    }

    // Returns IV + ciphertext concatenated, ready to store directly in encryptedPayload
    fun encryptPayload(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ciphertext
    }

    // Expects the same IV + ciphertext layout produced by encryptPayload
    fun decryptPayload(combined: ByteArray): String {
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    suspend fun processAndSaveMessage(incomingPayload: String, fromPeerId: String, toPeerId: String) {
        val encrypted = encryptPayload(incomingPayload)
        insertMessage(
            EncryptedMessageEntity(
                senderPeerId = fromPeerId,
                recipientPeerId = toPeerId,
                encryptedPayload = encrypted,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun wipeKeystore() {
        // No-op or wipe repository local keys
    }
}
