package com.blackcloudgroup.securemessenger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory

@Database(entities = [EncryptedMessageEntity::class], version = 1, exportSchema = false)
abstract class BlackCloudSecureDatabase : RoomDatabase() {

    abstract fun messengerDao(): SecureMessengerDao

    companion object {
        @Volatile
        private var INSTANCE: BlackCloudSecureDatabase? = null

        fun getInstance(context: Context, passphrase: ByteArray): BlackCloudSecureDatabase {
            return INSTANCE ?: synchronized(this) {
                // Initialize the SQLCipher factory to encrypt the Room database
                val factory = SupportFactory(passphrase)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BlackCloudSecureDatabase::class.java,
                    "blackcloud_secure_messenger.db"
                )
                    .openHelperFactory(factory)
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
