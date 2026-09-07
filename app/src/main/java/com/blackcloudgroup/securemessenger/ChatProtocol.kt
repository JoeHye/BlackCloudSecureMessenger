package com.blackcloudgroup.securemessenger

import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.netty.buffer.ByteBuf
import java.util.concurrent.CompletableFuture

interface ChatController {
    fun sendMessage(payload: ByteArray): CompletableFuture<Unit>
}

class Chat(protocol: ChatProtocol) : ChatBinding(protocol) {
    constructor(onMessageReceived: ((ByteArray) -> Unit)? = null) : this(ChatProtocol(onMessageReceived))
}

open class ChatBinding(chat: ChatProtocol) :
    StrictProtocolBinding<ChatController>("/blackcloud/chat/1.0.0", chat)

open class ChatProtocol(
    var onMessageReceived: ((ByteArray) -> Unit)? = null
) : ProtocolHandler<ChatController>(Long.MAX_VALUE, Long.MAX_VALUE) {

    override fun onStartInitiator(stream: Stream): CompletableFuture<ChatController> {
        val handler = ChatInitiator()
        stream.pushHandler(handler)
        return handler.activeFuture
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<ChatController> {
        val handler = ChatResponder()
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    open inner class ChatResponder : ProtocolMessageHandler<ByteBuf>, ChatController {
        override fun onMessage(stream: Stream, msg: ByteBuf) {
            try {
                val bytes = msg.toByteArray()
                onMessageReceived?.invoke(bytes)
            } catch (t: Throwable) {
                throw Libp2pException("Failed to process incoming chat message payload on responder", t)
            }
        }

        override fun sendMessage(payload: ByteArray): CompletableFuture<Unit> {
            throw Libp2pException("This is chat responder only")
        }
    }

    open inner class ChatInitiator : ProtocolMessageHandler<ByteBuf>, ChatController {
        val activeFuture = CompletableFuture<ChatController>()
        private var stream: Stream? = null

        override fun onActivated(stream: Stream) {
            this.stream = stream
            activeFuture.complete(this)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            try {
                val bytes = msg.toByteArray()
                onMessageReceived?.invoke(bytes)
            } catch (t: Throwable) {
                throw Libp2pException("Failed to process incoming chat message payload on initiator", t)
            }
        }

        override fun sendMessage(payload: ByteArray): CompletableFuture<Unit> {
            val future = CompletableFuture<Unit>()
            val activeStream = stream
            if (activeStream == null) {
                future.completeExceptionally(
                    Libp2pException("Cannot send message: Stream has not been activated or is null")
                )
                return future
            }

            try {
                val byteBuf = payload.toByteBuf()
                activeStream.writeAndFlush(byteBuf)
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(
                    Libp2pException("Failed to write and flush message payload to stream", t)
                )
            }
            return future
        }
    }
}