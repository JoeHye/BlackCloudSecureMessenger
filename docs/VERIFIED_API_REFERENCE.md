# Verified API Reference — Black Cloud Secure Messenger

This file lists every API signature actually confirmed against real source during MVP
development (jvm-libp2p v1.1.1 source, and this project's own files). It exists to stop
Gemini (or any model) from guessing at signatures that don't exist. **Paste the relevant
section into every prompt** that touches these APIs.

Anything NOT listed here has not been verified — treat it as unconfirmed, and instruct
whoever's writing code to say so explicitly rather than guess.

---

## Project files (com.blackcloudgroup.securemessenger)

### RepositoryResult (defined in MessageRepository.kt)
```kotlin
sealed class RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : RepositoryResult<Nothing>()
}
```
⚠️ The class is `Failure`, never `Error`. This was fabricated once already.

### MessageRepository.kt
```kotlin
class MessageRepository(private val dao: SecureMessengerDao)

suspend fun getMessagesForPeer(peerId: String): RepositoryResult<List<EncryptedMessageEntity>>
suspend fun processAndSaveMessage(incomingPayload: String, fromPeerId: String, toPeerId: String): RepositoryResult<Unit>
fun decryptPayload(peerId: String, combined: ByteArray): RepositoryResult<String>
fun encryptPayload(peerId: String, plaintext: String): RepositoryResult<ByteArray>
```
No zero-arg constructor exists. Requires a real `SecureMessengerDao`, which requires a
`BlackCloudSecureDatabase` instance, which requires an SQLCipher passphrase (`ByteArray`).

### EncryptedMessageEntity.kt
```kotlin
data class EncryptedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,   // Int, not String
    val senderPeerId: String,
    val recipientPeerId: String,
    val encryptedPayload: ByteArray,
    val timestamp: Long
)
```

### BlackCloudSecureDatabase.kt
```kotlin
companion object {
    fun getInstance(context: Context, passphrase: ByteArray): BlackCloudSecureDatabase
}
fun messengerDao(): SecureMessengerDao
```
`passphrase` is `ByteArray`, not `String` — a Base64-encoded String must be decoded first.

### P2PManager.kt
```kotlin
fun startHost(listenInterface: String = "0.0.0.0", port: Int = 10001): RepositoryResult<PeerId>
fun connectToPeer(multiaddrString: String): CompletableFuture<RepositoryResult<Unit>>
fun sendMessage(peerId: String, payload: ByteArray): CompletableFuture<RepositoryResult<Unit>>
fun disconnect(peerId: String): RepositoryResult<Unit>
fun stopHost(): RepositoryResult<Unit>
fun isRunning(): Boolean
fun getLocalPeerId(): String?
fun getListenAddresses(): List<String>
fun setIncomingMessageListener(listener: (peerId: String, payload: ByteArray) -> Unit)
fun clearIncomingMessageListener()
```

### ChatProtocol.kt
```kotlin
interface ChatController { fun sendMessage(payload: ByteArray): CompletableFuture<Unit> }
class ChatProtocol(onMessageReceived: ((String, ByteArray) -> Unit)? = null)
    : ProtocolHandler<ChatController>(Long.MAX_VALUE, Long.MAX_VALUE)
open class ChatBinding(protocol: ChatProtocol)
    : StrictProtocolBinding<ChatController>("/blackcloud/chat/1.0.0", protocol)
class Chat(onMessageReceived: ((String, ByteArray) -> Unit)? = null)
    : ChatBinding(ChatProtocol(onMessageReceived))
```

### MessageCoordinator.kt
```kotlin
class MessageCoordinator(p2pManager: P2PManager, messageRepository: MessageRepository)
val errorFlow: SharedFlow<RepositoryResult.Failure>
fun shutdown()
```

---

## jvm-libp2p (pinned version — CONFIRM against build.gradle.kts; tag naming has been
## wrong before — "1.1.1-RELEASE" does not exist, only bare "1.1.1")

```kotlin
// Stream (io.libp2p.core.Stream)
fun remotePeerId(): PeerId
fun writeAndFlush(msg: Any)          // returns Unit — NOT chainable, no .addListener()
fun pushHandler(protocolHandler: ProtocolMessageHandler<TMessage>)

// Multiaddr (io.libp2p.core.multiformats.Multiaddr)
companion object { fun fromString(s: String): Multiaddr }   // throws IllegalArgumentException
fun getPeerId(): PeerId?              // real method — getStringComponent("p2p") does NOT exist

// Host (io.libp2p.core.Host)
fun <T> newStream(protocols: List<ProtocolId>, conn: Connection): StreamPromise<T>
fun <T> newStream(protocols: List<ProtocolId>, peer: PeerId, vararg addr: Multiaddr): StreamPromise<T>
// No overload takes a bare Multiaddr as the 2nd arg, and protocols is ALWAYS List<ProtocolId>,
// never a bare ProtocolId string.

// StreamPromise (io.libp2p.core.StreamHandler)
data class StreamPromise<T>(val stream: CompletableFuture<Stream>, val controller: CompletableFuture<T>)

// ProtocolMessageHandler (io.libp2p.protocol) — this is an INTERFACE, implement with NO parens:
interface ProtocolMessageHandler<TMessage> {
    fun onActivated(stream: Stream) = Unit
    fun onMessage(stream: Stream, msg: TMessage) = Unit
    fun onClosed(stream: Stream) = Unit
    fun onException(cause: Throwable?) = Unit
}
// Correct:   : ProtocolMessageHandler<ByteBuf>, ChatController
// WRONG:     : ProtocolMessageHandler<ByteBuf>(), ChatController   (parens = invalid, it's an interface)

// ProtocolHandler (io.libp2p.protocol) — this IS an abstract class, requires 2 Long args:
abstract class ProtocolHandler<TController>(initiatorTrafficLimit: Long, responderTrafficLimit: Long)

// StrictProtocolBinding (io.libp2p.core.multistream)
abstract class StrictProtocolBinding<TController>(announce: ProtocolId, protocol: P2PChannelHandler<TController>)

// DSL builder (io.libp2p.core.dsl.host { ... })
transports { add(::TcpTransport) }                    // constructor REFERENCE, not TcpTransport()
secureChannels { add(::NoiseXXSecureChannel) }        // constructor REFERENCE, not an instance
muxers { add(StreamMuxerProtocol.Mplex) }             // the enum-like object, NOT MplexStreamMuxer()
protocols { +binding }                                // instance is correct here (covariant)
```

---

## Confirmed import paths (all previously gotten wrong at least once)
```
io.libp2p.protocol.ProtocolHandler              (NOT io.libp2p.core.protocol.ProtocolHandler)
io.libp2p.protocol.ProtocolMessageHandler       (NOT io.libp2p.core.protocol.*)
io.libp2p.core.multistream.StrictProtocolBinding (NOT io.libp2p.core.protocol.*)
io.libp2p.core.mux.StreamMuxerProtocol
io.libp2p.core.dsl.host
```

---

*Last updated: 2026-09-07, after Stage 4 completion. Add to this file whenever a new API
is verified or a new fabrication is caught — treat it as a living document, not a one-time
snapshot.*
