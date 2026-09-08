package com.blackcloudgroup.securemessenger

import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.util.concurrent.CompletableFuture

interface ChatController {
    fun sendMessage(payload: ByteArray): CompletableFuture<Unit>
}

class ChatProtocol(
    private val onMessageReceived: ((String, ByteArray) -> Unit)? = null
) : ProtocolHandler<ChatController>(Long.MAX_VALUE, Long.MAX_VALUE) {

    override fun onStartInitiator(stream: Stream): CompletableFuture<ChatController> {
        val handler = ChatInitiator(stream)
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<ChatController> {
        val handler = ChatResponder(stream)
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    inner class ChatInitiator(private val stream: Stream) : ProtocolMessageHandler<ByteBuf>, ChatController {
        override fun onMessage(stream: Stream, msg: ByteBuf) {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)
            
            // Extract the real remote PeerId directly from the libp2p Stream
            onMessageReceived?.invoke(stream.remotePeerId().toBase58(), bytes)
        }

        override fun sendMessage(payload: ByteArray): CompletableFuture<Unit> {
            val future = CompletableFuture<Unit>()
            try {
                stream.writeAndFlush(Unpooled.wrappedBuffer(payload))
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(
                    RuntimeException("Failed to write and flush message payload to stream", t)
                )
            }
            return future
        }
    }

    inner class ChatResponder(private val stream: Stream) : ProtocolMessageHandler<ByteBuf>, ChatController {
        override fun onMessage(stream: Stream, msg: ByteBuf) {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)
            
            // Extract the real remote PeerId directly from the libp2p Stream
            onMessageReceived?.invoke(stream.remotePeerId().toBase58(), bytes)
        }

        override fun sendMessage(payload: ByteArray): CompletableFuture<Unit> {
            val future = CompletableFuture<Unit>()
            try {
                stream.writeAndFlush(Unpooled.wrappedBuffer(payload))
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(
                    RuntimeException("Failed to write and flush message payload to stream", t)
                )
            }
            return future
        }
    }
}

open class ChatBinding(protocol: ChatProtocol) : StrictProtocolBinding<ChatController>("/blackcloud/chat/1.0.0", protocol)

class Chat(
    onMessageReceived: ((String, ByteArray) -> Unit)? = null
) : ChatBinding(ChatProtocol(onMessageReceived))