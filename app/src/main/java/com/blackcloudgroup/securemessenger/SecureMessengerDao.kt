package com.blackcloudgroup.securemessenger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SecureMessengerDao {

    // Returns messages where the given peer is either the sender or the recipient
    // (i.e. the full conversation thread with that peer)
    @Query("SELECT * FROM messages WHERE senderPeerId = :peerId OR recipientPeerId = :peerId ORDER BY timestamp ASC")
    suspend fun getMessagesForPeer(peerId: String): List<EncryptedMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: EncryptedMessageEntity)
}
