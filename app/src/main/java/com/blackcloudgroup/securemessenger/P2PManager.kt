package com.blackcloudgroup.securemessenger

import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * P2PManager manages the local libp2p Host lifecycle, incoming/outgoing stream routing,
 * and direct point-to-point communication over TCP without mDNS or automated discovery.
 */
class P2PManager {

    companion object {
        const val PROTOCOL_ID = "/blackcloud/chat/1.0.0"
        const val DEFAULT_LISTEN_PORT = 10001
        const val DEFAULT_LISTEN_INTERFACE = "0.0.0.0"
        const val NETWORK_TIMEOUT_SECONDS = 15L
    }

    private var host: Host? = null
    private var chatBinding: Chat? = null

    // Maps string representation of PeerId to active ChatController for outgoing streams
    private val activeConnections = ConcurrentHashMap<String, ChatController>()

    // Callback hook for incoming payloads forwarded from ChatProtocol.
    @Volatile
    private var incomingMessageCallback: ((String, ByteArray) -> Unit)? = null

    /**
     * Registers a listener callback for incoming raw payload frames from remote peers.
     */
    fun setIncomingMessageListener(listener: (String, ByteArray) -> Unit) {
        this.incomingMessageCallback = listener
    }

    /**
     * Clears the registered incoming message listener.
     */
    fun clearIncomingMessageListener() {
        this.incomingMessageCallback = null
    }

    /**
     * Starts the local libp2p host on the specified port using TCP transport and Noise encryption.
     * Manual multiaddr entry only (no mDNS, no DHT, no peer discovery).
     */
    @Synchronized
    fun startHost(
        listenInterface: String = DEFAULT_LISTEN_INTERFACE,
        port: Int = DEFAULT_LISTEN_PORT
    ): RepositoryResult<PeerId> {
        if (host != null) {
            return RepositoryResult.Failure(
                message = "Host is already running with PeerId: ${host?.peerId?.toBase58()}",
                cause = IllegalStateException("Host already initialized")
            )
        }

        val listenMultiaddrString = "/ip4/$listenInterface/tcp/$port"
        val listenMultiaddr = try {
            Multiaddr.fromString(listenMultiaddrString)
        } catch (e: IllegalArgumentException) {
            return RepositoryResult.Failure(
                message = "Failed to construct valid listen multiaddr from '$listenMultiaddrString'",
                cause = e
            )
        }

        return try {
            // Instantiate the protocol binding using the updated two-argument signature,
            // passing the true remote PeerId received from the wire directly to the callback.
            val binding = Chat { peerId, payload ->
                incomingMessageCallback?.invoke(peerId, payload)
            }
            this.chatBinding = binding

            val newHost = host {
                protocols {
                    +binding
                }
                transports {
                    add(::TcpTransport)
                }
                secureChannels {
                    add(::NoiseXXSecureChannel)
                }
                muxers {
                    add(StreamMuxerProtocol.Mplex)
                }
                network {
                    listen(listenMultiaddr.toString())
                }
            }

            val startFuture = newHost.start()
            startFuture.get(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            this.host = newHost

            RepositoryResult.Success(newHost.peerId)
        } catch (te: TimeoutException) {
            this.host?.stop()
            this.host = null
            this.chatBinding = null
            RepositoryResult.Failure(
                message = "Timed out starting libp2p host on $listenMultiaddrString after $NETWORK_TIMEOUT_SECONDS seconds",
                cause = te
            )
        } catch (e: Exception) {
            this.host?.stop()
            this.host = null
            this.chatBinding = null
            RepositoryResult.Failure(
                message = "Fatal error initializing libp2p host on $listenMultiaddrString: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Dials a remote peer using a raw multiaddr string (e.g. "/ip4/192.168.1.50/tcp/10001/p2p/<PeerID>").
     */
    fun connectToPeer(multiaddrString: String): CompletableFuture<RepositoryResult<Unit>> {
        val resultFuture = CompletableFuture<RepositoryResult<Unit>>()
        val currentHost = host

        if (currentHost == null) {
            resultFuture.complete(
                RepositoryResult.Failure(
                    message = "Cannot connect to peer: Local P2P host is not running. Call startHost() first.",
                    cause = IllegalStateException("Host not started")
                )
            )
            return resultFuture
        }

        val targetMultiaddr = try {
            Multiaddr.fromString(multiaddrString)
        } catch (e: IllegalArgumentException) {
            resultFuture.complete(
                RepositoryResult.Failure(
                    message = "Malformed multiaddr string '$multiaddrString': ${e.message}",
                    cause = e
                )
            )
            return resultFuture
        }

        val targetPeerId = targetMultiaddr.getPeerId()
        if (targetPeerId == null) {
            resultFuture.complete(
                RepositoryResult.Failure(
                    message = "Multiaddr must contain a p2p peer ID component to establish a stream",
                    cause = IllegalArgumentException("Missing PeerId in multiaddr")
                )
            )
            return resultFuture
        }

        val targetPeerIdString = targetPeerId.toBase58()

        try {
            val streamPromise = currentHost.newStream<ChatController>(
                listOf(PROTOCOL_ID),
                targetPeerId,
                targetMultiaddr
            )

            streamPromise.controller.orTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete { controller, throwable ->
                    if (throwable != null) {
                        activeConnections.remove(targetPeerIdString)
                        resultFuture.complete(
                            RepositoryResult.Failure(
                                message = "Failed to establish chat stream to $multiaddrString: ${throwable.message}",
                                cause = throwable
                            )
                        )
                    } else if (controller == null) {
                        activeConnections.remove(targetPeerIdString)
                        resultFuture.complete(
                            RepositoryResult.Failure(
                                message = "Chat controller returned null after negotiating $PROTOCOL_ID with $multiaddrString",
                                cause = IllegalStateException("Null protocol controller")
                            )
                        )
                    } else {
                        activeConnections[targetPeerIdString] = controller
                        resultFuture.complete(RepositoryResult.Success(Unit))
                    }
                }
        } catch (e: Exception) {
            resultFuture.complete(
                RepositoryResult.Failure(
                    message = "Unexpected failure initiating stream to $multiaddrString: ${e.message}",
                    cause = e
                )
            )
        }

        return resultFuture
    }

    /**
     * Sends a raw payload to a connected peer identified by their PeerId string.
     */
    fun sendMessage(peerId: String, payload: ByteArray): CompletableFuture<RepositoryResult<Unit>> {
        val resultFuture = CompletableFuture<RepositoryResult<Unit>>()

        if (host == null) {
            resultFuture.complete(
                RepositoryResult.Failure(
                    message = "Cannot send message: Local P2P host is stopped.",
                    cause = IllegalStateException("Host stopped")
                )
            )
            return resultFuture
        }

        if (payload.isEmpty()) {
            resultFuture.complete(
                RepositoryResult.Failure(
                    message = "Cannot send empty payload to peer $peerId",
                    cause = IllegalArgumentException("Empty payload")
                )
            )
            return resultFuture
        }

        val controller = activeConnections[peerId]
        if (controller == null) {
            resultFuture.complete(
                RepositoryResult.Failure(
                    message = "No active chat stream available for peer $peerId. Call connectToPeer() first.",
                    cause = IllegalStateException("Stream not connected")
                )
            )
            return resultFuture
        }

        try {
            controller.sendMessage(payload)
                .orTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete { _, throwable ->
                    if (throwable != null) {
                        activeConnections.remove(peerId)
                        resultFuture.complete(
                            RepositoryResult.Failure(
                                message = "Failed to deliver payload over wire to peer $peerId: ${throwable.message}",
                                cause = throwable
                            )
                        )
                    } else {
                        resultFuture.complete(RepositoryResult.Success(Unit))
                    }
                }
        } catch (e: Exception) {
            activeConnections.remove(peerId)
            resultFuture.complete(
                RepositoryResult.Failure(
                    message = "Exception dispatched while sending payload to peer $peerId: ${e.message}",
                    cause = e
                )
            )
        }

        return resultFuture
    }

    /**
     * Drops the cached controller and tears down the active stream to the specified peer.
     */
    fun disconnect(peerId: String): RepositoryResult<Unit> {
        val controller = activeConnections.remove(peerId)
            ?: return RepositoryResult.Failure(
                message = "Cannot disconnect: No active connection found for peer $peerId",
                cause = NoSuchElementException("Peer not found in active streams")
            )

        return try {
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            RepositoryResult.Failure(
                message = "Error disconnecting peer $peerId: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Shuts down all active streams, clears connections, and unbinds the local libp2p listening socket.
     */
    @Synchronized
    fun stopHost(): RepositoryResult<Unit> {
        val currentHost = host
            ?: return RepositoryResult.Failure(
                message = "Cannot stop host: No host instance is currently active",
                cause = IllegalStateException("Host not running")
            )

        return try {
            activeConnections.clear()
            val stopFuture = currentHost.stop()
            stopFuture.get(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            this.host = null
            this.chatBinding = null
            RepositoryResult.Success(Unit)
        } catch (te: TimeoutException) {
            this.host = null
            this.chatBinding = null
            RepositoryResult.Failure(
                message = "Host shutdown timed out after $NETWORK_TIMEOUT_SECONDS seconds",
                cause = te
            )
        } catch (e: Exception) {
            this.host = null
            this.chatBinding = null
            RepositoryResult.Failure(
                message = "Encountered error while stopping libp2p host: ${e.message}",
                cause = e
            )
        }
    }

    fun isRunning(): Boolean = host != null

    fun getLocalPeerId(): String? = host?.peerId?.toBase58()

    fun getListenAddresses(): List<String> {
        val currentHost = host ?: return emptyList()
        return currentHost.listenAddresses().map { it.toString() }
    }
}