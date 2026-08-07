package com.blackcloudgroup.securemessenger

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class EncryptedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderPeerId: String,
    val recipientPeerId: String,
    val encryptedPayload: ByteArray,
    val timestamp: Long
)
