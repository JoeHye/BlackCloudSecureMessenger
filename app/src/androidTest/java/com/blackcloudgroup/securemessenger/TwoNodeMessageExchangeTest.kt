// File 1 of 1: TwoNodeMessageExchangeTest.kt
package com.blackcloudgroup.securemessenger

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TwoNodeMessageExchangeTest {

    // Node A components
    private lateinit var nodeAP2P: P2PManager
    private lateinit var nodeADatabase: BlackCloudSecureDatabase
    private lateinit var nodeARepository: MessageRepository
    private lateinit var nodeACoordinator: MessageCoordinator

    // Node B components
    private lateinit var nodeBP2P: P2PManager
    private lateinit var nodeBDatabase: BlackCloudSecureDatabase
    private lateinit var nodeBRepository: MessageRepository
    private lateinit var nodeBCoordinator: MessageCoordinator

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Initialize Node A
        nodeAP2P = P2PManager()
        // Using in-memory database to ensure isolation between Node A and Node B
        nodeADatabase = Room.inMemoryDatabaseBuilder(context, BlackCloudSecureDatabase::class.java).build()
        nodeARepository = MessageRepository(nodeADatabase.messengerDao())
        nodeACoordinator = MessageCoordinator(nodeAP2P, nodeARepository)

        // Initialize Node B
        nodeBP2P = P2PManager()
        nodeBDatabase = Room.inMemoryDatabaseBuilder(context, BlackCloudSecureDatabase::class.java).build()
        nodeBRepository = MessageRepository(nodeBDatabase.messengerDao())
        nodeBCoordinator = MessageCoordinator(nodeBP2P, nodeBRepository)
    }

    @After
    fun tearDown() {
        // Shutdown Node A
        if (::nodeAP2P.isInitialized) nodeAP2P.stopHost()
        if (::nodeACoordinator.isInitialized) nodeACoordinator.shutdown()
        if (::nodeADatabase.isInitialized) nodeADatabase.close()

        // Shutdown Node B
        if (::nodeBP2P.isInitialized) nodeBP2P.stopHost()
        if (::nodeBCoordinator.isInitialized) nodeBCoordinator.shutdown()
        if (::nodeBDatabase.isInitialized) nodeBDatabase.close()
    }

    @Test
    fun testTwoNodeEncryptedMessageExchange() {
        val timeoutSeconds = 15L
        val testMessage = "Alpha Protocol: Black Cloud Secure Messenger MVP Verification."

        // 1. Start Node A on port 10001
        val nodeAStartResult = nodeAP2P.startHost("127.0.0.1", 10001)
        if (nodeAStartResult is RepositoryResult.Failure) {
            fail("Node A failed to start: ${nodeAStartResult.message} | Cause: ${nodeAStartResult.cause}")
        }
        val nodeAPeerId = (nodeAStartResult as RepositoryResult.Success).value.toBase58()
        val nodeAListenAddr = "/ip4/127.0.0.1/tcp/10001/p2p/$nodeAPeerId"

        // 2. Start Node B on port 10002
        val nodeBStartResult = nodeBP2P.startHost("127.0.0.1", 10002)
        if (nodeBStartResult is RepositoryResult.Failure) {
            fail("Node B failed to start: ${nodeBStartResult.message} | Cause: ${nodeBStartResult.cause}")
        }
        val nodeBPeerId = (nodeBStartResult as RepositoryResult.Success).value.toBase58()

        // 3. Node B connects to Node A
        val connectFuture = nodeBP2P.connectToPeer(nodeAListenAddr)
        val connectResult = connectFuture.get(timeoutSeconds, TimeUnit.SECONDS)
        if (connectResult is RepositoryResult.Failure) {
            fail("Node B failed to connect to Node A: ${connectResult.message} | Cause: ${connectResult.cause}")
        }

        // 4. Node B sends a message to Node A
        val payloadBytes = testMessage.toByteArray(Charsets.UTF_8)
        val sendFuture = nodeBP2P.sendMessage(nodeAPeerId, payloadBytes)
        val sendResult = sendFuture.get(timeoutSeconds, TimeUnit.SECONDS)
        if (sendResult is RepositoryResult.Failure) {
            fail("Node B failed to send message: ${sendResult.message} | Cause: ${sendResult.cause}")
        }

        // 5. Poll Node A's database to verify receipt and persistence
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000)
        var receivedPlaintext: String? = null

        // We use a bounded polling loop instead of an infinite while(true)
        while (System.currentTimeMillis() < deadline) {
            // Because we run in instrumented test, we use runBlocking for suspend functions implicitly
            // or we can safely block the test thread while polling
            kotlinx.coroutines.runBlocking {
                val dbResult = nodeARepository.getMessagesForPeer(nodeBPeerId)
                if (dbResult is RepositoryResult.Success && dbResult.value.isNotEmpty()) {
                    // Message arrived in DB
                    val entity = dbResult.value.first()
                    val decryptResult = nodeARepository.decryptPayload(nodeBPeerId, entity.encryptedPayload)
                    if (decryptResult is RepositoryResult.Success) {
                        receivedPlaintext = decryptResult.value
                    }
                }
            }
            if (receivedPlaintext != null) break
            Thread.sleep(500)
        }

        // 6. Assertions
        assertNotNull("Node A never received or failed to persist/decrypt the message within the ${timeoutSeconds}s timeout.", receivedPlaintext)
        assertEquals("The decrypted message on Node A did not match the original plaintext sent by Node B.", testMessage, receivedPlaintext)
    }
}