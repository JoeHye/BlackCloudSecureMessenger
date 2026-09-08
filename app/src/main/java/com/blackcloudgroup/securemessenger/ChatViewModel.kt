// File 2 of 2: ChatViewModel.kt
package com.blackcloudgroup.securemessenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

data class ChatMessageUiModel(
    val id: String,
    val senderPeerId: String,
    val text: String,
    val timestamp: Long,
    val isSentByMe: Boolean
)

data class ChatUiState(
    val isHostRunning: Boolean = false,
    val localPeerId: String? = null,
    val listenAddresses: List<String> = emptyList(),
    val targetMultiaddr: String = "",
    val connectedPeerId: String? = null,
    val isConnecting: Boolean = false,
    val messages: List<ChatMessageUiModel> = emptyList(),
    val messageInput: String = "",
    val isSending: Boolean = false,
    val errorMessage: String? = null
)

class ChatViewModel(
    private val p2pManager: P2PManager,
    private val messageRepository: MessageRepository,
    private val messageCoordinator: MessageCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.Main) {
            messageCoordinator.errorFlow.collect { failure ->
                showError("Coordinator Error: ${failure.message}")
            }
        }
    }

    fun startHost(listenInterface: String = "0.0.0.0", port: Int = 10001) {
        when (val result = p2pManager.startHost(listenInterface, port)) {
            is RepositoryResult.Success<PeerId> -> {
                _uiState.update { state ->
                    state.copy(
                        isHostRunning = true,
                        localPeerId = result.value.toBase58(),
                        listenAddresses = p2pManager.getListenAddresses()
                    )
                }
            }
            is RepositoryResult.Failure -> {
                showError("Failed to start host: ${result.message}")
            }
        }
    }

    fun updateTargetMultiaddr(multiaddr: String) {
        _uiState.update { it.copy(targetMultiaddr = multiaddr) }
    }

    fun connectToPeer() {
        val multiaddr = _uiState.value.targetMultiaddr
        if (multiaddr.isBlank()) {
            showError("Multiaddr cannot be empty")
            return
        }

        _uiState.update { it.copy(isConnecting = true) }

        p2pManager.connectToPeer(multiaddr).whenComplete { result, throwable ->
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.update { it.copy(isConnecting = false) }

                if (throwable != null) {
                    showError("Connection exception: ${throwable.message}")
                    return@launch
                }

                when (result) {
                    is RepositoryResult.Success -> {
                        val peerId = extractPeerIdFromMultiaddr(multiaddr)
                        if (peerId != null) {
                            _uiState.update { it.copy(connectedPeerId = peerId) }
                            startPollingMessages(peerId)
                        } else {
                            showError("Connected, but failed to parse Peer ID from multiaddr")
                        }
                    }
                    is RepositoryResult.Failure -> {
                        showError("Failed to connect: ${result.message}")
                    }
                }
            }
        }
    }

    private fun extractPeerIdFromMultiaddr(multiaddr: String): String? {
        return try {
            Multiaddr.fromString(multiaddr).getPeerId()?.toBase58()
        } catch (e: Exception) {
            null
        }
    }

    fun updateMessageInput(input: String) {
        _uiState.update { it.copy(messageInput = input) }
    }

    fun sendMessage() {
        val state = _uiState.value
        val targetPeerId = state.connectedPeerId
        val payloadText = state.messageInput
        val localPeerId = state.localPeerId

        if (targetPeerId == null) {
            showError("Not connected to a peer")
            return
        }
        if (payloadText.isBlank()) return
        if (localPeerId == null) {
            showError("Local host is not running")
            return
        }

        _uiState.update { it.copy(isSending = true) }

        val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
        p2pManager.sendMessage(targetPeerId, payloadBytes).whenComplete { result, throwable ->
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.update { it.copy(isSending = false) }

                if (throwable != null) {
                    showError("Failed to send: ${throwable.message}")
                    return@launch
                }

                when (result) {
                    is RepositoryResult.Success -> {
                        _uiState.update { it.copy(messageInput = "") }
                        fetchMessages(targetPeerId)
                    }
                    is RepositoryResult.Failure -> {
                        showError("Send failed: ${result.message}")
                    }
                }
            }
        }
    }

    private fun startPollingMessages(peerId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                fetchMessages(peerId)
                delay(2000L) // Poll interval for the MVP
            }
        }
    }

    private suspend fun fetchMessages(peerId: String) {
        when (val dbResult = messageRepository.getMessagesForPeer(peerId)) {
            is RepositoryResult.Success -> {
                val localId = p2pManager.getLocalPeerId() ?: ""

                val decryptedMessages = dbResult.value.map { entity ->
                    val text = when (val decryptResult = messageRepository.decryptPayload(peerId, entity.encryptedPayload)) {
                        is RepositoryResult.Success -> decryptResult.value
                        is RepositoryResult.Failure -> "[Decryption Failed: ${decryptResult.message}]"
                    }

                    ChatMessageUiModel(
                        id = entity.id.toString(),
                        senderPeerId = entity.senderPeerId,
                        text = text,
                        timestamp = entity.timestamp,
                        isSentByMe = (entity.senderPeerId == localId)
                    )
                }.sortedBy { it.timestamp }

                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.update { it.copy(messages = decryptedMessages) }
                }
            }
            is RepositoryResult.Failure -> {
                viewModelScope.launch(Dispatchers.Main) {
                    showError("Failed to load messages: ${dbResult.message}")
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        p2pManager.stopHost()
        messageCoordinator.shutdown()
    }
}