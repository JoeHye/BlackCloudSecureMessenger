package com.blackcloudgroup.securemessenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * MessageCoordinator bridges the asynchronous libp2p network layer (P2PManager)
 * with the persistent local storage layer (MessageRepository).
 */
class MessageCoordinator(
    private val p2pManager: P2PManager,
    private val messageRepository: MessageRepository
) {
    // Dedicated background scope to ensure database operations do not block the libp2p threads
    private val coordinatorScope = CoroutineScope(Dispatchers.IO)

    // Expose explicit failure paths (DB errors, host down) upstream to the UI or system loggers
    private val _errorFlow = MutableSharedFlow<RepositoryResult.Failure>()
    val errorFlow: SharedFlow<RepositoryResult.Failure> = _errorFlow.asSharedFlow()

    init {
        // Wire the updated callback providing both the remote PeerId and the binary payload
        p2pManager.setIncomingMessageListener { peerId, payload ->
            handleIncomingMessage(peerId, payload)
        }
    }

    private fun handleIncomingMessage(peerId: String, payload: ByteArray) {
        coordinatorScope.launch {
            val localPeerId = p2pManager.getLocalPeerId()
            
            if (localPeerId == null) {
                // EXPLICIT FAILURE PATH:
                // We drop the message because we cannot accurately record the `toPeerId`.
                // Emitting an explicit failure record rather than silently failing.
                val error = RepositoryResult.Failure(
                    message = "Dropped incoming message from $peerId: Local P2P host is not running (localPeerId is null).",
                    cause = IllegalStateException("Local host down during receive")
                )
                _errorFlow.emit(error)
                return@launch
            }

            val payloadString = try {
                String(payload, Charsets.UTF_8)
            } catch (e: Exception) {
                _errorFlow.emit(
                    RepositoryResult.Failure(
                        message = "Failed to decode incoming byte payload from $peerId to UTF-8 String",
                        cause = e
                    )
                )
                return@launch
            }

            // Route to persistence and encryption layer
            val result = messageRepository.processAndSaveMessage(
                incomingPayload = payloadString,
                fromPeerId = peerId,
                toPeerId = localPeerId
            )

            // Surface persistence failures upstream
            if (result is RepositoryResult.Failure) {
                _errorFlow.emit(result)
            }
        }
    }

    /**
     * Cancels background coroutines and unbinds listeners to prevent memory leaks on teardown.
     */
    fun shutdown() {
        p2pManager.clearIncomingMessageListener()
        coordinatorScope.cancel("MessageCoordinator shutting down")
    }
}