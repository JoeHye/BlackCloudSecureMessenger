// File 1 of 1: MainActivity.kt
package com.blackcloudgroup.securemessenger

import android.content.Context
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize Android Keystore and ensure our Master Key exists
        val keyAlias = "db_passphrase_master_key"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val keySpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            
            keyGenerator.init(keySpec)
            keyGenerator.generateKey()
        }
        
        val secretKey = keyStore.getKey(keyAlias, null) as SecretKey

        // 2. Retrieve or generate the encrypted SQLCipher passphrase
        val prefs = getSharedPreferences("secure_messenger_prefs", Context.MODE_PRIVATE)
        val encryptedPassphrase64 = prefs.getString("encrypted_db_passphrase", null)
        val iv64 = prefs.getString("db_passphrase_iv", null)
        
        val passphraseBytes: ByteArray
        
        if (encryptedPassphrase64 == null || iv64 == null) {
            // Generate a secure 32-byte random passphrase for SQLCipher
            passphraseBytes = ByteArray(32)
            SecureRandom().nextBytes(passphraseBytes)
            
            // Encrypt the passphrase using our Keystore key
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val encryptedBytes = cipher.doFinal(passphraseBytes)
            val ivBytes = cipher.iv
            
            // Persist the encrypted material
            prefs.edit()
                .putString("encrypted_db_passphrase", Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                .putString("db_passphrase_iv", Base64.encodeToString(ivBytes, Base64.NO_WRAP))
                .apply()
        } else {
            // Decrypt the existing passphrase for SQLCipher
            val encryptedBytes = Base64.decode(encryptedPassphrase64, Base64.NO_WRAP)
            val ivBytes = Base64.decode(iv64, Base64.NO_WRAP)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            passphraseBytes = cipher.doFinal(encryptedBytes)
        }

        // 3. Initialize secure database with the raw byte array and construct the DAO dependency
        val database = BlackCloudSecureDatabase.getInstance(applicationContext, passphraseBytes)
        val messengerDao = database.messengerDao()

        // 4. Manual dependency instantiation for the single-activity MVP
        val p2pManager = P2PManager()
        val messageRepository = MessageRepository(messengerDao)
        val messageCoordinator = MessageCoordinator(p2pManager, messageRepository)

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(p2pManager, messageRepository, messageCoordinator) as T
            }
        }

        val viewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(viewModel = viewModel)
                }
            }
        }
    }
}