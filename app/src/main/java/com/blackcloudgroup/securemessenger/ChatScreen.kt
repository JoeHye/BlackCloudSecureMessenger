// ChatScreen.kt
package com.blackcloudgroup.securemessenger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Black Cloud Secure Messenger", fontSize = 18.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            HostStatusSection(state = state, onStartHost = { viewModel.startHost() })
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            
            ConnectionSection(
                state = state,
                onMultiaddrChange = viewModel::updateTargetMultiaddr,
                onConnect = viewModel::connectToPeer
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            MessageList(
                messages = state.messages,
                modifier = Modifier.weight(1f)
            )

            MessageInputSection(
                state = state,
                onMessageChange = viewModel::updateMessageInput,
                onSend = viewModel::sendMessage
            )
        }
    }
}

@Composable
fun HostStatusSection(state: ChatUiState, onStartHost: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (state.isHostRunning) "Host Status: RUNNING" else "Host Status: STOPPED",
                fontWeight = FontWeight.Bold,
                color = if (state.isHostRunning) Color(0xFF2E7D32) else Color.Red
            )
            if (!state.isHostRunning) {
                Button(onClick = onStartHost) {
                    Text("Start Host")
                }
            }
        }
        if (state.isHostRunning && state.localPeerId != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Local Peer ID:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(state.localPeerId, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            
            Spacer(modifier = Modifier.height(4.dp))
            Text("Listen Addresses:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            state.listenAddresses.forEach { addr ->
                Text(addr, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ConnectionSection(
    state: ChatUiState,
    onMultiaddrChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Connect to Peer", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.targetMultiaddr,
                onValueChange = onMultiaddrChange,
                modifier = Modifier.weight(1f),
                label = { Text("Multiaddr") },
                singleLine = true,
                enabled = !state.isConnecting && state.connectedPeerId == null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onConnect,
                enabled = state.isHostRunning && state.targetMultiaddr.isNotBlank() && !state.isConnecting && state.connectedPeerId == null
            ) {
                if (state.isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Text(if (state.connectedPeerId != null) "Connected" else "Connect")
                }
            }
        }
        if (state.connectedPeerId != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Connected to: ${state.connectedPeerId.take(12)}...",
                color = Color(0xFF2E7D32),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MessageList(messages: List<ChatMessageUiModel>, modifier: Modifier = Modifier) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(messages) { msg ->
            val alignment = if (msg.isSentByMe) Alignment.CenterEnd else Alignment.CenterStart
            val bgColor = if (msg.isSentByMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            val textColor = if (msg.isSentByMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = alignment
            ) {
                Column(
                    modifier = Modifier
                        .background(color = bgColor, shape = RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .widthIn(max = 280.dp)
                ) {
                    Text(
                        text = msg.text,
                        color = textColor,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dateFormat.format(Date(msg.timestamp)),
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

@Composable
fun MessageInputSection(
    state: ChatUiState,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = state.messageInput,
            onValueChange = onMessageChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Enter message...") },
            enabled = state.connectedPeerId != null && !state.isSending,
            maxLines = 3
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onSend,
            enabled = state.connectedPeerId != null && state.messageInput.isNotBlank() && !state.isSending
        ) {
            if (state.isSending) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text("Send")
            }
        }
    }
}